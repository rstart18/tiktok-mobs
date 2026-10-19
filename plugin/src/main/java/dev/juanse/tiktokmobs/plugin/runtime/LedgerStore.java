package dev.juanse.tiktokmobs.plugin.runtime;

import dev.juanse.tiktokmobs.core.ActionResolver;
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

            Map<String, Integer> donantes = new LinkedHashMap<>();
            if (yaml.isConfigurationSection("donors")) {
                for (String nombre : yaml.getConfigurationSection("donors").getKeys(false)) {
                    donantes.put(nombre, yaml.getInt("donors." + nombre, 0));
                }
            }

            resolver.ledger().restore(donantes);
            resolver.likes().restore(yaml.getInt("likes", 0));
            // El caos es acumulado del live: si se reinicia el servidor a mitad, volver a
            // nivel 0 le quita a la gente lo que ya habia pagado entre todos.
            resolver.chaos().restore(
                    yaml.getInt("chaos-donations", 0),
                    yaml.getInt("chaos-level", 0));

            // Los regalos aprendidos: id -> nombre y monedas. Sin esto, el panel enseña
            // los ids pelados tras cada reinicio y no hay forma de saber cuál es la rosa.
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
            plugin.getLogger().info("[ledger] recuperado: "
                    + resolver.ledger().totalCoins() + " monedas, "
                    + donantes.size() + " donantes, "
                    + resolver.likes().total() + " likes, caos nivel "
                    + resolver.chaos().level());
        } catch (RuntimeException ex) {
            plugin.getLogger().warning("[ledger] no pude leer " + ARCHIVO + ": " + ex);
        }
    }

    public void save() {
        try {
            if (!plugin.getDataFolder().exists() && !plugin.getDataFolder().mkdirs()) return;

            YamlConfiguration yaml = new YamlConfiguration();
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
