plugins {
    // Si no tienes el JDK que pide la versión de Paper objetivo, Gradle lo descarga solo.
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.8.0"
}

rootProject.name = "tiktok-mobs"

include("core")
include("plugin")
