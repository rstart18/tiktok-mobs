package dev.juanse.tiktokmobs.plugin.overlay;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.juanse.tiktokmobs.core.DonorLedger;
import dev.juanse.tiktokmobs.core.action.ActionKind;
import dev.juanse.tiktokmobs.core.action.ActionSpec;
import dev.juanse.tiktokmobs.core.action.ResolvedAction;
import dev.juanse.tiktokmobs.core.event.GiftEvent;
import dev.juanse.tiktokmobs.core.event.GiftInfo;
import dev.juanse.tiktokmobs.core.event.TikTokUser;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OverlayPayloadsTest {

    private static ResolvedAction action(String donor, String giftName, int coins, int count) {
        GiftEvent event = GiftEvent.gift("m1",
                new TikTokUser("u1", donor, donor),
                new GiftInfo(1, giftName, coins, count, count > 1, true));

        return new ResolvedAction(
                ActionSpec.of(ActionKind.SPAWN).mob("ZOMBIE").count(3).build(),
                event, donor, event.coins(), "main");
    }

    private static JsonObject parse(String json) {
        return JsonParser.parseString(json).getAsJsonObject();
    }

    @Test
    void elEventoLlevaTodoLoQueElOverlayNecesita() {
        JsonObject json = parse(OverlayPayloads.event(
                action("juanse", "Rose", 1, 30), "3x zombie", 500, false));

        assertEquals("event", json.get("kind").getAsString());
        assertEquals("juanse", json.get("donor").getAsString());
        assertEquals("GIFT", json.get("type").getAsString());
        assertEquals("Rose", json.get("gift").getAsString());
        assertEquals(30, json.get("count").getAsInt());
        assertEquals(30, json.get("coins").getAsInt());
        assertEquals("3x zombie", json.get("action").getAsString());
        assertEquals("SPAWN", json.get("actionKind").getAsString());
    }

    @Test
    void losTiersSeCalculanPorMonedas() {
        assertEquals("social", OverlayPayloads.tierOf(0));
        assertEquals("common", OverlayPayloads.tierOf(1));
        assertEquals("common", OverlayPayloads.tierOf(9));
        assertEquals("uncommon", OverlayPayloads.tierOf(10));
        assertEquals("uncommon", OverlayPayloads.tierOf(99));
        assertEquals("rare", OverlayPayloads.tierOf(100));
        assertEquals("epic", OverlayPayloads.tierOf(999));
        assertEquals("legendary", OverlayPayloads.tierOf(1000));
    }

    @Test
    void laAlertaGrandeSoloSaltaDesdeElUmbral() {
        assertFalse(parse(OverlayPayloads.event(
                action("ana", "Rose", 1, 30), "3x zombie", 500, false))
                .get("alert").getAsBoolean());

        assertTrue(parse(OverlayPayloads.event(
                action("ana", "Galaxy", 1000, 1), "jefe wither", 500, false))
                .get("alert").getAsBoolean());
    }

    @Test
    void conUmbralCeroNuncaHayAlerta() {
        assertFalse(parse(OverlayPayloads.event(
                action("ana", "Lion", 29999, 1), "jefe warden", 0, false))
                .get("alert").getAsBoolean());
    }

    @Test
    void elModoDegradadoViajaAlOverlay() {
        assertTrue(parse(OverlayPayloads.event(
                action("ana", "Galaxy", 1000, 1), "jefe wither", 500, true))
                .get("degraded").getAsBoolean());
    }

    @Test
    void elEstadoLlevaElTopOrdenado() {
        JsonObject json = parse(OverlayPayloads.state(1500, 420, List.of(
                new DonorLedger.Donor("beto", 1000),
                new DonorLedger.Donor("ana", 500))));

        assertEquals("state", json.get("kind").getAsString());
        assertEquals(1500, json.get("totalCoins").getAsInt());
        assertEquals(420, json.get("likes").getAsInt());
        assertEquals(2, json.getAsJsonArray("top").size());
        assertEquals("beto", json.getAsJsonArray("top").get(0)
                .getAsJsonObject().get("label").getAsString());
    }

    @Test
    void unNickHostilNoPuedeRomperElJson() {
        // El core ya habría saneado esto, pero el serializador también tiene que aguantarlo:
        // el overlay está en directo y un JSON roto deja la pantalla congelada.
        String hostile = "\"},{\"x\":\"</script><img src=x onerror=alert(1)>";
        String json = OverlayPayloads.event(action(hostile, "Rose", 1, 1), "1x zombie", 500, false);

        JsonObject parsed = parse(json);
        assertEquals(hostile, parsed.get("donor").getAsString());
        assertEquals("event", parsed.get("kind").getAsString());
    }
}
