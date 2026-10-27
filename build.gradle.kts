plugins {
    java
    id("com.gradleup.shadow") version "9.6.1"
}

group = "com.minecraftcitiesnetwork"
version = "2.2.0"

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://maven.enginehub.org/repo/")
    maven("https://repo.codemc.io/repository/maven-releases/")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:26.3.build.222-stable")
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
    compileOnly("com.github.retrooper:packetevents-spigot:2.14.0") {
        exclude(group = "net.kyori")
    }
    implementation("org.spongepowered:configurate-yaml:4.2.0")
    testImplementation("io.papermc.paper:paper-api:26.3.build.222-stable")
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.11.3")
}

tasks {
    shadowJar {
        archiveClassifier.set("")
        relocate("org.spongepowered.configurate", "com.minecraftcitiesnetwork.directions.libs.configurate")
        relocate("org.yaml.snakeyaml", "com.minecraftcitiesnetwork.directions.libs.snakeyaml")
    }

    build {
        dependsOn(shadowJar)
    }

    test {
        useJUnitPlatform()
    }
    
    processResources {
        val props = mapOf("version" to version)
        inputs.properties(props)
        filesMatching(listOf("paper-plugin.yml")) {
            expand(props)
        }
    }
}
