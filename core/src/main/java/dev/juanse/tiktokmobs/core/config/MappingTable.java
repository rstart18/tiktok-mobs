package dev.juanse.tiktokmobs.core.config;

import dev.juanse.tiktokmobs.core.action.ActionSpec;
import dev.juanse.tiktokmobs.core.event.EventType;
import dev.juanse.tiktokmobs.core.event.GiftInfo;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * El catálogo de regalos de TikTok cambia cada temporada: salen regalos nuevos, retiran otros.
 * Por eso nada se hardcodea y, sobre todo, por eso existe la tabla de tiers:
 *
 * <p>Resolución en cascada: id exacto → nombre exacto → <b>tier por monedas</b>. Así un regalo
 * que no conoces igual produce algo proporcional a su valor en vez de quedarse en silencio,
 * que es el fallo más común y el más decepcionante en vivo.
 */
public final class MappingTable {

    /** Un tramo de la tabla: "hasta N monedas, haz esto". */
    public record Tier(int maxCoins, ActionSpec spec) {
    }

    /** Una fila del panel que ven los viewers: regalo, lo que cuesta y lo que provoca. */
    public record CatalogEntry(String label, int coins, ActionSpec spec) {
    }

    private final Map<Integer, ActionSpec> byGiftId;
    private final Map<String, ActionSpec> byGiftName;
    private final Map<EventType, ActionSpec> byEventType;
    private final List<Tier> tiers;
    private final List<CatalogEntry> catalog;
    private final int likeEvery;
    private final int donationGoalCoins;
    private final ActionSpec goalReward;

    private MappingTable(Builder b) {
        this.byGiftId = Map.copyOf(b.byGiftId);
        this.byGiftName = Map.copyOf(b.byGiftName);
        this.catalog = List.copyOf(b.catalog);
        this.byEventType = b.byEventType.isEmpty()
                ? Collections.emptyMap()
                : Collections.unmodifiableMap(new EnumMap<>(b.byEventType));
        List<Tier> sorted = new ArrayList<>(b.tiers);
        sorted.sort((x, y) -> Integer.compare(x.maxCoins(), y.maxCoins()));
        this.tiers = List.copyOf(sorted);
        this.likeEvery = Math.max(1, b.likeEvery);
        this.donationGoalCoins = Math.max(0, b.donationGoalCoins);
        this.goalReward = b.goalReward == null ? ActionSpec.NONE : b.goalReward;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static MappingTable empty() {
        return builder().build();
    }

    /** Resolución en cascada: id → nombre → tier por monedas. */
    public Optional<ActionSpec> forGift(GiftInfo gift) {
        if (gift == null) return Optional.empty();

        ActionSpec exact = byGiftId.get(gift.id());
        if (exact == null && !gift.name().isBlank()) {
            exact = byGiftName.get(gift.name().toLowerCase(Locale.ROOT));
        }

        // Mapeo con 'min-coins' que la donación no alcanza: no se ejecuta, pero tampoco se
        // devuelve vacío. Se deja caer al tramo por monedas para que el que donó vea algo.
        if (exact != null && exact.belowMinimum(gift.totalCoins())) {
            exact = null;
        }
        if (exact != null) return Optional.of(exact);

        return tierFor(gift.totalCoins());
    }

    /**
     * true si este regalo tiene mapeo propio (por id o por nombre), false si va a caer
     * en los tramos por monedas. Lo usa el log para avisar de qué regalos aún no mapeaste.
     */
    public boolean hasExplicit(GiftInfo gift) {
        if (gift == null) return false;
        if (byGiftId.containsKey(gift.id())) return true;
        return !gift.name().isBlank()
                && byGiftName.containsKey(gift.name().toLowerCase(Locale.ROOT));
    }

    public Optional<ActionSpec> tierFor(int coins) {
        for (Tier tier : tiers) {
            if (coins <= tier.maxCoins()) return Optional.of(tier.spec());
        }
        // Por encima del último tramo: aplica el más alto definido.
        return tiers.isEmpty() ? Optional.empty() : Optional.of(tiers.get(tiers.size() - 1).spec());
    }

    public Optional<ActionSpec> forEventType(EventType type) {
        return Optional.ofNullable(byEventType.get(type));
    }

    public int likeEvery() {
        return likeEvery;
    }

    public int donationGoalCoins() {
        return donationGoalCoins;
    }

    public ActionSpec goalReward() {
        return goalReward;
    }

    public boolean goalEnabled() {
        return donationGoalCoins > 0 && goalReward.kind() != dev.juanse.tiktokmobs.core.action.ActionKind.NONE;
    }

    public int mappedGifts() {
        return byGiftId.size() + byGiftName.size();
    }

    public int tierCount() {
        return tiers.size();
    }

    /** Los regalos con nombre, en el orden del config: eso es lo que ve el viewer. */
    public List<CatalogEntry> catalog() {
        return catalog;
    }

    public List<Tier> tiers() {
        return tiers;
    }

    /** Los mapeos por id, tal como quedaron tras mezclar config y panel. Lo usa el panel web. */
    public Map<Integer, ActionSpec> byGiftId() {
        return byGiftId;
    }

    public static final class Builder {
        private final Map<Integer, ActionSpec> byGiftId = new HashMap<>();
        private final Map<String, ActionSpec> byGiftName = new HashMap<>();
        // Lista aparte porque el mapa va en minúsculas y sin orden, y el panel necesita
        // el nombre tal cual lo escribió el usuario y en el orden del config.
        private final List<CatalogEntry> catalog = new ArrayList<>();
        private final Map<EventType, ActionSpec> byEventType = new EnumMap<>(EventType.class);
        private final List<Tier> tiers = new ArrayList<>();
        private int likeEvery = 100;
        private int donationGoalCoins = 0;
        private ActionSpec goalReward = ActionSpec.NONE;

        public Builder gift(int giftId, ActionSpec spec) {
            if (giftId > 0 && spec != null) byGiftId.put(giftId, spec);
            return this;
        }

        public Builder gift(String giftName, ActionSpec spec) {
            if (giftName != null && !giftName.isBlank() && spec != null) {
                byGiftName.put(giftName.trim().toLowerCase(Locale.ROOT), spec);

                // El mismo regalo se mapea varias veces (en espanol y en ingles) porque
                // TikTok traduce el nombre. Para el mapeo eso hace falta; para el panel que
                // ven los viewers no: ahi saldria "Rosa" y "Rose" como dos cosas distintas.
                boolean yaEsta = catalog.stream().anyMatch(e -> e.spec().equals(spec));
                if (!yaEsta) {
                    catalog.add(new CatalogEntry(giftName.trim(), spec.displayCoins(), spec));
                }
            }
            return this;
        }

        public Builder tier(int maxCoins, ActionSpec spec) {
            if (spec != null) tiers.add(new Tier(Math.max(0, maxCoins), spec));
            return this;
        }

        public Builder on(EventType type, ActionSpec spec) {
            if (type != null && spec != null) byEventType.put(type, spec);
            return this;
        }

        public Builder likeEvery(int every) {
            this.likeEvery = every;
            return this;
        }

        public Builder goal(int coins, ActionSpec reward) {
            this.donationGoalCoins = coins;
            this.goalReward = reward;
            return this;
        }

        public MappingTable build() {
            return new MappingTable(this);
        }
    }
}
