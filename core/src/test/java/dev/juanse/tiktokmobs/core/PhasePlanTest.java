package dev.juanse.tiktokmobs.core;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PhasePlanTest {

    private static final long T0 = 1_000_000L;

    @Test
    void sinFasesLasDonacionesSiemprePasan() {
        // Es el comportamiento de toda la vida: un plan sin configurar no puede dejar
        // el servidor mudo.
        PhasePlan plan = PhasePlan.apagado();
        plan.iniciar(T0);

        assertFalse(plan.activo());
        assertTrue(plan.donacionesActivas());
        assertNull(plan.actual());
        assertNull(plan.avanzarSiToca(T0 + 9_999_999L));
    }

    // ------------------------------------------------------------ marco "aguanta"

    @Test
    void unaFaseInfinitaNoAvanzaNunca() {
        PhasePlan plan = new PhasePlan(
                List.of(new PhasePlan.Fase("AGUANTA", 0, true)), false);
        plan.iniciar(T0);

        assertTrue(plan.donacionesActivas());
        assertEquals(-1, plan.restantes(T0 + 3_600_000L), "sin limite no hay cuenta atras");
        assertNull(plan.avanzarSiToca(T0 + 3_600_000L));
        assertEquals("AGUANTA", plan.actual().nombre());
    }

    // ------------------------------------------------- marco "termina a tiempo"

    @Test
    void unaFaseConRelojTerminaYSeApaga() {
        PhasePlan plan = new PhasePlan(
                List.of(new PhasePlan.Fase("CONTRARRELOJ", 60, true)), false);
        plan.iniciar(T0);

        assertEquals(60, plan.restantes(T0));
        assertEquals(30, plan.restantes(T0 + 30_000L));
        assertEquals(0, plan.restantes(T0 + 60_000L));

        assertNull(plan.avanzarSiToca(T0 + 60_000L), "no hay siguiente y no es ciclico");
        assertFalse(plan.activo(), "se acabo el formato");
        assertTrue(plan.donacionesActivas(), "terminado != mudo");
    }

    // ------------------------------------------------------------ marco "rondas"

    @Test
    void dosFasesAlternanYElInterruptorSigueALaFase() {
        PhasePlan plan = new PhasePlan(List.of(
                new PhasePlan.Fase("CONSTRUYE", 600, false),
                new PhasePlan.Fase("DESTRUYE", 300, true)), true);
        plan.iniciar(T0);

        assertEquals("CONSTRUYE", plan.actual().nombre());
        assertFalse(plan.donacionesActivas(), "construyendo no entra nada");

        assertNull(plan.avanzarSiToca(T0 + 599_000L), "todavia no");

        PhasePlan.Fase nueva = plan.avanzarSiToca(T0 + 600_000L);
        assertNotNull(nueva);
        assertEquals("DESTRUYE", nueva.nombre());
        assertTrue(plan.donacionesActivas());

        // y vuelve a empezar, porque es ciclico
        assertEquals("CONSTRUYE", plan.avanzarSiToca(T0 + 900_000L).nombre());
    }

    @Test
    void devuelveLaFaseSoloElTickEnQueCambia() {
        // Igual que el nivel de caos: quien llama lo anuncia una vez, no en cada tick.
        PhasePlan plan = new PhasePlan(List.of(
                new PhasePlan.Fase("A", 10, true),
                new PhasePlan.Fase("B", 10, false)), true);
        plan.iniciar(T0);

        assertNotNull(plan.avanzarSiToca(T0 + 10_000L));
        assertNull(plan.avanzarSiToca(T0 + 11_000L), "ya se anuncio");
        assertNull(plan.avanzarSiToca(T0 + 19_000L));
        assertNotNull(plan.avanzarSiToca(T0 + 20_000L));
    }

    @Test
    void laFraccionSirveParaLaBarra() {
        PhasePlan plan = new PhasePlan(
                List.of(new PhasePlan.Fase("X", 100, true)), false);
        plan.iniciar(T0);

        assertEquals(0f, plan.fraccion(T0), 0.001f);
        assertEquals(0.5f, plan.fraccion(T0 + 50_000L), 0.001f);
        assertEquals(1f, plan.fraccion(T0 + 500_000L), 0.001f, "no pasa de 1");
    }

    @Test
    void saltarAMano() {
        PhasePlan plan = new PhasePlan(List.of(
                new PhasePlan.Fase("A", 600, false),
                new PhasePlan.Fase("B", 600, true)), true);
        plan.iniciar(T0);

        assertEquals("B", plan.siguiente(T0 + 5_000L).nombre());
        assertEquals(600, plan.restantes(T0 + 5_000L), "el reloj arranca de cero");
    }

    @Test
    void elNombreVacioTieneRespaldo() {
        assertEquals("FASE", new PhasePlan.Fase("  ", 10, true).nombre());
        assertTrue(new PhasePlan.Fase("x", -5, true).infinita(), "negativo = sin limite");
    }
}
