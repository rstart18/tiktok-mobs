package dev.juanse.tiktokmobs.plugin.source;

import dev.juanse.tiktokmobs.core.event.GiftEvent;
import dev.juanse.tiktokmobs.core.source.GiftSource;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.logging.Logger;

/**
 * Cliente del WebSocket local de TikFinity ({@code ws://127.0.0.1:21213/}).
 *
 * <p>Usa {@code java.net.http.WebSocket} del JDK a propósito: cero dependencias, así que no hay
 * nada que relocalizar y es imposible chocar con el Netty del servidor, que es el error de
 * empaquetado más común en plugins que hablan con TikTok.
 *
 * <p>Dos cosas que casi todo el mundo se salta:
 * <ul>
 *   <li><b>Reensamblado de frames.</b> {@code onText} puede entregar un mensaje partido en
 *       trozos; hay que acumular hasta que {@code last == true} o el JSON llega cortado.</li>
 *   <li><b>Backoff exponencial.</b> Un live se cae, TikFinity se reinicia, el PC se duerme.
 *       Reintentar cada 100 ms contra un puerto cerrado solo te llena el log.</li>
 * </ul>
 *
 * <p>Todo esto corre fuera del hilo del servidor, por eso el sink solo encola.
 */
public final class TikFinityWebSocketSource implements GiftSource {

    private final URI uri;
    private final Logger log;
    private final int maxBackoffSeconds;
    private final Consumer<String> rawListener;

    private final ScheduledExecutorService scheduler;
    private final HttpClient http;
    private final AtomicInteger attempt = new AtomicInteger();
    private final StringBuilder buffer = new StringBuilder();

    private volatile Consumer<GiftEvent> sink = event -> {
    };
    private volatile WebSocket socket;
    private volatile boolean closed;
    private volatile boolean open;

    public TikFinityWebSocketSource(String url, int maxBackoffSeconds, Logger log,
                                    Consumer<String> rawListener) {
        this.uri = URI.create(url);
        this.maxBackoffSeconds = Math.max(5, maxBackoffSeconds);
        this.log = log;
        this.rawListener = rawListener == null ? raw -> {
        } : rawListener;

        ThreadFactory daemons = runnable -> {
            Thread thread = new Thread(runnable, "ttmobs-ws");
            thread.setDaemon(true);
            return thread;
        };
        this.scheduler = Executors.newSingleThreadScheduledExecutor(daemons);
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .executor(Executors.newFixedThreadPool(2, daemons))
                .build();
    }

    @Override
    public void start(Consumer<GiftEvent> sink) {
        this.sink = sink == null ? event -> {
        } : sink;
        this.closed = false;
        connect();
    }

    private void connect() {
        if (closed) return;

        http.newWebSocketBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .buildAsync(uri, new Handler())
                .whenComplete((webSocket, error) -> {
                    if (error != null) {
                        open = false;
                        log.warning("[tikfinity] no pude conectar a " + uri
                                + " (¿está abierta la app de TikFinity?): " + rootMessage(error));
                        scheduleReconnect();
                        return;
                    }
                    socket = webSocket;
                    open = true;
                    attempt.set(0);
                    log.info("[tikfinity] conectado a " + uri);
                });
    }

    private void scheduleReconnect() {
        if (closed) return;

        int tries = Math.min(attempt.incrementAndGet(), 10);
        long delay = Math.min(maxBackoffSeconds, (long) Math.pow(2, Math.min(tries, 6)));
        log.info("[tikfinity] reintento en " + delay + "s (intento " + tries + ")");
        scheduler.schedule(this::connect, delay, TimeUnit.SECONDS);
    }

    @Override
    public void close() {
        closed = true;
        open = false;
        WebSocket current = socket;
        socket = null;
        if (current != null) {
            try {
                current.sendClose(WebSocket.NORMAL_CLOSURE, "plugin deshabilitado");
            } catch (RuntimeException ignored) {
                current.abort();
            }
        }
        scheduler.shutdownNow();
    }

    @Override
    public String describe() {
        return "tikfinity " + uri + (open ? " (conectado)" : " (desconectado)");
    }

    @Override
    public boolean connected() {
        return open;
    }

    private static String rootMessage(Throwable error) {
        Throwable cause = error;
        while (cause.getCause() != null) cause = cause.getCause();
        return cause.getClass().getSimpleName() + ": " + cause.getMessage();
    }

    private final class Handler implements WebSocket.Listener {

        @Override
        public void onOpen(WebSocket webSocket) {
            // Sin esto solo llega UN mensaje: el contador de recepción por defecto es 1.
            webSocket.request(Long.MAX_VALUE);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            buffer.append(data);
            if (!last) return null;

            String message = buffer.toString();
            buffer.setLength(0);

            try {
                rawListener.accept(message);
                TikFinityParser.parse(message).ifPresent(event -> sink.accept(event));
            } catch (RuntimeException ex) {
                log.warning("[tikfinity] mensaje ilegible descartado: " + ex);
            }
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            open = false;
            log.info("[tikfinity] conexión cerrada (" + statusCode + " " + reason + ")");
            scheduleReconnect();
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            open = false;
            log.warning("[tikfinity] error de socket: " + rootMessage(error));
            scheduleReconnect();
        }
    }
}
