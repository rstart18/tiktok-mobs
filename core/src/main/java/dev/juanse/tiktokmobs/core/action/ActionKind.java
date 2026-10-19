package dev.juanse.tiktokmobs.core.action;

import java.util.Locale;

/** Qué clase de cosa pasa en el mundo cuando llega un evento. */
public enum ActionKind {
    /** Spawnea N mobs alrededor del objetivo. */
    SPAWN,
    /** Igual que SPAWN pero en anillo y con más radio: pensado para hordas. */
    HORDE,
    /** Un mob jefe con vida aumentada, brillo y barra propia. */
    BOSS,
    /** Efecto de poción al jugador objetivo. */
    EFFECT,
    /** Evento de mundo con id propio (tnt_rain, night, thunder, random_loot...). */
    WORLD_EVENT,
    /** Dar un item al jugador. Para los regalos que ayudan en vez de atacar. */
    GIVE,
    /** No hacer nada (útil para desactivar un mapeo sin borrarlo). */
    NONE;

    public static ActionKind from(String raw) {
        if (raw == null || raw.isBlank()) return NONE;
        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "spawn", "mob" -> SPAWN;
            case "horde", "horda" -> HORDE;
            case "boss", "jefe" -> BOSS;
            case "effect", "efecto", "potion" -> EFFECT;
            case "world_event", "event", "evento", "world" -> WORLD_EVENT;
            case "give", "dar", "item" -> GIVE;
            default -> NONE;
        };
    }
}
