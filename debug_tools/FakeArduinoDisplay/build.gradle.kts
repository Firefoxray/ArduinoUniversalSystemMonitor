plugins {
    java
    application
}

group = "com.firefoxray"
version = "1.0"

repositories {
    mavenCentral()
}

dependencies {
    implementation("com.fazecast:jSerialComm:2.11.0")
}

application {
    mainClass.set("UniversalMonitorControlCenter")
}
