package dev.juanse.tiktokmobs.plugin.overlay;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.BindException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import java.util.logging.Logger;

/**
 * Servidor del overlay para OBS: sirve la página y empuja los eventos por SSE.
 *
 * <p>Usa {@code com.sun.net.httpserver} del JDK: cero dependencias nuevas, nada que relocalizar.
 *
 * <p><b>Lo importante es el modelo de hilos.</b> Los eventos nacen en el hilo principal del
 * servidor (ahí es donde se ejecutan las acciones), pero escribir en un socket puede bloquear:
 * si el navegador de OBS se cuelga o la máquina va justa, un write lento desde el hilo
 * principal se te come el TPS en directo. Así que {@link #push(String)} solo encola en una cola
 * acotada y un hilo demonio aparte hace los writes. Si un cliente no lee, se descarta el evento
 * o se cierra ese cliente: nunca se paga con TPS.
 */
public final class OverlayServer {

    private static final int EVENT_QUEUE_CAPACITY = 256;
    private static final long HEARTBEAT_SECONDS = 15;

    private final Logger log;
    private final String bindHost;
    private final int port;
    private final String token;
    private final Supplier<String> stateSupplier;
    private final Supplier<String> catalogSupplier;

    /**
     * El panel de configuración, si está cableado.
     *
     * <p>Va con su <b>propio token</b> y no con el del overlay: el del overlay se pega en una
     * browser source de OBS y acaba en cualquier captura de pantalla del directo, mientras que
     * este abre la puerta a reconfigurar el servidor. No pueden ser el mismo.
     */
    private volatile PanelHooks panel;

    /** Lo que el panel necesita del plugin, sin que el servidor HTTP sepa de Bukkit. */
    public interface PanelHooks {
        /** Token obligatorio. Sin él, el panel no se sirve. */
        String token();

        /** JSON con los regalos vistos, los mapeos actuales y las opciones. */
        String data();

        /** @return mensaje para enseñar en el panel. Corre en el hilo principal. */
        String save(String json);

        /** Dispara una donación de prueba por id. */
        String test(int giftId, int coins);

        /** El png local de ese regalo, o null si no hay ninguno puesto. */
        byte[] icono(int giftId);
    }

    public void setPanel(PanelHooks panel) {
        this.panel = panel;
    }
    /** (streamerId, json crudo de TikFinity) -> lo mete en la cola del plugin. */
    private volatile java.util.function.BiConsumer<String, String> ingest = (a, b) -> { };

    private final CopyOnWriteArrayList<Client> clients = new CopyOnWriteArrayList<>();
    private final BlockingQueue<String> outbox = new ArrayBlockingQueue<>(EVENT_QUEUE_CAPACITY);
    private final AtomicLong dropped = new AtomicLong();
    private final AtomicLong ingested = new AtomicLong();

    private HttpServer server;
    private Thread writer;
    private volatile boolean running;
    private final Map<String, String> cachedPages = new ConcurrentHashMap<>();

    public OverlayServer(Logger log, String bindHost, int port, String token,
                         Supplier<String> stateSupplier, Supplier<String> catalogSupplier) {
        this.catalogSupplier = catalogSupplier;
        this.log = log;
        this.bindHost = (bindHost == null || bindHost.isBlank()) ? "127.0.0.1" : bindHost.trim();
        this.port = port;
        this.token = token == null ? "" : token.trim();
        this.stateSupplier = stateSupplier;
    }

