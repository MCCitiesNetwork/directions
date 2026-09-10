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
}
