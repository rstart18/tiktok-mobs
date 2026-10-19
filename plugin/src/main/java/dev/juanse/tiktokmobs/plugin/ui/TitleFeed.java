package dev.juanse.tiktokmobs.plugin.ui;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.time.Duration;

/**
 * El nombre del donante en mitad de la pantalla.
 *
 * <p>Por qué existe: el chat de Minecraft queda en una esquina y en un vertical de TikTok no se
 * lee. El título va al centro y se ve en cámara sin tocar OBS, que es justo lo que hace falta
 * cuando transmites desde el propio cliente.
 *
 * <p>Con un freno: en un combo de 30 rosas llegan 30 acciones seguidas, y 30 títulos encadenados
 * tapan la pantalla entera y no dejan jugar. Así que como mucho uno por ventana; el resto se
 * descarta en vez de encolarse, porque un título que sale 10 segundos tarde miente.
 */
public final class TitleFeed {

    /** Lo que no se muestra. Para cuando está desactivado en el config. */
    public static final TitleFeed DISABLED = new TitleFeed(false, 0);

    private final boolean enabled;
    private final long minGapMs;
    private long lastShown;

    public TitleFeed(boolean enabled, long minGapMs) {
        this.enabled = enabled;
        this.minGapMs = Math.max(0, minGapMs);
    }

    /**
     * @param donor lo que se ve grande: el nombre, que es lo que el donante vino a ver
     * @param what  lo que pasó, abajo y más pequeño
     */
    public void show(String donor, String what) {
        if (!enabled || donor == null || donor.isBlank()) return;

        long now = System.currentTimeMillis();
        if (now - lastShown < minGapMs) return;
        lastShown = now;

        Title title = Title.title(
                Component.text(donor, NamedTextColor.GOLD),
                Component.text(what == null ? "" : what, NamedTextColor.WHITE),
                Title.Times.times(
                        Duration.ofMillis(150),
                        Duration.ofMillis(1400),
                        Duration.ofMillis(350)));

        for (Player player : Bukkit.getOnlinePlayers()) {
            player.showTitle(title);
        }
    }
}
