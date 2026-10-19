package dev.juanse.tiktokmobs.core;

import dev.juanse.tiktokmobs.core.event.GiftInfo;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Catálogo de lo que TikTok ha mandado de verdad en esta sesión.
 *
 * <p>Existe porque los nombres de regalo llegan <b>traducidos al idioma de la cuenta</b>:
 * la misma rosa es "Rose", "Rosa" o "玫瑰" según quién transmita. Mapear por nombre es, por
 * tanto, una trampa. El id en cambio es el mismo en todos los idiomas.
 *
 * <p>Así que el plugin apunta cada regalo que ve con su id, y {@code /ttmobs gifts} te lo
 * enseña listo para copiar al config. No hay que adivinar ids ni buscarlos en ninguna web.
 */
public final class GiftRegistry {

    /** Un regalo observado: lo que TikTok mandó, no lo que supusimos. */
    public record Seen(int id, String name, int coins, int times, boolean explicitlyMapped) {
    }

    private final Map<Integer, Seen> byId = new ConcurrentHashMap<>();

    /**
     * @return true la primera vez que se ve este regalo (para loguearlo una sola vez
     *         y no inundar la consola en mitad de un live).
     */
    public boolean record(GiftInfo gift, boolean explicitlyMapped) {
        if (gift == null) return false;

        int key = gift.id();
        boolean[] isNew = {false};

        byId.compute(key, (id, previous) -> {
            if (previous == null) {
                isNew[0] = true;
                return new Seen(id, gift.name(), gift.coins(), 1, explicitlyMapped);
            }
            // El nombre puede llegar vacío en algún evento: nos quedamos con el que sí tenga.
            String name = previous.name().isBlank() ? gift.name() : previous.name();
            int coins = gift.coins() > 0 ? gift.coins() : previous.coins();
            return new Seen(id, name, coins, previous.times() + 1, explicitlyMapped);
        });

        return isNew[0];
    }

    /** Lo visto, de más caro a más barato. */
    public List<Seen> seen() {
        List<Seen> all = new ArrayList<>(byId.values());
        all.sort(Comparator.comparingInt(Seen::coins).reversed()
                .thenComparing(Seen::name));
        return all;
    }

    public int size() {
        return byId.size();
    }

    public void clear() {
        byId.clear();
    }

    /** Bloque de config listo para pegar, con los regalos que todavía no tienen mapeo propio. */
    public String asConfigSnippet() {
        List<Seen> pending = seen().stream().filter(s -> !s.explicitlyMapped()).toList();
        if (pending.isEmpty()) return "";

        StringBuilder out = new StringBuilder("gift-ids:\n");
        for (Seen gift : pending) {
            out.append("  ").append(gift.id()).append(":   # ")
                    .append(gift.name()).append(", ").append(gift.coins()).append(" monedas\n")
                    .append("    action: spawn\n")
                    .append("    mob: HUSK\n")
                    .append("    count: 3\n")
                    .append("    nameplate: true\n");
        }
        return out.toString();
    }
}
