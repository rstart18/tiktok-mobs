package dev.juanse.tiktokmobs.plugin.ui;

import dev.juanse.tiktokmobs.core.LifePool;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

/**
 * Las vidas, arriba de la pantalla: {@code VIDA 17 / 20}.
 *
 * <p>Un contador que no se ve no existe. El numero en camara es lo que convierte las muertes
 * en marcador y lo que hace que el chat empiece a pedir vidas extra cuando lo ve bajar.
 *
 * <p>Cambia de color con lo que queda: lo que avisa de que la cosa va mal es el color, no
 * leer el numero. A cinco vidas la barra ya esta roja aunque nadie este mirando la cifra.
 */
public final class LifeBar {

    private final JavaPlugin plugin;
    private final LifePool vidas;
    private final BossBar bar;
    private BukkitTask task;
    private BossBar.Color colorActual;

    public LifeBar(JavaPlugin plugin, LifePool vidas) {
        this.plugin = plugin;
        this.vidas = vidas;
        this.colorActual = BossBar.Color.GREEN;
        this.bar = BossBar.bossBar(
                texto(), 1.0f, BossBar.Color.GREEN, BossBar.Overlay.NOTCHED_10);
    }

    public void start() {
        stop();
        for (Player player : Bukkit.getOnlinePlayers()) {
            player.showBossBar(bar);
        }
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::update, 20L, 20L);
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            player.hideBossBar(bar);
        }
    }

    public void showTo(Player player) {
        player.showBossBar(bar);
    }

    private void update() {
        bar.name(texto());
        bar.progress(vidas.fraccion());

        BossBar.Color color = color();
        // Solo se reasigna cuando cambia: la bossbar reenvia el paquete a cada jugador en
        // cada set, y esto corre una vez por segundo.
        if (color != colorActual) {
            colorActual = color;
            bar.color(color);
        }
    }

    private Component texto() {
        return Component.text("VIDA " + vidas.vidas() + " / " + vidas.max(), colorTexto());
    }

    private BossBar.Color color() {
        float f = vidas.fraccion();
        if (f <= 0.25f) return BossBar.Color.RED;
        if (f <= 0.5f) return BossBar.Color.YELLOW;
        return BossBar.Color.GREEN;
    }

    private NamedTextColor colorTexto() {
        float f = vidas.fraccion();
        if (f <= 0.25f) return NamedTextColor.RED;
        if (f <= 0.5f) return NamedTextColor.YELLOW;
        return NamedTextColor.GREEN;
    }
}
