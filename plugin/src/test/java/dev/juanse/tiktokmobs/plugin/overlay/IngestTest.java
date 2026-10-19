package dev.juanse.tiktokmobs.plugin.overlay;

import dev.juanse.tiktokmobs.core.ActionResolver;
import dev.juanse.tiktokmobs.core.action.ActionKind;
import dev.juanse.tiktokmobs.core.action.ActionSpec;
import dev.juanse.tiktokmobs.core.action.ResolvedAction;
import dev.juanse.tiktokmobs.core.config.MappingTable;
import dev.juanse.tiktokmobs.core.dedup.DedupPolicy;
import dev.juanse.tiktokmobs.core.event.GiftEvent;
import dev.juanse.tiktokmobs.core.event.GiftInfo;
import dev.juanse.tiktokmobs.core.event.TikTokUser;
import dev.juanse.tiktokmobs.plugin.source.TikFinityParser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** El camino que recorre una donación del PC de un amigo hasta el mundo. */
class IngestTest {

    private static final Logger LOG = Logger.getLogger(IngestTest.class.getName());

    private OverlayServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop();
            server = null;
        }
    }

    private int postIngest(String source, String token, String body) throws Exception {
        String url = "http://127.0.0.1:" + server.boundPort() + "/ingest?source=" + source
                + (token.isBlank() ? "" : "&token=" + token);

        HttpURLConnection connection = (HttpURLConnection) URI.create(url).toURL().openConnection();
        connection.setRequestMethod("POST");
        connection.setDoOutput(true);
        connection.setConnectTimeout(3000);
        connection.setReadTimeout(3000);
        connection.setRequestProperty("Content-Type", "application/json");

        try (OutputStream out = connection.getOutputStream()) {
            out.write(body.getBytes(StandardCharsets.UTF_8));
        }
        return connection.getResponseCode();
    }

    @Test
    void elRelayDeUnAmigoEntregaSuDonacionEtiquetada() throws Exception {
        List<String> recibidos = new CopyOnWriteArrayList<>();

        server = new OverlayServer(LOG, "127.0.0.1", 0, "", () -> null, () -> "{}");
        assertTrue(server.start());
        server.setIngest((streamerId, json) -> recibidos.add(streamerId + "::" + json));

        String evento = """
                {"event":"gift","data":{"giftId":5655,"giftName":"Rosa","diamondCount":1,
                  "repeatCount":3,"repeatEnd":true,"nickname":"viewer_del_pana"}}
                """;

        assertEquals(204, postIngest("pana", "", evento));

        assertEquals(1, recibidos.size());
        assertTrue(recibidos.get(0).startsWith("pana::"), "el evento llega etiquetado con su live");
        assertTrue(recibidos.get(0).contains("viewer_del_pana"));

        // Y ese JSON es el que entiende el parser de siempre.
        GiftEvent parsed = TikFinityParser.parse(evento).orElseThrow();
        assertEquals(3, parsed.gift().count());
        assertEquals(1, server.ingested());
    }

    @Test
    void sinTokenNoSePuedeInyectarNadaCuandoHayToken() throws Exception {
        server = new OverlayServer(LOG, "127.0.0.1", 0, "secreto", () -> null, () -> "{}");
        assertTrue(server.start());
        server.setIngest((id, json) -> {
            throw new AssertionError("no deberia llegar nada sin token");
        });

        assertEquals(401, postIngest("intruso", "", "{\"event\":\"gift\"}"));
        assertEquals(0, server.ingested());
    }

    @Test
    void dosLivesConElMismoMsgIdNoSePisan() {
        // Sin el streamer en la clave de dedup, la donación del segundo live se perdería.
        MappingTable table = MappingTable.builder()
                .tier(9, ActionSpec.of(ActionKind.SPAWN).mob("HUSK").count(2).build())
                .build();
        ActionResolver resolver = new ActionResolver(table, new DedupPolicy(60_000L));

        GiftEvent mismoId = GiftEvent.gift("msg-1",
                new TikTokUser("u1", "viewer", "viewer"), GiftInfo.single(1, "Rosa", 1));

        List<ResolvedAction> deJuanse = resolver.resolve(mismoId, "juanse");
        List<ResolvedAction> delPana = resolver.resolve(mismoId, "pana");

        assertEquals(1, deJuanse.size());
        assertEquals(1, delPana.size(), "el mismo msgId en otro live sigue contando");
        assertEquals("juanse", deJuanse.get(0).streamerId());
        assertEquals("pana", delPana.get(0).streamerId());

        // Pero repetirlo dentro del mismo live sigue descartándose.
        assertEquals(0, resolver.resolve(mismoId, "pana").size());
    }
}
