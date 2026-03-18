plugins {
    java
    application
}

group = "com.firefoxray"
version = "8.4"

repositories {
    mavenCentral()
}

dependencies {
    implementation("com.fazecast:jSerialComm:2.11.0")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

application {
    mainClass.set("UniversalMonitorControlCenter")
    applicationName = "UniversalMonitorControlCenter"
}

tasks.processResources {
    filesMatching("version.properties") {
        expand("version" to project.version)
    }
}

tasks.jar {
    manifest {
        attributes(mapOf(
            "Main-Class" to application.mainClass.get(),
            "Implementation-Version" to project.version
        ))
    }
}

tasks.register<Jar>("fatJar") {
    group = "build"
    description = "Builds a runnable Control Center jar with all runtime dependencies."
    archiveBaseName.set("UniversalMonitorControlCenter")
    archiveClassifier.set("")
    archiveVersion.set("")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE

    manifest {
        attributes(mapOf(
            "Main-Class" to application.mainClass.get(),
            "Implementation-Version" to project.version
        ))
    }

    from(sourceSets.main.get().output)
    dependsOn(configurations.runtimeClasspath, tasks.processResources)
    from({
        configurations.runtimeClasspath.get().filter { it.exists() }.map {
            if (it.isDirectory) it else zipTree(it)
        }
    })
}
