package dev.juanse.tiktokmobs.core.source;

import dev.juanse.tiktokmobs.core.event.EventType;
import dev.juanse.tiktokmobs.core.event.GiftEvent;
import dev.juanse.tiktokmobs.core.event.GiftInfo;
import dev.juanse.tiktokmobs.core.event.TikTokUser;

import java.util.function.Consumer;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Inyector de eventos falsos. No es un extra: es la pieza que hace el proyecto desarrollable.
 * Sin esto necesitas un live real y un viewer que te done cada vez que recompilas.
 *
 * <p>Expuesto en el juego como {@code /ttmobs test <regalo> [cantidad]}.
 */
public final class FakeGiftSource implements GiftSource {

    private final AtomicLong sequence = new AtomicLong();
    private volatile Consumer<GiftEvent> sink = e -> {
    };

    @Override
    public void start(Consumer<GiftEvent> sink) {
        this.sink = sink == null ? e -> {
        } : sink;
    }

    @Override
    public void close() {
        this.sink = e -> {
        };
    }

    @Override
    public String describe() {
        return "fake (inyector manual)";
    }

    public void emitGift(String nick, int giftId, String giftName, int coins, int count) {
        boolean streak = count > 1;
        sink.accept(GiftEvent.gift(nextId(), user(nick),
                new GiftInfo(giftId, giftName, coins, count, streak, true)));
    }

    /** Simula una racha completa: N eventos abiertos y uno de cierre. Solo el último debe contar. */
    public void emitStreak(String nick, int giftId, String giftName, int coins, int count) {
        String id = nextId();
        for (int i = 1; i < count; i++) {
            sink.accept(GiftEvent.gift(id, user(nick),
                    new GiftInfo(giftId, giftName, coins, i, true, false)));
        }
        sink.accept(GiftEvent.gift(id, user(nick),
                new GiftInfo(giftId, giftName, coins, count, true, true)));
    }

    public void emitLikes(String nick, int likes) {
        sink.accept(GiftEvent.like(nextId(), user(nick), likes));
    }

    public void emitSimple(EventType type, String nick) {
        sink.accept(GiftEvent.simple(nextId(), type, user(nick)));
    }

    private TikTokUser user(String nick) {
        String safeNick = (nick == null || nick.isBlank()) ? "tester" : nick;
        return new TikTokUser("fake-" + safeNick, safeNick, safeNick);
    }

    private String nextId() {
        return "fake-" + sequence.incrementAndGet();
    }
}
