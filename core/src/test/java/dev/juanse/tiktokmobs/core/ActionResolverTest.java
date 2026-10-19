package dev.juanse.tiktokmobs.core;

import dev.juanse.tiktokmobs.core.action.ActionKind;
import dev.juanse.tiktokmobs.core.action.ActionSpec;
import dev.juanse.tiktokmobs.core.action.ResolvedAction;
import dev.juanse.tiktokmobs.core.config.MappingTable;
import dev.juanse.tiktokmobs.core.dedup.DedupPolicy;
import dev.juanse.tiktokmobs.core.event.EventType;
import dev.juanse.tiktokmobs.core.event.GiftEvent;
import dev.juanse.tiktokmobs.core.event.GiftInfo;
import dev.juanse.tiktokmobs.core.event.TikTokUser;
import dev.juanse.tiktokmobs.core.source.FakeGiftSource;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ActionResolverTest {

    private static final TikTokUser VIEWER = new TikTokUser("u1", "juanse", "Juan Sebastián");

    private static MappingTable table() {
        return MappingTable.builder()
                .gift("Rose", ActionSpec.of(ActionKind.SPAWN).mob("ZOMBIE").count(3).build())
                .gift(5655, ActionSpec.of(ActionKind.SPAWN).mob("SKELETON").count(2).build())
                .tier(9, ActionSpec.of(ActionKind.SPAWN).mob("CHICKEN").count(1).build())
                .tier(99, ActionSpec.of(ActionKind.HORDE).mob("ZOMBIE").count(10).build())
                .tier(999, ActionSpec.of(ActionKind.WORLD_EVENT).worldEvent("tnt_rain").build())
                .tier(999_999, ActionSpec.of(ActionKind.BOSS).mob("WARDEN").build())
                .on(EventType.LIKE, ActionSpec.of(ActionKind.EFFECT).effect("SPEED").seconds(10).build())
                .on(EventType.FOLLOW, ActionSpec.of(ActionKind.SPAWN).mob("ALLAY").count(1).build())
                .likeEvery(100)
                .goal(1000, ActionSpec.of(ActionKind.BOSS).mob("WITHER").build())
                .build();
    }

    private static ActionResolver resolver() {
        return new ActionResolver(table(), new DedupPolicy(60_000L));
    }

    @Test
    void mapeoPorNombreGanaAlTier() {
        List<ResolvedAction> actions = resolver().resolve(
                GiftEvent.gift("m1", VIEWER, GiftInfo.single(1, "Rose", 1)));

        assertEquals(1, actions.size());
        assertEquals("ZOMBIE", actions.get(0).spec().mob());
        assertEquals(3, actions.get(0).spec().count());
    }

    @Test
    void mapeoPorIdGanaAlNombre() {
        List<ResolvedAction> actions = resolver().resolve(
                GiftEvent.gift("m1", VIEWER, GiftInfo.single(5655, "Rose", 1)));

        assertEquals("SKELETON", actions.get(0).spec().mob());
    }

    @Test
    void regaloDesconocidoCaeEnElTierPorMonedas() {
        // "Galaxy Nuevo 2026" no está mapeado: 50 monedas -> tramo de 99
        List<ResolvedAction> actions = resolver().resolve(
                GiftEvent.gift("m2", VIEWER, GiftInfo.single(99999, "Galaxy Nuevo 2026", 50)));

        assertEquals(ActionKind.HORDE, actions.get(0).spec().kind());
    }

    @Test
    void porEncimaDelUltimoTramoAplicaElMasAlto() {
        List<ResolvedAction> actions = resolver().resolve(
                GiftEvent.gift("m3", VIEWER, GiftInfo.single(42, "Ballena", 5_000_000)));

        assertEquals(ActionKind.BOSS, actions.get(0).spec().kind());
    }

    @Test
    void laRachaAbiertaNoDisparaNadaYSoloCuentaElCierre() {
        ActionResolver resolver = resolver();
        List<GiftEvent> emitted = new ArrayList<>();

        FakeGiftSource source = new FakeGiftSource();
        source.start(emitted::add);
        source.emitStreak("juanse", 1, "Rose", 1, 30);

        assertEquals(30, emitted.size(), "la fuente emite los 30 eventos de la racha");

        int actions = 0;
        for (GiftEvent event : emitted) {
            actions += resolver.resolve(event).size();
        }
        assertEquals(1, actions, "pero solo el repeatEnd produce una acción");
    }

    @Test
    void eventoReenviadoTrasReconnectNoSeDuplica() {
        ActionResolver resolver = resolver();
        GiftEvent event = GiftEvent.gift("msg-abc", VIEWER, GiftInfo.single(1, "Rose", 1));

        assertEquals(1, resolver.resolve(event).size());
        assertEquals(0, resolver.resolve(event).size(), "mismo msgId: descartado");
    }

    @Test
    void losLikesSeAgrupanPorUmbral() {
        ActionResolver resolver = resolver();

        assertEquals(0, resolver.resolve(GiftEvent.like("l1", VIEWER, 40)).size());
        assertEquals(0, resolver.resolve(GiftEvent.like("l2", VIEWER, 40)).size());
        // 40 + 40 + 40 = 120 -> cruza el umbral de 100 exactamente una vez
        assertEquals(1, resolver.resolve(GiftEvent.like("l3", VIEWER, 40)).size());
    }

    @Test
    void unaRafagaEnormeDeLikesTieneTecho() {
        ActionResolver resolver = resolver();
        // 5000 likes cruzarían 50 umbrales; el techo evita 50 acciones en un tick
        assertEquals(5, resolver.resolve(GiftEvent.like("l1", VIEWER, 5_000)).size());
    }

    @Test
    void laMetaDeDonacionDisparaElJefe() {
        ActionResolver resolver = resolver();

        List<ResolvedAction> actions = resolver.resolve(
                GiftEvent.gift("m9", VIEWER, GiftInfo.single(77, "Leon", 1000)));

        assertEquals(2, actions.size(), "la acción del tier + la recompensa de la meta");
        assertTrue(actions.stream().anyMatch(a -> "WITHER".equals(a.spec().mob())));
        assertEquals(1000, resolver.ledger().totalCoins());
    }

    @Test
    void elRankingDeDonantesOrdenaPorMonedas() {
        ActionResolver resolver = resolver();
        resolver.resolve(GiftEvent.gift("a", new TikTokUser("u1", "ana", "ana"),
                GiftInfo.single(1, "Rose", 5)));
        resolver.resolve(GiftEvent.gift("b", new TikTokUser("u2", "beto", "beto"),
                GiftInfo.single(1, "Rose", 50)));

        List<DonorLedger.Donor> top = resolver.ledger().top(10);
        assertEquals("beto", top.get(0).label());
        assertEquals(50, top.get(0).coins());
    }

    @Test
    void eventoSinMapeoNoProduceAcciones() {
        assertTrue(resolver().resolve(
                GiftEvent.simple("s1", EventType.SHARE, VIEWER)).isEmpty());
    }

    @Test
    void dosStreamersTienenNivelDeCaosYMetaIndependientes() {
        // La promesa del multi-streamer: lo que haga la audiencia de un live no afecta al
        // nivel de caos ni al top de donantes del otro. Si se mezclaran, la escalera subiria
        // en el live equivocado y la meta de uno la pagarian los viewers del otro.
        ActionResolver resolver = new ActionResolver(table(), new DedupPolicy(60_000L), 2);

        TikTokUser viewerA = new TikTokUser("a1", "ana_v", "Ana V");
        TikTokUser viewerB = new TikTokUser("b1", "beto_v", "Beto V");

        // Dos donaciones en el live de ana suben su caos a nivel 1 (umbral 2).
        resolver.resolve(GiftEvent.gift("ma1", viewerA, GiftInfo.single(1, "Rose", 1)), "ana");
        resolver.resolve(GiftEvent.gift("ma2", viewerA, GiftInfo.single(1, "Rose", 1)), "ana");

        // Una donacion en el live de beto NO le sube el caos todavia.
        resolver.resolve(GiftEvent.gift("mb1", viewerB, GiftInfo.single(1, "Rose", 1)), "beto");

        assertEquals(1, resolver.chaos("ana").level(), "ana lleva 2 donaciones -> nivel 1");
        assertEquals(0, resolver.chaos("beto").level(), "beto lleva 1 donacion -> nivel 0");

        assertEquals(2, resolver.ledger("ana").totalCoins());
        assertEquals(1, resolver.ledger("beto").totalCoins());
    }
}
