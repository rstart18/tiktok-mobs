package dev.juanse.tiktokmobs.plugin.ui;

import dev.juanse.tiktokmobs.core.event.GiftEvent;
import dev.juanse.tiktokmobs.core.util.NameSanitizer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Set;

/**
 * Los comentarios del live, dentro del juego.
 *
 * <p>Por qué vale la pena: el que escribe en el chat de TikTok no ve nunca su mensaje en la
 * transmisión, porque tu pantalla es Minecraft. En cuanto se ve, escribe más. Es la forma más
 * barata que hay de que participe la gente que no dona.
 *
 * <p>Dos frenos que no son opcionales:
 * <ul>
 *   <li><b>Ritmo.</b> Un live con público mete cientos de comentarios por minuto. Sin tope, el
 *       chat de Minecraft se convierte en una cascada y no ves ni tu propia muerte.</li>
 *   <li><b>Repetidos.</b> Tras una reconexión, TikTok reenvía mensajes ya vistos. Sin esto
 *       salen dos y tres veces y parece que el plugin está roto.</li>
 * </ul>
 *
 * <p>El texto va en {@link Component#text}, nunca por el serializador de códigos legacy: así un
 * viewer no puede falsificar una línea de chat ni colorear la suya escribiendo &amp;c.
 */
public final class ChatRelay {

    public static final ChatRelay DISABLED = new ChatRelay(false, 0, 0);

    /** Cuántos ids recordamos para detectar reenvíos. Pequeño: solo interesa lo reciente. */
    private static final int MEMORY = 200;

    private final boolean enabled;
    private final int maxPerSecond;
    private final int minCoinsToShow;

    private final Deque<String> recientes = new ArrayDeque<>();
    private final Set<String> vistos = new HashSet<>();

    private long ventanaMs;
    private int enEstaVentana;

    /**
     * @param maxPerSecond   tope de comentarios mostrados por segundo; el resto se descarta
     * @param minCoinsToShow si es > 0, solo se muestran los comentarios de quien haya donado
     *                       al menos esas monedas (0 = todos)
     */
    public ChatRelay(boolean enabled, int maxPerSecond, int minCoinsToShow) {
        this.enabled = enabled;
        this.maxPerSecond = Math.max(1, maxPerSecond);
        this.minCoinsToShow = Math.max(0, minCoinsToShow);
    }

    /** @return true si el comentario llegó a pantalla. */
    public boolean show(GiftEvent event, int monedasDelAutor) {
        if (!enabled || event == null) return false;

        String texto = NameSanitizer.cleanMessage(event.comment());
        if (texto.isEmpty()) return false;

        if (minCoinsToShow > 0 && monedasDelAutor < minCoinsToShow) return false;
        if (yaVisto(event.id())) return false;
        if (!cabeEnLaVentana()) return false;

        String autor = NameSanitizer.clean(event.user().rawLabel());

        Component linea = Component.text("[TikTok] ", NamedTextColor.DARK_AQUA)
                .append(Component.text(autor, NamedTextColor.AQUA))
                .append(Component.text(": ", NamedTextColor.GRAY))
                .append(Component.text(texto, NamedTextColor.WHITE));

        for (Player player : Bukkit.getOnlinePlayers()) {
            player.sendMessage(linea);
        }
        return true;
    }

    private boolean yaVisto(String id) {
        if (id == null || id.isBlank()) return false;
        if (!vistos.add(id)) return true;

        recientes.addLast(id);
        while (recientes.size() > MEMORY) {
            String viejo = recientes.pollFirst();
            if (viejo != null) vistos.remove(viejo);
        }
        return false;
    }

    /** Cubo por segundo: lo que pase del tope se tira, no se encola. Un comentario que sale
     *  medio minuto tarde ya no es conversación, es ruido. */
    private boolean cabeEnLaVentana() {
        long ahora = System.currentTimeMillis() / 1000L;
        if (ahora != ventanaMs) {
            ventanaMs = ahora;
            enEstaVentana = 0;
        }
        if (enEstaVentana >= maxPerSecond) return false;
        enEstaVentana++;
        return true;
    }

    public boolean enabled() {
        return enabled;
    }
}