    public boolean start() {
        try {
            server = HttpServer.create(new InetSocketAddress(bindHost, port), 0);
        } catch (BindException ex) {
            log.warning("[overlay] el puerto " + port + " ya está ocupado. "
                    + "Cambia 'overlay.port' en config.yml y usa /ttmobs reload.");
            return false;
        } catch (IOException ex) {
            log.warning("[overlay] no pude abrir " + bindHost + ":" + port + " -> " + ex.getMessage());
            return false;
        }

        ThreadFactory daemons = runnable -> {
            Thread thread = new Thread(runnable, "ttmobs-overlay");
            thread.setDaemon(true);
            return thread;
        };

        server.createContext("/", this::handlePage);
        server.createContext("/catalogo", this::handleCatalogPage);
        server.createContext("/catalog.json", this::handleCatalogJson);
        server.createContext("/ingest", this::handleIngest);
        server.createContext("/events", this::handleEvents);
        server.createContext("/health", this::handleHealth);
        server.createContext("/panel", this::handlePanel);
        server.setExecutor(Executors.newFixedThreadPool(3, daemons));
        server.start();

        running = true;
        writer = daemons.newThread(this::writeLoop);
        writer.start();

        if (!"127.0.0.1".equals(bindHost) && !"localhost".equals(bindHost) && token.isEmpty()) {
            log.warning("[overlay] estás escuchando en " + bindHost + " SIN token. "
                    + "Cualquiera en tu red puede abrir el overlay: pon 'overlay.token' en el config.");
        }
        log.info("[overlay] escuchando en http://" + bindHost + ":" + port + url("/"));
        return true;
    }

    public void stop() {
        running = false;
        if (writer != null) {
            writer.interrupt();
            writer = null;
        }
        for (Client client : clients) {
            client.close();
        }
        clients.clear();
        if (server != null) {
            server.stop(0);
            server = null;
        }
    }

    /**
     * Llamado desde el hilo principal. No bloquea nunca: encola y vuelve.
     * Si la cola está llena (OBS cerrado, cliente colgado) se descarta el evento.
     */
    public void push(String json) {
        if (!running || json == null) return;
        if (!outbox.offer(json)) {
            dropped.incrementAndGet();
        }
    }

    public void setIngest(java.util.function.BiConsumer<String, String> ingest) {
        this.ingest = ingest == null ? (a, b) -> { } : ingest;
    }

    public long ingested() {
        return ingested.get();
    }

    public int clientCount() {
        return clients.size();
    }

    /** El puerto real. Con {@code port: 0} el SO asigna uno libre (lo usan los tests). */
    public int boundPort() {
        return server == null ? -1 : server.getAddress().getPort();
    }

    public long droppedEvents() {
        return dropped.get();
    }

    public String describe() {
        if (!running) return "apagado";
        return "http://" + bindHost + ":" + port + url("/")
                + " (" + clients.size() + " cliente(s))";
    }

    private String url(String path) {
        return token.isEmpty() ? path : path + "?token=" + token;
    }

    // ------------------------------------------------------------------ handlers

    private void handlePage(HttpExchange exchange) throws IOException {
        if (!authorized(exchange)) return;

        String path = exchange.getRequestURI().getPath();
        if (!path.equals("/") && !path.equals("/index.html")) {
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
            return;
        }

        sendHtml(exchange, page("/overlay/index.html"));
    }

    private void handleCatalogPage(HttpExchange exchange) throws IOException {
        if (!authorized(exchange)) return;
        sendHtml(exchange, page("/overlay/catalogo.html"));
    }

