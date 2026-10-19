package dev.juanse.tiktokmobs.core.event;

/**
 * Quien manda el evento. OJO: todo esto es input no confiable.
 * Nunca lo uses crudo en un nameplate, en el chat ni dentro de un comando:
 * pásalo antes por {@link dev.juanse.tiktokmobs.core.util.NameSanitizer}.
 */
public record TikTokUser(String id, String uniqueId, String nickname) {

    public TikTokUser {
        id = id == null ? "" : id;
        uniqueId = uniqueId == null ? "" : uniqueId;
        nickname = nickname == null ? "" : nickname;
    }

    public static TikTokUser anonymous() {
        return new TikTokUser("", "", "");
    }

    /** El nombre que queremos mostrar, todavía SIN sanear. */
    public String rawLabel() {
        if (!nickname.isBlank()) return nickname;
        if (!uniqueId.isBlank()) return uniqueId;
        return "anon";
    }

    /** Clave estable para dedup y para el ranking de donantes. */
    public String stableKey() {
        if (!id.isBlank()) return id;
        if (!uniqueId.isBlank()) return uniqueId;
        return nickname.isBlank() ? "anon" : nickname;
    }
}
