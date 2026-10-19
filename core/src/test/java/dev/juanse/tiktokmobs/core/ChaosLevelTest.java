package dev.juanse.tiktokmobs.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChaosLevelTest {

    @Test
    void subeDeNivelCadaNDonaciones() {
        ChaosLevel caos = new ChaosLevel(5);

        for (int i = 0; i < 4; i++) {
            assertEquals(0, caos.record(), "todavia no toca subir en la donacion " + (i + 1));
        }
        assertEquals(1, caos.record(), "la quinta donacion sube a nivel 1");
        assertEquals(1, caos.level());
    }

    @Test
    void soloAvisaUnaVezPorNivel() {
        // Si avisara en cada donacion, el chat seria un spam de "NIVEL DE CAOS 3".
        ChaosLevel caos = new ChaosLevel(2);
        caos.record();
        assertEquals(1, caos.record());
        assertEquals(0, caos.record(), "la siguiente donacion del mismo nivel no avisa");
    }

    @Test
    void elMultiplicadorCreceConElNivel() {
        ChaosLevel caos = new ChaosLevel(1);

        assertEquals(1.0, caos.multiplier(), 0.001, "al empezar no multiplica nada");
        caos.record();
        assertEquals(1.25, caos.multiplier(), 0.001);
    }

    @Test
    void hayTechoOElServidorSeCae() {
        ChaosLevel caos = new ChaosLevel(1);
        for (int i = 0; i < 500; i++) caos.record();

        assertEquals(ChaosLevel.MAX_LEVEL, caos.level());
        assertEquals(ChaosLevel.MAX_MULTIPLIER, caos.multiplier(), 0.001);
        assertTrue(caos.atMax());
    }

    @Test
    void aplicarNuncaDevuelveMenosDeLoQueHabia() {
        // Un multiplicador que redondee hacia abajo no puede quitarte mobs.
        ChaosLevel caos = new ChaosLevel(10);
        assertEquals(1, caos.apply(1));
        assertEquals(3, caos.apply(3));
    }

    @Test
    void aplicarMultiplicaCuandoHayNivel() {
        ChaosLevel caos = new ChaosLevel(1);
        caos.record();
        caos.record();
        caos.record();
        caos.record();

        assertEquals(4, caos.level());
        assertEquals(20, caos.apply(10), "x2 al nivel 4");
    }

    @Test
    void sabeCuantoFaltaParaElSiguiente() {
        ChaosLevel caos = new ChaosLevel(10);
        caos.record();
        caos.record();
        caos.record();

        assertEquals(7, caos.toNextLevel());
    }

    @Test
    void elReinicioDelServidorNoBorraLoQueYaPagaron() {
        ChaosLevel caos = new ChaosLevel(20);
        caos.restore(45, 2);

        assertEquals(2, caos.level());
        assertEquals(45, caos.donations());
        assertEquals(1.5, caos.multiplier(), 0.001);
    }

    @Test
    void restaurarUnNivelAbsurdoSeRecortaAlTecho() {
        ChaosLevel caos = new ChaosLevel(20);
        caos.restore(99999, 9999);

        assertEquals(ChaosLevel.MAX_LEVEL, caos.level());
    }
}
