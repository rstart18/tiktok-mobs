package dev.juanse.tiktokmobs.plugin.ui;

import dev.juanse.tiktokmobs.core.PhasePlan;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

/**
 * La fase y su cuenta atrás: {@code CONSTRUYE  04:12}.
 *
 * <p>Es la barra que le habla al <b>público</b>, no al que juega. Sin ella, el chat no sabe
 * si le toca mirar o le toca romper, y un formato por turnos en el que nadie sabe de quién
 * es el turno no es un formato.
 *
 * <p>El color dice el papel de un vistazo: verde mientras construyes tranquilo, rojo cuando
 * las donaciones están abiertas. En los últimos diez segundos parpadea a amarillo, que es
 * cuando conviene que todo el mundo esté mirando.
 */
public final class PhaseBar {

    private final JavaPlugin plugin;
    private final PhasePlan plan;
    private final BossBar bar;
    private BukkitTask task;
    private BossBar.Color colorActual;

    public PhaseBar(JavaPlugin plugin, PhasePlan plan) {
        this.plugin = plugin;
        this.plan = plan;
        this.colorActual = BossBar.Color.GREEN;
        this.bar = BossBar.bossBar(
                Component.empty(), 0f, BossBar.Color.GREEN, BossBar.Overlay.NOTCHED_20);
    }

    public void start() {
        stop();
        for (Player player : Bukkit.getOnlinePlayers()) {
            player.showBossBar(bar);
        }
        // Cada 5 ticks y no cada 20: la cuenta atras de los ultimos segundos tiene que
        // verse moverse, que es el momento en que la barra de verdad importa.
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::update, 5L, 5L);
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
        long ahora = System.currentTimeMillis();
        PhasePlan.Fase fase = plan.actual();
        if (fase == null) {
            bar.name(Component.text("SIN FORMATO", NamedTextColor.GRAY));
            bar.progress(0f);
            return;
        }

        int restan = plan.restantes(ahora);
        String reloj = restan < 0 ? "" : "  " + (restan / 60) + ":" + String.format("%02d", restan % 60);
        bar.name(Component.text(fase.nombre() + reloj, texto(fase, restan)));
        bar.progress(Math.max(0f, 1f - plan.fraccion(ahora)));

        BossBar.Color color = color(fase, restan);
        if (color != colorActual) {
            colorActual = color;
            bar.color(color);
        }
    }

    private BossBar.Color color(PhasePlan.Fase fase, int restan) {
        if (restan >= 0 && restan <= 10) return BossBar.Color.YELLOW;
        return fase.donaciones() ? BossBar.Color.RED : BossBar.Color.GREEN;
    }

    private NamedTextColor texto(PhasePlan.Fase fase, int restan) {
        if (restan >= 0 && restan <= 10) return NamedTextColor.YELLOW;
        return fase.donaciones() ? NamedTextColor.RED : NamedTextColor.GREEN;
    }
}
