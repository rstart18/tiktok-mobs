package dev.juanse.tiktokmobs.plugin.runtime;

import dev.juanse.tiktokmobs.core.ActionResolver;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Guarda el marcador del live en disco.
 *
 * <p>Sin esto, un reinicio del servidor —o un corte de luz— borra las monedas acumuladas,
 * el top de donantes y los likes. En mitad de un directo eso es perder el progreso de la
 * meta delante de la gente que acaba de pagarla.
 *
 * <p>Se escribe cada pocos segundos y al apagar. Es un YAML pequenio: aunque se guarde a
 * menudo no se nota.
 */
public final class LedgerStore {

    private static final String ARCHIVO = "ledger.yml";

    private final JavaPlugin plugin;
    private final ActionResolver resolver;
    private final File archivo;

    public LedgerStore(JavaPlugin plugin, ActionResolver resolver) {
        this.plugin = plugin;
        this.resolver = resolver;
        this.archivo = new File(plugin.getDataFolder(), ARCHIVO);
    }

    public void load() {
        if (!archivo.exists()) return;

        try {
            YamlConfiguration yaml = YamlConfiguration.loadConfiguration(archivo);

            // Dos formatos conviven:
            //  - El plano, de los primeros meses: 'chaos-level', 'chaos-donations' y 'donors.*'
            //    sueltos. Es el live "main".
            //  - El nuevo, con un bloque 'streamers.<id>.*' por live.
            // Si existe el bloque nuevo, se usa; si no, se cae al plano para no perder nada
            // en el primer arranque despues de actualizar el plugin.
            int lives = 0;
            if (yaml.isConfigurationSection("streamers")) {
                ConfigurationSection seccion = yaml.getConfigurationSection("streamers");
                for (String streamerId : seccion.getKeys(false)) {
                    loadStreamer(seccion.getConfigurationSection(streamerId), streamerId);
                    lives++;
                }
            } else {
                loadLegacy(yaml);
                lives = 1;
            }

            // Los regalos aprendidos: id -> nombre y monedas. Globales, iguales que GiftRegistry.
            // Sin esto, el panel enseña los ids pelados tras cada reinicio y no hay forma de
            // saber cual es la rosa.
            int regalos = 0;
            if (yaml.isConfigurationSection("gifts-vistos")) {
                var seccion = yaml.getConfigurationSection("gifts-vistos");
                for (String rawId : seccion.getKeys(false)) {
                    try {
                        int id = Integer.parseInt(rawId);
                        resolver.gifts().restore(id,
                                seccion.getString(rawId + ".nombre", ""),
                                seccion.getInt(rawId + ".monedas", 0),
                                seccion.getInt(rawId + ".veces", 0),
                                seccion.getString(rawId + ".imagen", ""));
                        regalos++;
                    } catch (NumberFormatException ignored) {
                        // una clave que no es un id: no es motivo para abortar la carga
                    }
                }
            }

            plugin.getLogger().info("[ledger] " + regalos + " regalo(s) conocidos recuperados");
            plugin.getLogger().info("[ledger] recuperado: " + lives + " live(s), "
                    + resolver.ledger("main").totalCoins() + " monedas en 'main', "
                    + resolver.likes("main").total() + " likes en 'main', caos nivel "
                    + resolver.chaos("main").level() + " en 'main'");
        } catch (RuntimeException ex) {
            plugin.getLogger().warning("[ledger] no pude leer " + ARCHIVO + ": " + ex);
        }
    }

    private void loadLegacy(YamlConfiguration yaml) {
        Map<String, Integer> donantes = new LinkedHashMap<>();
        if (yaml.isConfigurationSection("donors")) {
            for (String nombre : yaml.getConfigurationSection("donors").getKeys(false)) {
                donantes.put(nombre, yaml.getInt("donors." + nombre, 0));
            }
        }

        resolver.ledger("main").restore(donantes);
        resolver.likes("main").restore(yaml.getInt("likes", 0));
        // El caos es acumulado del live: si se reinicia el servidor a mitad, volver a nivel 0
        // le quita a la gente lo que ya habia pagado entre todos.
        resolver.chaos("main").restore(
                yaml.getInt("chaos-donations", 0),
                yaml.getInt("chaos-level", 0));
    }

    private void loadStreamer(ConfigurationSection seccion, String streamerId) {
        if (seccion == null) return;

        Map<String, Integer> donantes = new LinkedHashMap<>();
        if (seccion.isConfigurationSection("donors")) {
            for (String nombre : seccion.getConfigurationSection("donors").getKeys(false)) {
                donantes.put(nombre, seccion.getInt("donors." + nombre, 0));
            }
        }

        resolver.ledger(streamerId).restore(donantes);
        resolver.likes(streamerId).restore(seccion.getInt("likes", 0));
        resolver.chaos(streamerId).restore(
                seccion.getInt("chaos-donations", 0),
                seccion.getInt("chaos-level", 0));
    }

    public void save() {
        try {
            if (!plugin.getDataFolder().exists() && !plugin.getDataFolder().mkdirs()) return;

            YamlConfiguration yaml = new YamlConfiguration();
            // C8 solo lee los dos formatos; el escritor sigue en el plano del live "main"
            // hasta C9, para que un rollback del codigo nuevo pueda releer este archivo.
            yaml.set("likes", resolver.likes().total());
            yaml.set("total-coins", resolver.ledger().totalCoins());
            yaml.set("chaos-level", resolver.chaos().level());
            yaml.set("chaos-donations", resolver.chaos().donations());

            for (var visto : resolver.gifts().seen()) {
                String ruta = "gifts-vistos." + visto.id();
                yaml.set(ruta + ".nombre", visto.name());
                yaml.set(ruta + ".monedas", visto.coins());
                yaml.set(ruta + ".veces", visto.times());
                if (!visto.imageUrl().isBlank()) yaml.set(ruta + ".imagen", visto.imageUrl());
            }
            resolver.ledger().snapshot().forEach((nombre, monedas) ->
                    yaml.set("donors." + nombre, monedas));

            yaml.save(archivo);
        } catch (IOException | RuntimeException ex) {
            plugin.getLogger().warning("[ledger] no pude guardar " + ARCHIVO + ": " + ex);
        }
    }

    /** Borra el marcador guardado (lo usa /ttmobs reset). */
    public void delete() {
        if (archivo.exists() && !archivo.delete()) {
            plugin.getLogger().warning("[ledger] no pude borrar " + ARCHIVO);
        }
    }
}
