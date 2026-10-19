plugins {
    java
}

allprojects {
    group = "dev.juanse.tiktokmobs"
    version = "0.1.0"
}

// Versión objetivo. Por defecto: Paper estable actual (26.2), que exige Java 25.
// Para compilar contra la rama antigua:
//   ./gradlew build -PpaperApi=1.21.11-R0.1-SNAPSHOT -PjavaVersion=21 -PapiVersion=1.21
val paperApiVersion: String = (findProperty("paperApi") as String?) ?: "26.2.build.131-stable"
val javaVersion: Int = ((findProperty("javaVersion") as String?) ?: "25").toInt()
val pluginApiVersion: String = (findProperty("apiVersion") as String?) ?: "26.2"

extra["paperApiVersion"] = paperApiVersion
extra["pluginApiVersion"] = pluginApiVersion

subprojects {
    apply(plugin = "java")

    repositories {
        mavenCentral()
        maven("https://repo.papermc.io/repository/maven-public/")
        maven("https://repo.opencollab.dev/main/")
    }

    extensions.configure<JavaPluginExtension> {
        toolchain.languageVersion.set(JavaLanguageVersion.of(javaVersion))
    }

    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        options.compilerArgs.add("-Xlint:-deprecation")
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        testLogging { events("passed", "failed", "skipped") }
    }
}
