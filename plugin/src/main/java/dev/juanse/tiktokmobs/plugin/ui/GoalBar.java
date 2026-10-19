package dev.juanse.tiktokmobs.plugin.ui;

import dev.juanse.tiktokmobs.core.DonorLedger;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

/**
 * La meta de donación como barra arriba de la pantalla.
 * Visualizar la meta es lo que convierte donaciones sueltas en un objetivo colectivo:
 * el chat empieza a empujar para llenarla.
 */
public final class GoalBar {

    private final JavaPlugin plugin;
    private final DonorLedger ledger;
    private final BossBar bar;
    private BukkitTask task;

    public GoalBar(JavaPlugin plugin, DonorLedger ledger) {
        this.plugin = plugin;
        this.ledger = ledger;
        this.bar = BossBar.bossBar(
                Component.text("Meta de donación", NamedTextColor.GOLD),
                0.0f,
                BossBar.Color.YELLOW,
                BossBar.Overlay.PROGRESS);
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
        int goal = ledger.goalCoins();
        if (goal <= 0) return;

        int within = ledger.totalCoins() % goal;
        bar.name(Component.text("Meta: " + within + " / " + goal + " monedas", NamedTextColor.GOLD));
        bar.progress((float) Math.max(0.0, Math.min(1.0, ledger.goalProgress())));
    }
}