    private void handleCatalogJson(HttpExchange exchange) throws IOException {
        if (!authorized(exchange)) return;

        String json = catalogSupplier == null ? "{}" : catalogSupplier.get();
        byte[] body = (json == null ? "{}" : json).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(200, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    private void sendHtml(HttpExchange exchange, String html) throws IOException {
        byte[] body = html.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(200, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    /**
     * Entrada de eventos desde el relay que corre en el PC de otro streamer.
     *
     * <p>Va al reves que el WebSocket: aqui el otro PC EMPUJA hacia nosotros, asi que a el no
     * le hace falta abrir ningun puerto ni montar un tunel. Solo salida.
     */
    private void handleIngest(HttpExchange exchange) throws IOException {
        if (!authorized(exchange)) return;

        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(405, -1);
            exchange.close();
            return;
        }

        String streamerId = queryParam(exchange.getRequestURI(), "source");
        if (streamerId == null || streamerId.isBlank()) streamerId = "main";

        byte[] body;
        try (InputStream in = exchange.getRequestBody()) {
            // Techo de tamanio: un evento de TikTok no pasa de unos pocos KB.
            body = in.readNBytes(64 * 1024);
        }

        try {
            ingest.accept(streamerId, new String(body, StandardCharsets.UTF_8));
            ingested.incrementAndGet();
        } catch (RuntimeException ex) {
            log.warning("[ingest] evento ilegible de '" + streamerId + "': " + ex);
        }

        exchange.sendResponseHeaders(204, -1);
        exchange.close();
    }

    private void handleHealth(HttpExchange exchange) throws IOException {
        byte[] body = ("{\"ok\":true,\"clients\":" + clients.size() + "}")
                .getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    private void handleEvents(HttpExchange exchange) throws IOException {
        if (!authorized(exchange)) return;

        exchange.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-cache, no-store");
        exchange.getResponseHeaders().set("Connection", "keep-alive");
        exchange.sendResponseHeaders(200, 0);

        Client client = new Client(exchange);
        clients.add(client);
        log.info("[overlay] cliente conectado (" + clients.size() + " en total)");

        // Una recarga de la browser source en OBS no debe dejar el panel vacío:
        // el estado actual se manda de inmediato.
        String state = stateSupplier == null ? null : stateSupplier.get();
        if (state != null && !client.send(state)) {
            drop(client);
        }
    }

    /**
     * Todo lo del panel: la página, sus datos, el guardado y las pruebas.
     *
     * <p>La autorización es <b>aparte</b> de la del overlay y no se puede desactivar. El puerto
     * del overlay acaba abierto a internet para que lleguen los relays de otros streamers, así
     * que un panel sin token sería una consola de administración pública.
     *
     * <p>Ojo con un detalle que engaña: cuando el tráfico entra por un túnel (playit, ngrok),
     * el cliente que ve el servidor es el túnel, que corre en la misma máquina. Mirar si la
     * petición viene de 127.0.0.1 NO distingue "yo" de "internet". Por eso: token, siempre.
     */
    private void handlePanel(HttpExchange exchange) throws IOException {
        PanelHooks hooks = panel;
        if (hooks == null) {
            enviarTexto(exchange, 503, "El panel no está activo.");
            return;
        }

        String esperado = hooks.token();
        if (esperado == null || esperado.isBlank()
                || !esperado.equals(queryParam(exchange.getRequestURI(), "token"))) {
            enviarTexto(exchange, 401, "Token del panel incorrecto o ausente.");
            return;
        }

        String path = exchange.getRequestURI().getPath();
        try {
            switch (path) {
                case "/panel", "/panel/" -> sendHtml(exchange, page("/overlay/panel.html"));
                case "/panel/data" -> enviarJson(exchange, hooks.data());
                case "/panel/save" -> enviarJson(exchange, hooks.save(leerCuerpo(exchange)));
                case "/panel/icono" -> {
                    byte[] png = hooks.icono(entero(queryParam(exchange.getRequestURI(), "id"), 0));
                    if (png == null) {
                        exchange.sendResponseHeaders(404, -1);
                        exchange.close();
                    } else {
                        exchange.getResponseHeaders().set("Content-Type", "image/png");
                        exchange.getResponseHeaders().set("Cache-Control", "max-age=300");
                        exchange.sendResponseHeaders(200, png.length);
                        try (OutputStream out = exchange.getResponseBody()) {
                            out.write(png);
                        }
                    }
                }
                case "/panel/test" -> {
                    int id = entero(queryParam(exchange.getRequestURI(), "id"), 0);
                    int coins = entero(queryParam(exchange.getRequestURI(), "coins"), 1);
                    enviarJson(exchange, hooks.test(id, coins));
                }
                default -> enviarTexto(exchange, 404, "No existe " + path);
            }
        } catch (RuntimeException ex) {
            log.warning("[panel] " + path + " falló: " + ex);
            enviarJson(exchange, "{\"ok\":false,\"mensaje\":\"Error en el servidor\"}");
        }
    }

    private static int entero(String raw, int fallback) {
        try {
            return Integer.parseInt(raw == null ? "" : raw.trim());
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private String leerCuerpo(HttpExchange exchange) throws IOException {
        try (InputStream in = exchange.getRequestBody()) {
            // Techo: el catálogo entero son unos pocos KB. Más que eso no es un catálogo.
            return new String(in.readNBytes(256 * 1024), StandardCharsets.UTF_8);
        }
    }

    private void enviarJson(HttpExchange exchange, String json) throws IOException {
        byte[] body = (json == null ? "{}" : json).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(200, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    private void enviarTexto(HttpExchange exchange, int codigo, String texto) throws IOException {
        byte[] body = texto.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
        exchange.sendResponseHeaders(codigo, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    private boolean authorized(HttpExchange exchange) throws IOException {
        if (token.isEmpty()) return true;

        if (token.equals(queryParam(exchange.getRequestURI(), "token"))) return true;

        exchange.sendResponseHeaders(401, -1);
        exchange.close();
        return false;
    }

    private static String queryParam(URI uri, String name) {
        String query = uri.getRawQuery();
        if (query == null) return null;
        for (String pair : query.split("&")) {
            int split = pair.indexOf('=');
            if (split > 0 && pair.substring(0, split).equals(name)) {
                return java.net.URLDecoder.decode(pair.substring(split + 1), StandardCharsets.UTF_8);
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ writer

    private void writeLoop() {
        while (running) {
            try {
                String json = outbox.poll(HEARTBEAT_SECONDS, TimeUnit.SECONDS);
                if (json == null) {
                    // Sin tráfico: un comentario SSE mantiene viva la conexión.
                    broadcastRaw(": ping\n\n");
                    continue;
                }
                broadcastRaw("data: " + json + "\n\n");
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return;
            } catch (RuntimeException ex) {
                log.warning("[overlay] fallo al emitir: " + ex);
            }
        }
    }

    private void broadcastRaw(String frame) {
        if (clients.isEmpty()) return;

        List<Client> snapshot = List.copyOf(clients);
        for (Client client : snapshot) {
            if (!client.sendRaw(frame)) {
                drop(client);
            }
        }
    }

    private void drop(Client client) {
        clients.remove(client);
        client.close();
        log.info("[overlay] cliente desconectado (" + clients.size() + " en total)");
    }

    private String page(String resource) {
        String cached = cachedPages.get(resource);
        if (cached != null) return cached;

        try (InputStream in = getClass().getResourceAsStream(resource)) {
            if (in == null) {
                return "<!doctype html><title>overlay</title>"
                        + "<p>No encuentro " + resource + " dentro del jar.";
            }
            String html = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            cachedPages.put(resource, html);
            return html;
        } catch (IOException ex) {
            return "<!doctype html><title>overlay</title><p>Error leyendo la página: "
                    + ex.getMessage();
        }
    }

    /** Un cliente SSE. Los writes solo ocurren en el hilo escritor. */
    private static final class Client {
        private final HttpExchange exchange;
        private final OutputStream out;

        Client(HttpExchange exchange) {
            this.exchange = exchange;
            this.out = exchange.getResponseBody();
        }

        boolean send(String json) {
            return sendRaw("data: " + json + "\n\n");
        }

        boolean sendRaw(String frame) {
            try {
                out.write(frame.getBytes(StandardCharsets.UTF_8));
                out.flush();
                return true;
            } catch (IOException ex) {
                return false;
            }
        }

        void close() {
            try {
                out.close();
            } catch (IOException ignored) {
                // el cliente ya se fue
            }
            exchange.close();
        }
    }
}
