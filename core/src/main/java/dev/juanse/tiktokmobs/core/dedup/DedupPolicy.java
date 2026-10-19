package dev.juanse.tiktokmobs.core.dedup;

import dev.juanse.tiktokmobs.core.event.EventType;
import dev.juanse.tiktokmobs.core.event.GiftEvent;
import dev.juanse.tiktokmobs.core.event.GiftInfo;

import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Dos protecciones en una, y las dos son obligatorias:
 *
 * <ol>
 *   <li><b>Gate de rachas.</b> Un regalo streakable emite evento en cada repetición. Solo
 *       procesamos el que cierra la racha ({@code repeatEnd}), que además trae el count final.</li>
 *   <li><b>Dedup con TTL.</b> El gateway de TikTok reenvía mensajes tras un reconnect. Sin esto,
 *       una caída de red de 3 segundos te duplica todos los regalos de la ventana.</li>
 * </ol>
 *
 * Thread-safe a propósito: {@link #accept(GiftEvent)} se llama desde el hilo del WebSocket.
 */
public final class DedupPolicy {

    private final Map<String, Long> seen = new ConcurrentHashMap<>();
    private final long ttlMs;
    private final LongSupplier clock;

    public DedupPolicy(long ttlMs) {
        this(ttlMs, System::currentTimeMillis);
    }

    /** El constructor con reloj inyectado existe para poder testear el TTL sin dormir. */
    public DedupPolicy(long ttlMs, LongSupplier clock) {
        this.ttlMs = Math.max(1_000L, ttlMs);
        this.clock = clock;
    }

    public boolean accept(GiftEvent event) {
        return accept(event, "main");
    }

    /** @return true si el evento es nuevo y debe procesarse. */
    public boolean accept(GiftEvent event, String streamerId) {
        if (event == null) return false;

        if (event.type() == EventType.GIFT) {
            GiftInfo gift = event.gift();
            if (gift == null) return false;
            if (gift.isOpenStreak()) return false;
        }

        long now = clock.getAsLong();
        purge(now);
        return seen.putIfAbsent(key(event, streamerId), now) == null;
    }

    private String key(GiftEvent e, String streamerId) {
        int giftId = e.gift() == null ? -1 : e.gift().id();
        // El streamer va en la clave: dos lives distintos pueden repetir msgId sin relacion.
        return streamerId + "|" + e.type() + "|" + e.user().stableKey() + "|" + giftId + "|" + e.id();
    }

    private void purge(long now) {
        if (seen.size() < 256) return;
        for (Iterator<Map.Entry<String, Long>> it = seen.entrySet().iterator(); it.hasNext(); ) {
            if (now - it.next().getValue() > ttlMs) it.remove();
        }
    }

    public int trackedKeys() {
        return seen.size();
    }

    public void clear() {
        seen.clear();
    }
}
