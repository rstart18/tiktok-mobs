package dev.juanse.tiktokmobs.core.boss;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BossDefinitionTest {

    private static BossDefinition base() {
        return BossDefinition.builder("super-boss")
                .hpBase(40).hpPerCoin(0.5).hpMax(300)
                .build();
    }

    @Test
    void laVidaEsceladaConLasMonedas() {
        BossDefinition def = base();

        assertEquals(40.0, def.hpFor(0), 0.001, "sin monedas, la vida base");
        assertEquals(290.0, def.hpFor(500), 0.001, "500 monedas = 40 + 250");
    }

    @Test
    void laVidaNoPasaDelTecho() {
        // El caso del regalo de 30.000 monedas: sin techo, el jefe es inmortal y el live
        // se convierte en media hora de golpear una pared.
        assertEquals(300.0, base().hpFor(100_000), 0.001);
    }

    @Test
    void elTechoDuroManda() {
        BossDefinition absurdo = BossDefinition.builder("x").hpMax(999_999).build();
        assertEquals(BossDefinition.HARD_MAX_HP, absurdo.hpMax(), 0.001);
    }

    @Test
    void enfurecerSumaSoloLaParteProporcional() {
        // Si enfurecer sumara tambien hp-base, dos regalos de 1 moneda darian mas vida que
        // uno de 500 y la gente aprenderia a trollear el jefe a base de regalos minimos.
        BossDefinition def = base();

        assertEquals(140.0, def.hpAfterEnrage(40.0, 200), 0.001);
        assertEquals(300.0, def.hpAfterEnrage(290.0, 500), 0.001, "respeta el techo");
    }

    @Test
    void enfurecerNuncaBajaLaVida() {
        assertEquals(290.0, base().hpAfterEnrage(290.0, 0), 0.001);
    }

    @Test
    void elEquipoEligeElTramoMasAltoQueAlcanza() {
        BossDefinition def = BossDefinition.builder("x")
                .gear(new BossDefinition.Gear(0, "STONE_SWORD", "", "", "", "", 1))
                .gear(new BossDefinition.Gear(500, "IRON_SWORD", "", "", "", "", 2))
                .gear(new BossDefinition.Gear(2000, "DIAMOND_SWORD", "", "", "", "", 4))
                .build();

        assertEquals("STONE_SWORD", def.gearFor(10).weapon());
        assertEquals("IRON_SWORD", def.gearFor(500).weapon());
        assertEquals("IRON_SWORD", def.gearFor(1999).weapon());
        assertEquals("DIAMOND_SWORD", def.gearFor(5000).weapon());
    }

    @Test
    void sinEquipoConfiguradoNoHayEquipo() {
        assertNull(base().gearFor(5000));
    }

    @Test
    void losValoresImposiblesCaenEnAlgoJugable() {
        BossDefinition roto = new BossDefinition("x", "", "", -5, -1, -1,
                "", "", -10, 5.0, 3, 1, "", 0.0, null);

        assertEquals("WITHER_SKELETON", roto.baseEntity(), "sin base-entity, una que no arde");
        assertTrue(roto.hpBase() > 0);
        assertTrue(roto.suspenseTicks() > 0, "un suspenso de 0 ticks no es suspenso");
        assertTrue(roto.minionsAt() > 0 && roto.minionsAt() < 1, "una fraccion de vida valida");
        assertTrue(roto.minionsMax() >= roto.minionsMin(), "max por debajo de min es imposible");
        assertNotNull(roto.gear());
    }

    @Test
    void sinModelIdNoSePideModelo() {
        assertTrue(!base().hasModel());
        assertTrue(BossDefinition.builder("x").modelId("mi_jefe").build().hasModel());
    }
}
