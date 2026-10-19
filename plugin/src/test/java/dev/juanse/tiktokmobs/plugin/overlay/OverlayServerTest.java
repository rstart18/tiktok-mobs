package dev.juanse.tiktokmobs.plugin.overlay;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Test de integración real del overlay: levanta el servidor en un puerto libre, se conecta
 * como lo haría la browser source de OBS y comprueba que los frames SSE llegan enteros.
 *
 * <p>Se puede hacer porque {@link OverlayServer} no depende de Bukkit: solo del JDK.
 */
class OverlayServerTest {

    private static final Logger LOG = Logger.getLogger(OverlayServerTest.class.getName());

    private OverlayServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop();
            server = null;
        }
    }

    private OverlayServer start(String token, String state) {
        server = new OverlayServer(LOG, "127.0.0.1", 0, token, () -> state, () -> "{}");
        assertTrue(server.start(), "el servidor debería arrancar en un puerto libre");
        return server;
    }

    private String base() {
        return "http://127.0.0.1:" + server.boundPort();
    }

    @Test
    void sirveLaPaginaDelOverlayDesdeElJar() throws Exception {
        start("", null);

        HttpURLConnection connection = (HttpURLConnection) URI.create(base() + "/").toURL().openConnection();
        connection.setConnectTimeout(3000);
        connection.setReadTimeout(3000);

        assertEquals(200, connection.getResponseCode());
        assertEquals("text/html; charset=utf-8", connection.getHeaderField("Content-Type"));

        String body;
        try (InputStream in = connection.getInputStream()) {
            body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }

        assertTrue(body.contains("TikTokMobs Overlay"), "debe servir index.html real");
        assertTrue(body.contains("EventSource"), "la página tiene que traer el cliente SSE");
        assertTrue(body.contains("background: transparent"),
                "fondo transparente: sin esto el overlay tapa el juego en OBS");
    }

    @Test
    void unaRutaDesconocidaDevuelve404() throws Exception {
        start("", null);

        HttpURLConnection connection =
                (HttpURLConnection) URI.create(base() + "/otra-cosa").toURL().openConnection();
        connection.setConnectTimeout(3000);
        connection.setReadTimeout(3000);

        assertEquals(404, connection.getResponseCode());
    }

    @Test
    void conTokenConfiguradoSeRechazaElAccesoSinEl() throws Exception {
        start("secreto", null);

        HttpURLConnection sinToken =
                (HttpURLConnection) URI.create(base() + "/").toURL().openConnection();
        sinToken.setConnectTimeout(3000);
        sinToken.setReadTimeout(3000);
        assertEquals(401, sinToken.getResponseCode());

        HttpURLConnection conToken =
                (HttpURLConnection) URI.create(base() + "/?token=secreto").toURL().openConnection();
        conToken.setConnectTimeout(3000);
        conToken.setReadTimeout(3000);
        assertEquals(200, conToken.getResponseCode());
    }

    @Test
    void alConectarseRecibeElEstadoActualYLuegoLosEventos() throws Exception {
        String state = OverlayPayloads.state(1500, 42, List.of());
        start("", state);

        List<String> frames = new ArrayList<>();
        CountDownLatch gotTwo = new CountDownLatch(2);

        Thread client = new Thread(() -> {
            try {
                HttpURLConnection connection =
                        (HttpURLConnection) URI.create(base() + "/events").toURL().openConnection();
                connection.setConnectTimeout(3000);
                connection.setReadTimeout(8000);

                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (line.startsWith("data: ")) {
                            synchronized (frames) {
                                frames.add(line.substring(6));
                            }
                            gotTwo.countDown();
                        }
                    }
                }
            } catch (IOException ignored) {
                // el test cierra el servidor al final
            }
        }, "overlay-test-client");
        client.setDaemon(true);
        client.start();

        // Esperamos a que el cliente esté registrado antes de publicar.
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (server.clientCount() == 0 && System.nanoTime() < deadline) {
            Thread.sleep(25);
        }
        assertEquals(1, server.clientCount(), "el cliente SSE debería estar registrado");

        server.push("{\"kind\":\"event\",\"donor\":\"juanse\"}");

        assertTrue(gotTwo.await(6, TimeUnit.SECONDS),
                "deberían llegar el estado inicial y el evento");

        synchronized (frames) {
            assertTrue(frames.get(0).contains("\"kind\":\"state\""),
                    "lo primero es el estado, para que una recarga de OBS no deje el panel vacío");
            assertTrue(frames.get(1).contains("juanse"));
        }
    }

    @Test
    void pushNoBloqueaCuandoNadieEstaEscuchando() {
        start("", null);

        // La cola es acotada: 1000 eventos sin ningún cliente no deben bloquear ni lanzar.
        // Esto es lo que protege el hilo principal del servidor de Minecraft.
        for (int i = 0; i < 1000; i++) {
            server.push("{\"kind\":\"event\",\"n\":" + i + "}");
        }
        assertEquals(0, server.clientCount());
    }
}
