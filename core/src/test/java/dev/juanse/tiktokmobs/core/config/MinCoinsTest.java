package dev.juanse.tiktokmobs.core.config;

import dev.juanse.tiktokmobs.core.action.ActionKind;
import dev.juanse.tiktokmobs.core.action.ActionSpec;
import dev.juanse.tiktokmobs.core.event.GiftInfo;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * El regalo que invoca al jefe cuesta 500 monedas, pero el mismo id puede llegar con menos
 * (combos a medias, o TikTok cambiando el precio de un regalo). La regla acordada: no sale el
 * jefe, pero el que dono no se queda mirando — cae al tramo por monedas.
 */
class MinCoinsTest {

    private static MappingTable table() {
        return MappingTable.builder()
                .gift(5269, ActionSpec.of(ActionKind.BOSS)
                        .bossId("super-boss")
                        .minCoins(500)
                        .build())
                .tier(29, ActionSpec.of(ActionKind.SPAWN).mob("HUSK").perCoin(1).build())
                .tier(999, ActionSpec.of(ActionKind.HORDE).mob("SPIDER").build())
                .build();
    }

    @Test
    void conLasMonedasJustasSaleElJefe() {
        Optional<ActionSpec> spec = table().forGift(GiftInfo.single(5269, "Lion", 500));

        assertTrue(spec.isPresent());
        assertEquals(ActionKind.BOSS, spec.get().kind());
        assertEquals("super-boss", spec.get().bossId());
    }

    @Test
    void conMenosMonedasCaeAlTramoYNoSeQuedaEnNada() {
        Optional<ActionSpec> spec = table().forGift(GiftInfo.single(5269, "Lion", 300));

        assertTrue(spec.isPresent(), "nadie dona y se va con las manos vacias");
        assertEquals(ActionKind.HORDE, spec.get().kind(), "300 monedas caen en el tramo de 999");
        assertEquals("SPIDER", spec.get().mob());
    }

    @Test
    void conMasMonedasTambienSaleElJefe() {
        Optional<ActionSpec> spec = table().forGift(GiftInfo.single(5269, "Lion", 3000));

        assertTrue(spec.isPresent());
        assertEquals(ActionKind.BOSS, spec.get().kind());
    }

    @Test
    void unaRachaSumaParaElMinimo() {
        // 5 regalos de 100 monedas = 500: el jefe sale igual que con uno de 500.
        GiftInfo racha = new GiftInfo(5269, "Lion", 100, 5, true, true);

        assertEquals(500, racha.totalCoins());
        assertEquals(ActionKind.BOSS, table().forGift(racha).orElseThrow().kind());
    }

    @Test
    void sinMinCoinsNadaCambia() {
        MappingTable sinMinimo = MappingTable.builder()
                .gift(5269, ActionSpec.of(ActionKind.BOSS).bossId("super-boss").build())
                .tier(999, ActionSpec.of(ActionKind.HORDE).mob("SPIDER").build())
                .build();

        assertEquals(ActionKind.BOSS, sinMinimo.forGift(GiftInfo.single(5269, "Lion", 1))
                .orElseThrow().kind());
    }

    @Test
    void elBossSimpleDeSiempreSigueSiendoBossSimple() {
        // Los tramos altos que ya tenia (RAVAGER, WARDEN) no llevan 'boss:', y tienen que
        // seguir yendo por el camino de siempre y no por el del jefe nuevo.
        ActionSpec viejo = ActionSpec.of(ActionKind.BOSS).mob("RAVAGER").healthScale(2.0).build();

        assertTrue(!viejo.isSuperBoss());
        assertTrue(ActionSpec.of(ActionKind.BOSS).bossId("super-boss").build().isSuperBoss());
    }
}
