package dev.juanse.tiktokmobs.plugin.ui;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.time.Duration;

/**
 * Las animaciones de pantalla completa, sin resource pack y sin NMS.
 *
 * <p>Un efecto que ocupa la pantalla entera es lo que hace que una donacion <i>parezca</i>
 * que valio la pena. El mismo creeper, con o sin aviso delante, se recuerda distinto: sin
 * animacion es un bicho que aparece, con animacion es un momento.
 *
 * <p>Todo lo de aqui es vanilla puro. Minecraft ya trae dos efectos que ocupan toda la
 * pantalla y se pueden disparar desde el servidor; no hacia falta inventar nada.
 */
public final class Anim {

    /** Ticks entre fotograma y fotograma del titulo. Mas rapido parpadea, mas lento se arrastra. */
    private static final long PASO = 2L;

    /** La onda de color recorriendo el texto. Oro por delante, blanco detras. */
    private static final TextColor FRENTE = TextColor.color(0xFFE066);
    private static final TextColor FONDO = NamedTextColor.WHITE;

    private Anim() {
    }

    /**
     * La animacion del totem: el totem girando a pantalla completa con su sonido.
     *
     * <p><b>No gasta ningun totem</b> ni toca el inventario. Es el efecto de entidad que el
     * cliente ya sabe dibujar; el servidor solo le dice que lo pinte. Es la animacion mas
     * reconocible del juego, asi que vale para lo que de verdad importa: cumplir la mision.
     */
    public static void totem(Player player) {
        if (player == null || !player.isOnline()) return;
        try {
            player.playEffect(org.bukkit.EntityEffect.TOTEM_RESURRECT);
        } catch (RuntimeException | NoSuchFieldError ignored) {
            // Una version sin ese efecto no puede dejar sin recompensa al que cumplio.
        }
    }

    /**
     * El susto del guardian anciano: la cara ocupando la pantalla y su sonido.
     *
     * <p>Es el contrario del totem. El totem celebra, este aterra, y los dos se ven enteros
     * sin que el espectador tenga que fijarse en una esquina.
     */
    public static void susto(Player player) {
        if (player == null || !player.isOnline()) return;
        try {
            org.bukkit.Particle p = org.bukkit.Particle.valueOf("ELDER_GUARDIAN");
            player.spawnParticle(p, player.getLocation(), 1);
        } catch (RuntimeException | NoSuchFieldError ignored) {
            // idem
        }
    }

    /**
     * Un titulo con una onda de color recorriendo el texto.
     *
     * <p>Los titulos de Minecraft no se pueden animar de verdad: se reenvia el titulo entero
     * cada dos ticks moviendo el resalte una letra. A esa velocidad el ojo lo lee como
     * movimiento, que es lo unico que hace falta.
     *
     * <p>Los tiempos van a cero en los fotogramas intermedios: con fundido, cada reenvio
     * arrancaria su propia entrada y el texto quedaria parpadeando en vez de fluyendo.
     */
    public static void tituloOnda(JavaPlugin plugin, Player player, String texto, String sub) {
        if (player == null || !player.isOnline() || texto == null || texto.isEmpty()) return;

        int pasos = texto.length() + 4;
        for (int i = 0; i < pasos; i++) {
            final int resalte = i;
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (!player.isOnline()) return;
                player.showTitle(Title.title(
                        onda(texto, resalte),
                        Component.text(sub == null ? "" : sub, NamedTextColor.GRAY),
                        Title.Times.times(Duration.ZERO, Duration.ofMillis(400), Duration.ZERO)));
            }, i * PASO);
        }
    }

    /** El texto con una letra resaltada, la que lleva la onda en ese fotograma. */
    private static Component onda(String texto, int resalte) {
        Component out = Component.empty();
        for (int i = 0; i < texto.length(); i++) {
            Component letra = Component.text(String.valueOf(texto.charAt(i)),
                    i == resalte ? FRENTE : FONDO);
            out = out.append(letra);
        }
        return out;
    }
}
