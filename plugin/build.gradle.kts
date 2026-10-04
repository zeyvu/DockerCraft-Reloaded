plugins {
    java
    id("com.gradleup.shadow") version "9.6.1"
}

group = "dev.dockercraft"
version = (findProperty("pluginVersion") as String?) ?: "0.2.0"

// Paper API version. Pinned to a specific build so it always compiles the same way.
// To follow the latest 26.3 build use "26.3.build.+" (or pass -PpaperApi=...).
val paperApi = (findProperty("paperApi") as String?) ?: "26.3.build.134-beta"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:$paperApi")

    // slf4j is already provided by Paper; it is not bundled.
    implementation("com.github.docker-java:docker-java-core:3.4.1") {
        exclude(group = "org.slf4j")
    }
    implementation("com.github.docker-java:docker-java-transport-zerodep:3.4.1") {
        exclude(group = "org.slf4j")
    }
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
}

tasks {
    withType<JavaCompile> {
        options.encoding = "UTF-8"
        options.release.set(25)
    }

    processResources {
        inputs.property("version", project.version)
        filesMatching("plugin.yml") {
            expand("version" to project.version)
        }
    }

    // The "plain" jar (without dependencies) is not meant to be installed.
    jar {
        archiveClassifier.set("plain")
    }

    shadowJar {
        archiveBaseName.set("DockerCraft-Reloaded")
        archiveClassifier.set("")
        mergeServiceFiles()
        // Avoids clashes with libraries from other plugins.
        relocate("com.github.dockerjava", "dev.dockercraft.libs.dockerjava")
        relocate("com.fasterxml.jackson", "dev.dockercraft.libs.jackson")
        relocate("org.apache.commons", "dev.dockercraft.libs.commons")
        exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
    }

    build {
        dependsOn(shadowJar)
    }
}
