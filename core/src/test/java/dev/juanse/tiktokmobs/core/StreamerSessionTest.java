package dev.juanse.tiktokmobs.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

class StreamerSessionTest {

    @Test
    void dosSesionesNoCompartenMonedas() {
        // El punto entero del multi-streamer: las donaciones del live de ana no pueden
        // aparecer en el top ni en la meta del live de beto.
        StreamerSession ana = new StreamerSession("ana", 1000, 20, 100);
        StreamerSession beto = new StreamerSession("beto", 1000, 20, 100);

        ana.ledger().record("viewer-a", 500);

        assertEquals(500, ana.ledger().totalCoins());
        assertEquals(0, beto.ledger().totalCoins());
    }

    @Test
    void dosSesionesNoCompartenNivelDeCaos() {
        // La escalera es permanente DENTRO del live. Si ana rompe el servidor subiendo a
        // nivel 8, el live de beto no puede empezar al tope de dificultad por eso.
        StreamerSession ana = new StreamerSession("ana", 1000, 2, 100);
        StreamerSession beto = new StreamerSession("beto", 1000, 2, 100);

        ana.chaos().record();
        ana.chaos().record();

        assertEquals(1, ana.chaos().level());
        assertEquals(0, beto.chaos().level());
    }

    @Test
    void dosSesionesNoCompartenLikes() {
        // Los umbrales de likes son del live: si se mezclaran, cinco audiencias saturarian
        // el umbral en segundos y la recompensa dejaria de ser una recompensa.
        StreamerSession ana = new StreamerSession("ana", 1000, 20, 100);
        StreamerSession beto = new StreamerSession("beto", 1000, 20, 100);

        assertEquals(0, ana.likes().add(80));
        assertEquals(0, beto.likes().add(80), "los 80 de ana no deben contar para beto");
        assertEquals(1, ana.likes().add(30), "ana cruza su primer umbral con 110");
    }

    @Test
    void losAccesoresDevuelvenSiempreElMismoObjeto() {
        // El panel y las barras guardan referencias: si cada llamada devolviera una pieza
        // distinta la UI se quedaria apuntando al estado viejo.
        StreamerSession s = new StreamerSession("main", 1000, 20, 100);

        assertSame(s.ledger(), s.ledger());
        assertSame(s.chaos(), s.chaos());
        assertSame(s.likes(), s.likes());
    }

    @Test
    void sesionesDistintasSonObjetosDistintos() {
        StreamerSession a = new StreamerSession("a", 1000, 20, 100);
        StreamerSession b = new StreamerSession("b", 1000, 20, 100);

        assertNotSame(a.ledger(), b.ledger());
        assertNotSame(a.chaos(), b.chaos());
        assertNotSame(a.likes(), b.likes());
    }

    @Test
    void unIdVacioCaeEnMain() {
        // Si llega una fuente sin etiquetar (fallo de config), cae en el live por defecto
        // y no en una clave rara que nadie monitorea.
        StreamerSession s = new StreamerSession("", 1000, 20, 100);
        assertEquals("main", s.streamerId());
    }
}
