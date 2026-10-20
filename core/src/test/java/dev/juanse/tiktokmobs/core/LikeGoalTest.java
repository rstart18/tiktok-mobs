package dev.juanse.tiktokmobs.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LikeGoalTest {

    @Test
    void sinObjetivoLaMetaEstaApagada() {
        LikeGoal meta = new LikeGoal(0, "WITHER");

        assertFalse(meta.activa());
        assertEquals(0, meta.record(999_999), "apagada no debe cobrar nunca");
    }

    @Test
    void cobraAlCruzar() {
        LikeGoal meta = new LikeGoal(1000, "WITHER");

        assertEquals(0, meta.record(999));
        assertEquals(1, meta.record(1000));
        assertEquals(0, meta.record(1500), "dentro de la misma meta no se vuelve a cobrar");
        assertEquals(1, meta.record(2000));
    }

    @Test
    void unLoteEnormeCobraTodasLasQueCruzo() {
        // En un live con publico entran miles de likes entre dos ticks. Si solo cobraramos
        // una, el premio se perderia y el contador diria que ya pasaste tres metas.
        LikeGoal meta = new LikeGoal(1000, "WITHER");

        assertEquals(3, meta.record(3200));
    }

    @Test
    void laBarraVaDentroDeLaMetaActual() {
        LikeGoal meta = new LikeGoal(1000, "WITHER");

        assertEquals(0.5f, meta.fraccion(1500), 0.001f);
        assertEquals(500, meta.dentro(1500));
        assertEquals(500, meta.faltan(1500));
    }

    @Test
    void restaurarNoDisparaLosPremiosYaCobrados() {
        // Reinicio a mitad de live con 48.740 likes guardados: al siguiente like no puede
        // creer que acaban de completarse las 48 metas de golpe.
        LikeGoal meta = new LikeGoal(1000, "WITHER");
        meta.restoreFrom(48_740);

        assertEquals(0, meta.record(48_740));
        assertEquals(0, meta.record(48_900));
        assertEquals(1, meta.record(49_000), "la siguiente de verdad si se cobra");
    }

    @Test
    void laEtiquetaTieneRespaldo() {
        assertEquals("META", new LikeGoal(100, "  ").label());
        assertEquals("WITHER", new LikeGoal(100, " WITHER ").label());
    }
}
