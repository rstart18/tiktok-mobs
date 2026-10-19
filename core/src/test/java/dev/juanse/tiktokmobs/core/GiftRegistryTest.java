package dev.juanse.tiktokmobs.core;

import dev.juanse.tiktokmobs.core.action.ActionKind;
import dev.juanse.tiktokmobs.core.action.ActionSpec;
import dev.juanse.tiktokmobs.core.config.MappingTable;
import dev.juanse.tiktokmobs.core.dedup.DedupPolicy;
import dev.juanse.tiktokmobs.core.event.GiftEvent;
import dev.juanse.tiktokmobs.core.event.GiftInfo;
import dev.juanse.tiktokmobs.core.event.TikTokUser;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GiftRegistryTest {

    private static final TikTokUser VIEWER = new TikTokUser("u1", "juanse", "juanse");

    @Test
    void laPrimeraVezEsNuevaYLasSiguientesNo() {
        GiftRegistry registry = new GiftRegistry();
        GiftInfo rosa = GiftInfo.single(5655, "Rosa", 1);

        assertTrue(registry.record(rosa, false), "la primera vez hay que loguearlo");
        assertFalse(registry.record(rosa, false), "la segunda no, o inundamos la consola");
        assertEquals(1, registry.size());
        assertEquals(2, registry.seen().get(0).times());
    }

    @Test
    void elMismoRegaloEnOtroIdiomaEsElMismoId() {
        GiftRegistry registry = new GiftRegistry();

        registry.record(GiftInfo.single(5655, "Rose", 1), false);
        registry.record(GiftInfo.single(5655, "Rosa", 1), false);
        registry.record(GiftInfo.single(5655, "Corazón coreano", 1), false);

        assertEquals(1, registry.size(), "un id es un regalo, se llame como se llame");
    }

    @Test
    void ordenaDeMasCaroAMasBarato() {
        GiftRegistry registry = new GiftRegistry();
        registry.record(GiftInfo.single(1, "Rosa", 1), false);
        registry.record(GiftInfo.single(2, "Galaxy", 1000), false);
        registry.record(GiftInfo.single(3, "Corazon", 5), false);

        List<GiftRegistry.Seen> seen = registry.seen();
        assertEquals("Galaxy", seen.get(0).name());
        assertEquals("Rosa", seen.get(2).name());
    }

    @Test
    void elBloqueDeConfigSoloTraeLosNoMapeados() {
        GiftRegistry registry = new GiftRegistry();
        registry.record(GiftInfo.single(5655, "Rosa", 1), true);
        registry.record(GiftInfo.single(7777, "Corazón coreano", 5), false);

        String snippet = registry.asConfigSnippet();

        assertTrue(snippet.contains("7777"), "el que falta por mapear sí aparece");
        assertFalse(snippet.contains("5655"), "el ya mapeado no se vuelve a sugerir");
        assertTrue(snippet.startsWith("gift-ids:"));
    }

    @Test
    void elResolverApuntaLosRegalosQueVePorElCaminoNormal() {
        MappingTable table = MappingTable.builder()
                .tier(9, ActionSpec.of(ActionKind.SPAWN).mob("HUSK").count(2).build())
                .build();
        ActionResolver resolver = new ActionResolver(table, new DedupPolicy(60_000L));

        resolver.resolve(GiftEvent.gift("m1", VIEWER, GiftInfo.single(7777, "Corazón coreano", 5)));

        assertEquals(1, resolver.gifts().size());
        GiftRegistry.Seen seen = resolver.gifts().seen().get(0);
        assertEquals(7777, seen.id());
        assertFalse(seen.explicitlyMapped(), "cayó por tramos, no por mapeo propio");
    }

    @Test
    void unNombreEnOtroIdiomaNoCoincideYCaePorTramos() {
        // Esto es exactamente lo que pasaba en vivo: el config decía "Finger Heart"
        // y TikTok mandaba "Corazón coreano".
        MappingTable table = MappingTable.builder()
                .gift("Finger Heart", ActionSpec.of(ActionKind.SPAWN).mob("SKELETON").count(4).build())
                .tier(9, ActionSpec.of(ActionKind.SPAWN).mob("HUSK").count(2).build())
                .build();

        GiftInfo enEspanol = GiftInfo.single(7777, "Corazón coreano", 5);

        assertFalse(table.hasExplicit(enEspanol));
        assertEquals("HUSK", table.forGift(enEspanol).orElseThrow().mob());

        // Mapeado por id, el idioma deja de importar.
        MappingTable porId = MappingTable.builder()
                .gift(7777, ActionSpec.of(ActionKind.SPAWN).mob("SKELETON").count(4).build())
                .tier(9, ActionSpec.of(ActionKind.SPAWN).mob("HUSK").count(2).build())
                .build();

        assertTrue(porId.hasExplicit(enEspanol));
        assertEquals("SKELETON", porId.forGift(enEspanol).orElseThrow().mob());
    }
}
