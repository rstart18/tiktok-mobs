package dev.juanse.tiktokmobs.plugin.ui;

import dev.juanse.tiktokmobs.core.LikeGoal;
import dev.juanse.tiktokmobs.core.dedup.LikeAccumulator;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

/**
 * La meta de likes, con su nombre: {@code WITHER - 48740 / 60000 likes}.
 *
 * <p>Va aparte de la meta de donacion a proposito. Aquella la empujan los que pagan; esta la
 * empuja todo el mundo, porque los likes son gratis. Es el unico marcador del directo en el
 * que puede participar quien no gasta, que suele ser la mayoria de la sala.
 */
public final class LikeGoalBar {

    private final JavaPlugin plugin;
    private final LikeGoal meta;
    private final LikeAccumulator likes;
    private final BossBar bar;
    private BukkitTask task;

    public LikeGoalBar(JavaPlugin plugin, LikeGoal meta, LikeAccumulator likes) {
        this.plugin = plugin;
        this.meta = meta;
        this.likes = likes;
        this.bar = BossBar.bossBar(
                texto(), 0.0f, BossBar.Color.PINK, BossBar.Overlay.PROGRESS);
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
        int total = likes.total();
        bar.name(texto());
        bar.progress(meta.fraccion(total));
    }

    private Component texto() {
        int total = likes.total();
        return Component.text(
                meta.label() + "  " + meta.dentro(total) + " / " + meta.target() + " likes",
                NamedTextColor.LIGHT_PURPLE);
    }
}
