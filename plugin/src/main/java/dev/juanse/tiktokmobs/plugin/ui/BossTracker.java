package dev.juanse.tiktokmobs.plugin.ui;

import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * Barra de vida para los jefes, con el nombre del donante encima.
 * Es la diferencia entre "apareció un ravager" y "el ravager de Pepita va por la mitad":
 * da algo que mirar y por qué gritar en el chat.
 */
public final class BossTracker {

    private record Entry(BossBar bar, LivingEntity entity, double peakHealth) {
    }

    private final JavaPlugin plugin;
    private final Map<UUID, Entry> bars = new HashMap<>();
    private BukkitTask task;

    public BossTracker(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void start() {
        stop();
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::update, 20L, 10L);
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        clear();
    }

    public void track(LivingEntity entity, String label) {
        if (entity == null || !entity.isValid()) return;

        BossBar bar = BossBar.bossBar(
                Component.text(label, NamedTextColor.LIGHT_PURPLE),
                1.0f,
                BossBar.Color.PURPLE,
                BossBar.Overlay.NOTCHED_10);

        bars.put(entity.getUniqueId(), new Entry(bar, entity, Math.max(1.0, entity.getHealth())));
        for (Player player : Bukkit.getOnlinePlayers()) {
            player.showBossBar(bar);
        }
    }

    private void update() {
        for (Iterator<Map.Entry<UUID, Entry>> it = bars.entrySet().iterator(); it.hasNext(); ) {
            Entry entry = it.next().getValue();

            if (!entry.entity().isValid() || entry.entity().isDead()) {
                hide(entry.bar());
                it.remove();
                continue;
            }

            float progress = (float) Math.max(0.0,
                    Math.min(1.0, entry.entity().getHealth() / entry.peakHealth()));
            entry.bar().progress(progress);
        }
    }

    /** Un jugador que entra a mitad de la pelea también debe ver las barras activas. */
    public void showAllTo(Player player) {
        for (Entry entry : bars.values()) {
            player.showBossBar(entry.bar());
        }
    }

    public void clear() {
        for (Entry entry : bars.values()) {
            hide(entry.bar());
        }
        bars.clear();
    }

    public int active() {
        return bars.size();
    }

    private void hide(BossBar bar) {
        for (Player player : Bukkit.getOnlinePlayers()) {
            player.hideBossBar(bar);
        }
    }
}
