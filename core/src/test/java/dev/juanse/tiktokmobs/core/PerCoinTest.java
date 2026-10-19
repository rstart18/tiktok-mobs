package dev.juanse.tiktokmobs.core;

import dev.juanse.tiktokmobs.core.action.ActionKind;
import dev.juanse.tiktokmobs.core.action.ActionSpec;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PerCoinTest {

    private static ActionSpec husk() {
        return ActionSpec.of(ActionKind.SPAWN).mob("HUSK")
                .perCoin(1).count(2).maxCount(30).build();
    }

    @Test
    void quinceTiktoksSonQuinceMobs() {
        // El caso que fallaba: 15 regalos de 1 moneda daban 5 mobs fijos.
        assertEquals(15, husk().effectiveCount(15));
    }

    @Test
    void unaSolaMonedaSigueHaciendoAlgoVisible() {
        assertEquals(2, husk().effectiveCount(1), "el 'count' actúa de suelo");
    }

    @Test
    void unaBallenaNoTumbaElServidor() {
        assertEquals(30, husk().effectiveCount(29_999), "max-count manda");
    }

    @Test
    void elTechoDuroNoSePuedeSuperarDesdeElConfig() {
        ActionSpec absurdo = ActionSpec.of(ActionKind.SPAWN).mob("HUSK")
                .perCoin(10).count(1).maxCount(5000).build();

        assertEquals(ActionSpec.HARD_MAX_COUNT, absurdo.effectiveCount(1_000_000));
    }

    @Test
    void sinPerCoinSeComportaComoAntes() {
        ActionSpec fijo = ActionSpec.of(ActionKind.SPAWN).mob("CREEPER").count(6).build();

        assertEquals(6, fijo.effectiveCount(1));
        assertEquals(6, fijo.effectiveCount(5_000));
    }

    @Test
    void elCatalogoLoExplicaEnPalabras() {
        assertEquals("1 husk por moneda", husk().describe());
    }
}
