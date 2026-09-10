plugins {
    java
    id("com.gradleup.shadow") version "8.3.5"
    id("xyz.jpenilla.run-paper") version "2.3.1"
}

group = "com.minecraftcitiesnetwork"
version = "3.0.0"

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://maven.enginehub.org/repo/")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.8-R0.1-SNAPSHOT")
    compileOnly("org.jetbrains:annotations:26.0.2")
    compileOnly("com.sk89q.worldguard:worldguard-bukkit:7.0.12") {
        isTransitive = false
    }
    compileOnly("com.sk89q.worldguard:worldguard-core:7.0.12") {
        isTransitive = false
    }
    compileOnly("com.sk89q.worldedit:worldedit-bukkit:7.3.0") {
        isTransitive = false
    }
    compileOnly("com.sk89q.worldedit:worldedit-core:7.3.0") {
        isTransitive = false
    }
    implementation("org.spongepowered:configurate-yaml:4.2.0")
    // Pathetic A* engine (pure Java, MIT). Only pathfinding.WalkPathfinder may import it.
    implementation("de.bsommerfeld.pathetic:engine:5.5.2")
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks {
    shadowJar {
        archiveClassifier.set("")
        relocate("org.spongepowered.configurate", "com.minecraftcitiesnetwork.directions.libs.configurate")
        relocate("org.yaml.snakeyaml", "com.minecraftcitiesnetwork.directions.libs.snakeyaml")
        relocate("de.bsommerfeld.pathetic", "com.minecraftcitiesnetwork.directions.libs.pathetic")
    }

    build {
        dependsOn(shadowJar)
    }

    test {
        useJUnitPlatform {
            excludeTags("perf", "fuzz-long")
        }
        // Real-terrain (L1) tests read captures from sample-data/ and skip when it is absent.
        val sampleData = System.getenv("DIRECTIONS_SAMPLE_DATA") ?: rootDir.resolve("sample-data").absolutePath
        systemProperty("directions.sampleData", sampleData)
    }

    register<Test>("perfTest") {
        description = "L3 performance thresholds on captured and synthetic grids."
        group = "verification"
        testClassesDirs = sourceSets["test"].output.classesDirs
        classpath = sourceSets["test"].runtimeClasspath
        useJUnitPlatform { includeTags("perf") }
        systemProperty("directions.sampleData", System.getenv("DIRECTIONS_SAMPLE_DATA") ?: rootDir.resolve("sample-data").absolutePath)
    }

    register<Test>("fuzzLong") {
        description = "L2 long fuzz run (5000 seeds)."
        group = "verification"
        testClassesDirs = sourceSets["test"].output.classesDirs
        classpath = sourceSets["test"].runtimeClasspath
        useJUnitPlatform { includeTags("fuzz-long") }
    }
    
    processResources {
        val props = mapOf("version" to version)
        inputs.properties(props)
        filesMatching(listOf("paper-plugin.yml")) {
            expand(props)
        }
    }

    // ---- local test server -------------------------------------------------------------------
    // `./gradlew runServer` starts Paper 1.21.8 in run/ with WorldGuard and WorldEdit. When the
    // git-ignored sample-data/ folder holds the real city world it is copied in on first run.
    val sampleData = file(System.getenv("DIRECTIONS_SAMPLE_DATA") ?: "sample-data")

    register("prepareRunDir") {
        description = "Prepares run/ with the sample world, regions and live configs when available."
        doLast {
            val run = file("run")
            run.mkdirs()
            run.resolve("eula.txt").writeText("eula=true\n")
            val world = sampleData.resolve("world/Reveille")
            if (world.isDirectory) {
                val target = run.resolve("Reveille")
                if (!target.exists()) {
                    logger.lifecycle("Copying sample world into run/ (one-time, ~2 GB)")
                    copy { from(world); into(target) }
                }
                copy { from(sampleData.resolve("worldguard/regions.yml")); into(run.resolve("plugins/WorldGuard/worlds/Reveille")) }
                copy { from(sampleData.resolve("config")); into(run.resolve("plugins/Directions")) }
                run.resolve("server.properties").writeText("level-name=Reveille\nonline-mode=false\nspawn-protection=0\nview-distance=8\nsimulation-distance=4\n")
            } else if (!run.resolve("server.properties").exists()) {
                run.resolve("server.properties").writeText("online-mode=false\nspawn-protection=0\n")
            }
        }
    }

    runServer {
        minecraftVersion("1.21.8")
        runDirectory.set(file("run"))
        downloadPlugins {
            modrinth("worldguard", "7.0.14")
            modrinth("worldedit", "7.3.19")
        }
        jvmArgs("-Dcom.mojang.eula.agree=true", "-Xmx4G")
        dependsOn("prepareRunDir")
    }

    // `./gradlew serverSmoke` runs the headless self-test on the synthetic course world (no sample
    // data needed) and fails if any scenario fails. `-PsmokeScenarios=<file>` runs another file,
    // for example sample-data/scenarios.yml against the real world in run/.
    val smokeScenarios = (findProperty("smokeScenarios") as String?)?.let { file(it) } ?: file("src/serverTest/scenarios-course.yml")
    val smokeDir = if (findProperty("smokeScenarios") != null) file("run") else file("run-smoke")
    val smokeReport = smokeDir.resolve("plugins/Directions/selftest-report.json")

    register<xyz.jpenilla.runpaper.task.RunServer>("serverSmoke") {
        description = "Headless self-test on a real Paper server; fails on any failing scenario."
        group = "verification"
        minecraftVersion("1.21.8")
        runDirectory.set(smokeDir)
        pluginJars.from(shadowJar.flatMap { it.archiveFile })
        downloadPlugins {
            modrinth("worldguard", "7.0.14")
            modrinth("worldedit", "7.3.19")
        }
        args("--nogui")
        jvmArgs(
            "-Dcom.mojang.eula.agree=true",
            "-Xmx3G",
            "-Ddirections.selftest=" + smokeScenarios.absolutePath,
            "-Ddirections.selftest.report=" + smokeReport.absolutePath
        )
        doFirst {
            smokeDir.mkdirs()
            smokeDir.resolve("eula.txt").writeText("eula=true\n")
            if (!smokeDir.resolve("server.properties").exists()) {
                smokeDir.resolve("server.properties").writeText("online-mode=false\nspawn-protection=0\nview-distance=6\nsimulation-distance=4\nlevel-type=minecraft\\:flat\n")
            }
            smokeReport.delete()
        }
        if (findProperty("smokeScenarios") != null) {
            dependsOn("prepareRunDir")
        }
        finalizedBy("checkSelfTest")
    }

    register("checkSelfTest") {
        description = "Reads the self-test report written by serverSmoke and fails on failures."
        group = "verification"
        doLast {
            if (!smokeReport.exists()) {
                throw GradleException("Self-test produced no report at " + smokeReport)
            }
            @Suppress("UNCHECKED_CAST")
            val report = groovy.json.JsonSlurper().parse(smokeReport) as Map<String, Any>
            val failed = (report["failed"] as Number).toInt()
            val passed = (report["passed"] as Number).toInt()
            @Suppress("UNCHECKED_CAST")
            val results = report["results"] as List<Map<String, Any>>
            for (r in results) {
                logger.lifecycle("  " + (if (r["ok"] == true) "PASS" else "FAIL") + " " + r["name"] + " " + (r["status"] ?: "") + " " + (r["ms"]?.let { "${it} ms" } ?: "") + (r["length"]?.let { " len ${it}" } ?: "") + (r["note"]?.let { " :: $it" } ?: ""))
            }
            logger.lifecycle("Self-test: $passed passed, $failed failed")
            if (failed > 0) {
                throw GradleException("Self-test failed: $failed scenario(s)")
            }
        }
    }
}
