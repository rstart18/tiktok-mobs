// core: dominio puro. CERO dependencias de Bukkit, cero red, cero I/O.
// Todo lo que decide "este regalo -> esta acción" vive aquí y se testea sin servidor.
dependencies {
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
