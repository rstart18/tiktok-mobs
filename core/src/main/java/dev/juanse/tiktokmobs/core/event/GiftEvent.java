package dev.juanse.tiktokmobs.core.event;

import java.util.Optional;

/**
 * Evento normalizado. Toda fuente (TikFinity, tik.tools, TikTokLiveJava, el inyector falso)
 * produce esto y nada más. El resto del sistema no sabe de dónde vino.
 */
public record GiftEvent(String id,
                        long timestampMs,
                        EventType type,
                        TikTokUser user,
                        GiftInfo gift,
                        int likeCount,
                        String comment) {

    public GiftEvent {
        id = (id == null || id.isBlank()) ? "evt-" + timestampMs + "-" + System.nanoTime() : id;
        type = type == null ? EventType.UNKNOWN : type;
        user = user == null ? TikTokUser.anonymous() : user;
        comment = comment == null ? "" : comment;
        likeCount = Math.max(0, likeCount);
    }

    public static GiftEvent gift(String id, TikTokUser user, GiftInfo gift) {
        return new GiftEvent(id, System.currentTimeMillis(), EventType.GIFT, user, gift, 0, "");
    }

    public static GiftEvent like(String id, TikTokUser user, int likes) {
        return new GiftEvent(id, System.currentTimeMillis(), EventType.LIKE, user, null, likes, "");
    }

    public static GiftEvent simple(String id, EventType type, TikTokUser user) {
        return new GiftEvent(id, System.currentTimeMillis(), type, user, null, 0, "");
    }

    public Optional<GiftInfo> giftInfo() {
        return Optional.ofNullable(gift);
    }

    public int coins() {
        return gift == null ? 0 : gift.totalCoins();
    }
}
