package dev.juanse.tiktokmobs.plugin.runtime;

import dev.juanse.tiktokmobs.core.LifePool;
import dev.juanse.tiktokmobs.core.Mission;
import dev.juanse.tiktokmobs.plugin.boss.Fx;
import dev.juanse.tiktokmobs.plugin.ui.Anim;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Set;
import java.util.function.Function;

/**
 * Cuenta lo que el streamer consigue <b>con sus manos</b>.
 *
 * <p>Cuenta bloques picados y no objetos en el inventario, y esa es toda la gracia: una
 * donacion puede regalarte un diamante, pero no puede bajar a la mina por ti. Si la mision
 * mirara el inventario, el publico podria completarsela a base de regalos y la busqueda
 * dejaria de significar nada.
 *
 * <p>{@code MONITOR} con {@code ignoreCancelled}: si una proteccion de terreno cancelo la
 * rotura, el bloque no se pico y no cuenta.
 */
public final class MissionListener implements Listener {

    private final JavaPlugin plugin;
    private final TargetResolver targets;
    private final Function<String, Mission> misionDe;
    private final Function<String, LifePool> vidasDe;
    private final Set<Material> cuentan;
    private final int vidasDePremio;

    public MissionListener(JavaPlugin plugin, TargetResolver targets,
                           Function<String, Mission> misionDe,
                           Function<String, LifePool> vidasDe,
                           Set<Material> cuentan, int vidasDePremio) {
        this.plugin = plugin;
        this.targets = targets;
        this.misionDe = misionDe;
        this.vidasDe = vidasDe;
        this.cuentan = cuentan;
        this.vidasDePremio = Math.max(0, vidasDePremio);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        if (cuentan.isEmpty() || !cuentan.contains(event.getBlock().getType())) return;

        Player quien = event.getPlayer();
        String streamerId = targets.streamerOf(quien);
        if (streamerId == null) return;

        Mission mision = misionDe.apply(streamerId);
        if (mision == null || !mision.activa()) return;

        int vueltas = mision.record(1);
        if (vueltas <= 0) return;

        premiar(quien, streamerId, mision);
    }

    /**
     * Cumplir tiene que sentirse como el momento mas grande del directo.
     *
     * <p>Si al completar la mision solo saliera una linea gris en el chat, nadie querria
     * completarla: el esfuerzo fue del que juega y la recompensa tiene que ocupar la
     * pantalla. De ahi la animacion del totem, que es la mas reconocible del juego.
     *
     * <p>Y las vidas cierran el bucle: te matan y pierdes, cumples y recuperas, asi que
     * aguantas mas y puedes cumplir otra vez. Las dos barras tiran una de otra.
     */
    private void premiar(Player quien, String streamerId, Mission mision) {
        Anim.totem(quien);
        Anim.tituloOnda(plugin, quien, mision.label() + " COMPLETADA",
                "siguiente: " + mision.objetivo());
        Fx.sound(quien.getLocation(), "minecraft:ui.toast.challenge_complete", 1.0f, 1.0f);
        Fx.burst(quien.getLocation());

        LifePool vidas = vidasDe == null ? null : vidasDe.apply(streamerId);
        int dadas = 0;
        if (vidas != null) {
            for (int i = 0; i < vidasDePremio; i++) {
                if (vidas.ganar()) dadas++;
            }
        }

        anunciar("&b&l\\u2726 " + mision.label() + " COMPLETADA"
                + (dadas > 0 ? " &a&l+" + dadas + " VIDA(S)" : "")
                + " &7- siguiente: &b" + mision.objetivo());
    }

    private void anunciar(String legacy) {
        var mensaje = LegacyComponentSerializer.legacyAmpersand().deserialize(legacy);
        for (Player player : Bukkit.getOnlinePlayers()) {
            player.sendMessage(mensaje);
        }
    }
}
