package dev.juanse.tiktokmobs.core.action;

/**
 * La descripción declarativa de una acción, tal como sale del config.
 * Es un valor inmutable y sin dependencias: el core decide el QUÉ, el plugin hace el CÓMO.
 */
public record ActionSpec(ActionKind kind,
                         String mob,
                         int count,
                         int radius,
                         boolean nameplate,
                         double healthScale,
                         String worldEventId,
                         String effect,
                         int seconds,
                         String message,
                         String label,
                         int displayCoins,
                         double perCoin,
                         int maxCount,
                         String item,
                         String bossId,
                         int minCoins) {

    /** Techo duro: por encima de esto ningún evento puede spawnear, pase lo que pase. */
    public static final int HARD_MAX_COUNT = 40;

    public static final ActionSpec NONE =
            new ActionSpec(ActionKind.NONE, "", 1, 0, false, 1.0, "", "", 0, "", "", 0, 0.0, 0, "", "", 0);

    public ActionSpec {
        kind = kind == null ? ActionKind.NONE : kind;
        mob = mob == null ? "" : mob.trim();
        count = Math.max(1, count);
        radius = Math.max(0, radius);
        healthScale = healthScale <= 0 ? 1.0 : healthScale;
        worldEventId = worldEventId == null ? "" : worldEventId.trim();
        effect = effect == null ? "" : effect.trim();
        seconds = Math.max(0, seconds);
        message = message == null ? "" : message;
        label = label == null ? "" : label.trim();
        displayCoins = Math.max(0, displayCoins);
        item = item == null ? "" : item.trim();
        perCoin = Math.max(0.0, perCoin);
        maxCount = maxCount <= 0 ? HARD_MAX_COUNT : Math.min(maxCount, HARD_MAX_COUNT);
        bossId = bossId == null ? "" : bossId.trim();
        minCoins = Math.max(0, minCoins);
    }

    /** true si esta acción es un jefe del bloque {@code bosses:} y no un BOSS simple de siempre. */
    public boolean isSuperBoss() {
        return kind == ActionKind.BOSS && !bossId.isBlank();
    }

    /**
     * true si la donación no llega al mínimo que pide este mapeo.
     *
     * <p>Existe para el jefe: el regalo que lo invoca cuesta 500 monedas, pero el mismo regalo
     * puede llegar con menos (combos a medias, regalos cambiados de precio). En ese caso no se
     * invoca el jefe, pero tampoco se deja al donante sin nada: la tabla cae al tramo normal.
     */
    public boolean belowMinimum(int coins) {
        return minCoins > 0 && coins < minCoins;
    }

    /**
     * Cuántos mobs salen de verdad para una donación de {@code coins} monedas.
     *
     * <p>Con {@code perCoin} a 0 se comporta como antes: {@code count} fijo. Con perCoin > 0
     * el spawn es <b>proporcional</b>, que es lo que espera el viewer: si mando 15 regalos
     * quiero ver 15 bichos, no los 5 de un tramo.
     *
     * <p>{@code count} actúa de suelo (una donación de 1 moneda sigue haciendo algo visible)
     * y {@code maxCount} de techo (nadie tumba el servidor con un regalo de 30.000 monedas).
     */
    public int effectiveCount(int coins) {
        if (perCoin <= 0.0) return Math.min(count, maxCount);

        int scaled = (int) Math.round(coins * perCoin);
        return Math.max(count, Math.min(scaled, maxCount));
    }

    /**
     * Texto corto de lo que hace esta acción, para el panel de catálogo que ven los viewers.
     * {@code label} del config manda; si no hay, se describe sola.
     */
    public String describe() {
        if (!label.isBlank()) return label;
        return switch (kind) {
            case SPAWN, HORDE -> perCoin > 0
                    ? (perCoin == 1.0
                    ? "1 " + mob.toLowerCase(java.util.Locale.ROOT) + " por moneda"
                    : mob.toLowerCase(java.util.Locale.ROOT) + " segun monedas")
                    : count + "x " + mob.toLowerCase(java.util.Locale.ROOT);
            case BOSS -> isSuperBoss() ? "UN JEFE" : "jefe " + mob.toLowerCase(java.util.Locale.ROOT);
            case EFFECT -> effect.toLowerCase(java.util.Locale.ROOT) + " " + seconds + "s";
            case WORLD_EVENT -> worldEventId.replace('_', ' ');
            case GIVE -> count + "x " + item.toLowerCase(java.util.Locale.ROOT).replace('_', ' ');
            case NONE -> "";
        };
    }

    public static Builder of(ActionKind kind) {
        return new Builder(kind);
    }

    public static final class Builder {
        private final ActionKind kind;
        private String mob = "";
        private int count = 1;
        private int radius = 4;
        private boolean nameplate = true;
        private double healthScale = 1.0;
        private String worldEventId = "";
        private String effect = "";
        private int seconds = 10;
        private String message = "";
        private String label = "";
        private int displayCoins = 0;
        private double perCoin = 0.0;
        private int maxCount = HARD_MAX_COUNT;
        private String item = "";
        private String bossId = "";
        private int minCoins = 0;

        private Builder(ActionKind kind) {
            this.kind = kind;
        }

        public Builder mob(String v) { this.mob = v; return this; }
        public Builder count(int v) { this.count = v; return this; }
        public Builder radius(int v) { this.radius = v; return this; }
        public Builder nameplate(boolean v) { this.nameplate = v; return this; }
        public Builder healthScale(double v) { this.healthScale = v; return this; }
        public Builder worldEvent(String v) { this.worldEventId = v; return this; }
        public Builder effect(String v) { this.effect = v; return this; }
        public Builder seconds(int v) { this.seconds = v; return this; }
        public Builder message(String v) { this.message = v; return this; }
        public Builder label(String v) { this.label = v; return this; }
        public Builder displayCoins(int v) { this.displayCoins = v; return this; }
        public Builder perCoin(double v) { this.perCoin = v; return this; }
        public Builder maxCount(int v) { this.maxCount = v; return this; }
        public Builder item(String v) { this.item = v; return this; }
        public Builder bossId(String v) { this.bossId = v; return this; }
        public Builder minCoins(int v) { this.minCoins = v; return this; }

        public ActionSpec build() {
            return new ActionSpec(kind, mob, count, radius, nameplate, healthScale,
                    worldEventId, effect, seconds, message, label, displayCoins,
                    perCoin, maxCount, item, bossId, minCoins);
        }
    }
}
