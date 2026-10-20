package dev.juanse.tiktokmobs.plugin.ui;

import dev.juanse.tiktokmobs.core.Mission;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

/**
 * La mision del streamer, arriba: {@code DIAMANTES 3 / 10}.
 *
 * <p>Es la barra que le da un porque al directo. Las otras dos las mueve el publico; esta la
 * mueve el que juega, y verla subir despacio mientras el chat le tira hordas encima es
 * exactamente el conflicto que hace que la gente se quede mirando.
 */
public final class MissionBar {

    private final JavaPlugin plugin;
    private final Mission mision;
    private final BossBar bar;
    private BukkitTask task;

    public MissionBar(JavaPlugin plugin, Mission mision) {
        this.plugin = plugin;
        this.mision = mision;
        this.bar = BossBar.bossBar(
                texto(), 0.0f, BossBar.Color.BLUE, BossBar.Overlay.PROGRESS);
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
        bar.progress(mision.fraccion());
    }

    private Component texto() {
        return Component.text(
                mision.label() + "  " + mision.dentro() + " / " + mision.objetivo(),
                NamedTextColor.AQUA);
    }
}
