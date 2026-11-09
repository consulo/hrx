plugins {
    // provisions a native-image capable JDK (GraalVM) when none is installed
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "http-cli"
