package dev.juanse.tiktokmobs.core.source;

import dev.juanse.tiktokmobs.core.event.GiftEvent;

import java.util.function.Consumer;

/**
 * El puerto de entrada. Todo lo que sepa hablar con TikTok implementa esto:
 * TikFinity por WebSocket local, tik.tools, TikTokLiveJava embebido, o el inyector falso.
 *
 * <p><b>Contrato crítico:</b> el {@code sink} se invoca desde un hilo ajeno al del servidor.
 * Quien lo consuma tiene prohibido tocar la API de Bukkit ahí: solo encolar.
 */
public interface GiftSource extends AutoCloseable {

    void start(Consumer<GiftEvent> sink);

    @Override
    void close();

    /** Texto corto para /ttmobs status. */
    String describe();

    default boolean connected() {
        return true;
    }
}
