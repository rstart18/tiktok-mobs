package dev.juanse.tiktokmobs.core.event;

import java.util.Locale;

/** Tipos de evento de un live de TikTok que nos interesan. */
public enum EventType {
    GIFT,
    LIKE,
    FOLLOW,
    SHARE,
    SUBSCRIBE,
    COMMENT,
    UNKNOWN;

    /** Tolerante: acepta "gift", "GIFT", "social", "member", etc. */
    public static EventType from(String raw) {
        if (raw == null || raw.isBlank()) return UNKNOWN;
        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "gift", "gifts" -> GIFT;
            case "like", "likes" -> LIKE;
            case "follow", "social" -> FOLLOW;
            case "share" -> SHARE;
            case "subscribe", "sub", "subscription" -> SUBSCRIBE;
            case "chat", "comment", "message" -> COMMENT;
            default -> UNKNOWN;
        };
    }
}
