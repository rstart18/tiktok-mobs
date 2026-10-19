package dev.juanse.tiktokmobs.plugin.config;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.security.SecureRandom;
import java.util.List;
import java.util.Map;

/**
 * El archivo que escribe el panel: {@code catalogo.yml}.
 *
 * <p><b>Por qué un archivo aparte y no el config.</b> El escritor de YAML de Bukkit no conserva
 * comentarios: guardar el config desde el panel borraría de un plumazo todo lo que está
 * documentado ahí dentro —por qué el id manda sobre el nombre, qué mobs no se queman de día,
 * por qué magma y no lava—, que es medio mes de cosas aprendidas en directo. Así que el panel
 * tiene su propio archivo y el {@code config.yml} comentado no se toca nunca.
 *
 * <p>Lo de aquí <b>manda</b> sobre el bloque {@code gift-ids} del config: es lo último que se
 * aplica. Para volver a lo del config, basta con borrar la entrada en el panel.
 */
public final class CatalogStore {

    private static final String ARCHIVO = "catalogo.yml";
    private static final String CABECERA = """
            # ARCHIVO DEL PANEL - se reescribe entero cada vez que guardas desde la web.
            #
            # No lo edites a mano: lo que escribas aqui lo va a borrar el proximo guardado
            # del panel. Para cambios a mano usa config.yml, que esta comentado y no se toca.
            #
            # Lo de este archivo MANDA sobre el bloque 'gift-ids' del config.
            """;

    private final JavaPlugin plugin;
    private final File archivo;
    private YamlConfiguration yaml;

    public CatalogStore(JavaPlugin plugin) {
        this.plugin = plugin;
        this.archivo = new File(plugin.getDataFolder(), ARCHIVO);
        this.yaml = archivo.exists()
                ? YamlConfiguration.loadConfiguration(archivo)
                : new YamlConfiguration();
    }

    /** Todo el archivo del panel: 'gifts', 'gift-ids' y 'tiers'. Lo lee el ConfigLoader. */
    public ConfigurationSection raiz() {
        return yaml;
    }

    /** El bloque de mapeos por id del panel, o null si todavía no hay ninguno. */
    public ConfigurationSection giftIds() {
        return yaml.getConfigurationSection("gift-ids");
    }

    /** true si el panel ya tiene su propia escalera de tramos (manda sobre la del config). */
    public boolean tieneTramos() {
        return !yaml.getMapList("tiers").isEmpty();
    }

    /**
     * El token del panel, generándolo la primera vez.
     *
     * <p>El panel escribe el config del servidor y lo recarga, así que no puede quedar abierto:
     * el puerto del overlay acaba expuesto a internet para que lleguen los relays, y ahí
     * cualquiera podría reconfigurarte el servidor en mitad de un live. Por eso el token no es
     * opcional y por eso se genera solo: un token que hay que inventarse es un token que se
     * queda vacío.
     */
    public String panelToken() {
        String actual = yaml.getString("panel-token", "");
        if (!actual.isBlank()) return actual;

        byte[] bytes = new byte[12];
        new SecureRandom().nextBytes(bytes);
        StringBuilder token = new StringBuilder();
        for (byte b : bytes) {
            token.append(Character.forDigit((b >> 4) & 0xF, 16));
            token.append(Character.forDigit(b & 0xF, 16));
        }

        yaml.set("panel-token", token.toString());
        guardar();
        return token.toString();
    }

    /**
     * Reemplaza TODO el bloque de mapeos por el que manda el panel.
     *
     * <p>Reemplazo completo y no fusión, a propósito: así borrar una fila en el panel la borra
     * de verdad. Con una fusión no habría forma de quitar nada desde la web.
     *
     * @param entradas una por regalo, con las claves que entiende el ConfigLoader
     * @return cuántas quedaron escritas
     */
    public int guardarMapeos(List<Map<String, Object>> entradas) {
        yaml.set("gift-ids", null);

        int escritas = 0;
        for (Map<String, Object> entrada : entradas) {
            Object id = entrada.get("id");
            if (!(id instanceof Number numero)) continue;

            String ruta = "gift-ids." + numero.intValue();
            for (Map.Entry<String, Object> campo : entrada.entrySet()) {
                if (campo.getKey().equals("id")) continue;
                if (campo.getValue() == null) continue;
                if (campo.getValue() instanceof String texto && texto.isBlank()) continue;
                yaml.set(ruta + "." + campo.getKey(), campo.getValue());
            }
            escritas++;
        }

        guardar();
        return escritas;
    }

    /**
     * Reemplaza el bloque de mapeos por nombre.
     *
     * <p>Las claves van tal cual las manda el panel: el buscador las pasa a minúsculas al
     * resolver, así que "Rosa" y "rosa" acaban siendo lo mismo.
     */
    public int guardarNombres(List<Map<String, Object>> entradas) {
        yaml.set("gifts", null);

        int escritas = 0;
        for (Map<String, Object> entrada : entradas) {
            Object nombre = entrada.get("nombre");
            if (!(nombre instanceof String texto) || texto.isBlank()) continue;

            String ruta = "gifts." + texto.trim();
            for (Map.Entry<String, Object> campo : entrada.entrySet()) {
                if (campo.getKey().equals("nombre")) continue;
                if (campo.getValue() == null) continue;
                if (campo.getValue() instanceof String valor && valor.isBlank()) continue;
                yaml.set(ruta + "." + campo.getKey(), campo.getValue());
            }
            escritas++;
        }

        guardar();
        return escritas;
    }

    /**
     * Reemplaza la escalera de tramos entera.
     *
     * <p>Se guardan ordenados por 'max-coins'. El orden en el archivo da igual —la tabla los
     * ordena al construirse— pero un archivo que se lee de barato a caro es un archivo que se
     * puede revisar a ojo cuando algo sale raro en directo.
     */
    public int guardarTramos(List<Map<String, Object>> tramos) {
        List<Map<String, Object>> ordenados = new java.util.ArrayList<>(tramos);
        ordenados.sort(java.util.Comparator.comparingInt(
                t -> t.get("max-coins") instanceof Number n ? n.intValue() : Integer.MAX_VALUE));

        yaml.set("tiers", ordenados);
        guardar();
        return ordenados.size();
    }

    /** Relee el archivo de disco. Se llama en cada reload. */
    public void recargar() {
        yaml = archivo.exists()
                ? YamlConfiguration.loadConfiguration(archivo)
                : new YamlConfiguration();
    }

    private void guardar() {
        try {
            if (!plugin.getDataFolder().exists() && !plugin.getDataFolder().mkdirs()) return;
            yaml.options().setHeader(List.of(CABECERA.split("\n")));
            yaml.save(archivo);
        } catch (IOException ex) {
            plugin.getLogger().warning("[panel] no pude guardar " + ARCHIVO + ": " + ex);
        }
    }
}
