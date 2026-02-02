plugins {
    kotlin("jvm") version "2.1.0"
    application
}

group = "core.coreshift"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

application {
    // The 'Kt' suffix is added by Kotlin to the filename Main.kt
    mainClass.set("core.coreshift.policy.MainKt")
}

dependencies {
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}
