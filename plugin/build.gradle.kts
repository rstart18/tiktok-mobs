// plugin: adaptador Paper. No se empaqueta ninguna dependencia externa:
//  - el cliente WebSocket es java.net.http (JDK 11+)
//  - Gson ya viene dentro de Paper/Spigot
// Resultado: nada de shade, nada de relocate, cero choques con el Netty del servidor.
dependencies {
    implementation(project(":core"))
    compileOnly("io.papermc.paper:paper-api:${rootProject.extra["paperApiVersion"]}")
    // Floodgate NO se declara como dependencia: se detecta por reflexión en runtime,
    // así el jar funciona igual en un servidor sin Geyser instalado.

    // El parser de TikFinity es la pieza más frágil del proyecto (formato no documentado),
    // así que se testea aparte, sin servidor: solo necesita Gson.
    testImplementation("com.google.code.gson:gson:2.11.0")
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.processResources {
    val tokens = mapOf(
        "version" to project.version.toString(),
        "apiVersion" to rootProject.extra["pluginApiVersion"].toString()
    )
    inputs.properties(tokens)
    filesMatching("plugin.yml") { expand(tokens) }
}

tasks.jar {
    archiveBaseName.set("TikTokMobs")
    // el core es dominio puro: va dentro del jar sin relocate (no hay conflicto posible)
    from(project(":core").sourceSets["main"].output)
}

tasks.named("build") { dependsOn(tasks.jar) }
