package dev.juanse.tiktokmobs.core.boss;

import java.util.ArrayList;
import java.util.List;

/**
 * Un jefe, tal como lo describe el bloque {@code bosses:} del config.
 *
 * <p>Esto es dominio puro a propósito: la curva de vida y la elección de equipo son las dos
 * cosas que más se van a retocar en vivo ("se muere muy rápido", "aguanta demasiado"), y
 * poder probarlas sin levantar un servidor es la diferencia entre ajustarlas en un test de
 * dos segundos o a ciegas entre directos.
 *
 * <p>El plugin solo traduce: lee {@link #hpFor(int)} y {@link #gearFor(int)} y llama a Bukkit.
 */
public record BossDefinition(String id,
                             String baseEntity,
                             String modelId,
                             double hpBase,
                             double hpPerCoin,
                             double hpMax,
                             String titleOne,
                             String titleMany,
                             int suspenseTicks,
                             double minionsAt,
                             int minionsMin,
                             int minionsMax,
                             String minionEntity,
                             double rageAt,
                             List<Gear> gear) {

    /** Equipo a partir de N monedas. Se elige el tramo más alto que la donación alcance. */
    public record Gear(int minCoins,
                       String weapon,
                       String helmet,
                       String chest,
                       String legs,
                       String boots,
                       int enchantLevel) {

        public Gear {
            minCoins = Math.max(0, minCoins);
            weapon = trim(weapon);
            helmet = trim(helmet);
            chest = trim(chest);
            legs = trim(legs);
            boots = trim(boots);
            enchantLevel = Math.max(0, Math.min(enchantLevel, 10));
        }

        private static String trim(String v) {
            return v == null ? "" : v.trim();
        }
    }

    /** Techo duro de vida: por encima de esto la pelea deja de ser una pelea y es una espera. */
    public static final double HARD_MAX_HP = 2000.0;

    public BossDefinition {
        id = id == null ? "" : id.trim();
        baseEntity = baseEntity == null || baseEntity.isBlank()
                ? "WITHER_SKELETON" : baseEntity.trim();
        modelId = modelId == null ? "" : modelId.trim();

        hpBase = hpBase <= 0 ? 40.0 : hpBase;
        hpPerCoin = Math.max(0.0, hpPerCoin);
        hpMax = hpMax <= 0 ? 300.0 : Math.min(hpMax, HARD_MAX_HP);

        titleOne = titleOne == null || titleOne.isBlank() ? "{donador}, el Implacable" : titleOne;
        titleMany = titleMany == null || titleMany.isBlank() ? "{donadores}, los Implacables" : titleMany;

        // 3 segundos por defecto, y nunca más de 10: el suspenso que no acaba es lag, no suspenso.
        suspenseTicks = suspenseTicks <= 0 ? 60 : Math.min(suspenseTicks, 200);

        minionsAt = clampFraction(minionsAt, 0.5);
        minionsMin = Math.max(0, minionsMin);
        minionsMax = Math.max(minionsMin, minionsMax);
        minionEntity = minionEntity == null || minionEntity.isBlank()
                ? baseEntity : minionEntity.trim();
        rageAt = clampFraction(rageAt, 0.2);

        List<Gear> sorted = new ArrayList<>(gear == null ? List.of() : gear);
        sorted.sort((a, b) -> Integer.compare(b.minCoins(), a.minCoins()));
        gear = List.copyOf(sorted);
    }

    private static double clampFraction(double value, double fallback) {
        if (value <= 0.0 || value >= 1.0) return fallback;
        return value;
    }

    /** Vida con la que nace el jefe para una donación de {@code coins} monedas. */
    public double hpFor(int coins) {
        double hp = hpBase + hpPerCoin * Math.max(0, coins);
        return Math.max(1.0, Math.min(hp, hpMax));
    }

    /**
     * Vida máxima después de enfurecerlo con otra donación.
     *
     * <p>Se suma solo la parte proporcional, no la base: si no, dos regalos pequeños darían
     * más vida que uno grande y la gente aprendería a trollear el jefe a base de regalos de 1.
     */
    public double hpAfterEnrage(double currentMax, int coins) {
        double hp = currentMax + hpPerCoin * Math.max(0, coins);
        return Math.max(currentMax, Math.min(hp, hpMax));
    }

    /** true si hay que pedirle el modelo a BetterModel; false = entidad vanilla equipada. */
    public boolean hasModel() {
        return !modelId.isBlank();
    }

    /** El tramo de equipo que le toca a esta donación, o null si no hay ninguno configurado. */
    public Gear gearFor(int coins) {
        for (Gear tier : gear) {
            if (coins >= tier.minCoins()) return tier;
        }
        return null;
    }

    public static Builder builder(String id) {
        return new Builder(id);
    }

    public static final class Builder {
        private final String id;
        private String baseEntity = "WITHER_SKELETON";
        private String modelId = "";
        private double hpBase = 40.0;
        private double hpPerCoin = 0.5;
        private double hpMax = 300.0;
        private String titleOne = "";
        private String titleMany = "";
        private int suspenseTicks = 60;
        private double minionsAt = 0.5;
        private int minionsMin = 2;
        private int minionsMax = 3;
        private String minionEntity = "";
        private double rageAt = 0.2;
        private final List<Gear> gear = new ArrayList<>();

        private Builder(String id) {
            this.id = id;
        }

        public Builder baseEntity(String v) { this.baseEntity = v; return this; }
        public Builder modelId(String v) { this.modelId = v; return this; }
        public Builder hpBase(double v) { this.hpBase = v; return this; }
        public Builder hpPerCoin(double v) { this.hpPerCoin = v; return this; }
        public Builder hpMax(double v) { this.hpMax = v; return this; }
        public Builder titleOne(String v) { this.titleOne = v; return this; }
        public Builder titleMany(String v) { this.titleMany = v; return this; }
        public Builder suspenseTicks(int v) { this.suspenseTicks = v; return this; }
        public Builder minions(double at, int min, int max, String entity) {
            this.minionsAt = at;
            this.minionsMin = min;
            this.minionsMax = max;
            this.minionEntity = entity;
            return this;
        }
        public Builder rageAt(double v) { this.rageAt = v; return this; }
        public Builder gear(Gear tier) {
            if (tier != null) gear.add(tier);
            return this;
        }

        public BossDefinition build() {
            return new BossDefinition(id, baseEntity, modelId, hpBase, hpPerCoin, hpMax,
                    titleOne, titleMany, suspenseTicks,
                    minionsAt, minionsMin, minionsMax, minionEntity, rageAt, gear);
        }
    }
}
