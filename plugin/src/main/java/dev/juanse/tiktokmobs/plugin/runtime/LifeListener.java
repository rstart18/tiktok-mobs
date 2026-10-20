package dev.juanse.tiktokmobs.plugin.runtime;

import dev.juanse.tiktokmobs.core.LifePool;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.function.Function;

/**
 * Las muertes son las que gastan vidas, no los regalos.
 *
 * <p>Es la decision de diseño que hace que el contador signifique algo. Si el regalo restara
 * una vida directamente, seria un boton de "quitarle un punto" y daria igual lo que pasara en
 * pantalla. Asi, el regalo crea el peligro, el peligro mata y la muerte cuesta: lo que se ve
 * en el juego <i>es</i> lo que mueve el marcador.
 *
 * <p>Llegar a cero no corta el live. Se anuncia, se monta el espectaculo y se rellena, porque
 * un contador que se agota y termina la transmision se usa una vez por noche, y uno que se
 * agota y vuelve a empezar se puede vivir cinco veces.
 */
public final class LifeListener implements Listener {

    /** Lo que pasa al quedarse sin vidas. Existe ya y es lo bastante escandaloso. */
    private static final String CLIMAX = "tnt_rain";

    /** Ticks hasta el climax: lo justo para que el jugador haya reaparecido. */
    private static final long ESPERA_CLIMAX = 60L;

    private final JavaPlugin plugin;
    private final TargetResolver targets;
    private final SpawnRegistry registry;
    private final Function<String, LifePool> vidasDe;

    public LifeListener(JavaPlugin plugin, TargetResolver targets, SpawnRegistry registry,
                        Function<String, LifePool> vidasDe) {
        this.plugin = plugin;
        this.targets = targets;
        this.registry = registry;
        this.vidasDe = vidasDe;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent event) {
        Player muerto = event.getEntity();

        // La muerte de la ruleta rusa ya tiene su propio trato y no deberia cobrarse ademas
        // una vida: una sola donacion no puede castigar dos veces.
        String streamerId = targets.streamerOf(muerto);
        if (streamerId == null) return;

        LifePool vidas = vidasDe.apply(streamerId);
        if (vidas == null) return;

        int quedan = vidas.perder();
        if (quedan > 0) {
            anunciar("&c&l\\u2620 " + muerto.getName() + " cayo &7- quedan &c&l" + quedan
                    + "&7 de &c" + vidas.max());
            return;
        }

        anunciar("&4&l\\u2620\\u2620 SIN VIDAS \\u2620\\u2620 &7- se acabo la ronda");
        vidas.rellenar();

        // El climax va con retraso para que no caiga sobre un jugador que todavia esta
        // en la pantalla de muerte: ahi no lo veria nadie.
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            Player objetivo = targets.resolvePlayer(streamerId);
            if (objetivo == null) return;
            WorldEvents.run(CLIMAX, objetivo.getLocation(), registry, objetivo, streamerId);
            anunciar("&a&l\\u2764 VIDAS AL MAXIMO &7- ronda nueva");
        }, ESPERA_CLIMAX);
    }

    private void anunciar(String legacy) {
        var mensaje = LegacyComponentSerializer.legacyAmpersand().deserialize(legacy);
        for (Player player : Bukkit.getOnlinePlayers()) {
            player.sendMessage(mensaje);
        }
    }
}
