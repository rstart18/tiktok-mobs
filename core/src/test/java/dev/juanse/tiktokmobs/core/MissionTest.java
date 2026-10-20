package dev.juanse.tiktokmobs.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MissionTest {

    @Test
    void sinObjetivoNoHayMision() {
        Mission mision = new Mission("DIAMANTES", 0);

        assertFalse(mision.activa());
        assertEquals(0, mision.record(50));
        assertEquals(0f, mision.fraccion(), 0.001f);
    }

    @Test
    void cuentaYCompletaAlLlegar() {
        Mission mision = new Mission("DIAMANTES", 10);

        assertEquals(0, mision.record(4));
        assertEquals(4, mision.logrado());
        assertEquals(0, mision.record(5));
        assertEquals(1, mision.record(1), "el decimo completa");
    }

    @Test
    void conEscaladaCadaVueltaCuestaMas() {
        // 10, luego 15, luego 20. Es la escalada del streamer: en espejo a la del caos,
        // que sube por donaciones, esta sube por meritos.
        Mission mision = new Mission("DIAMANTES", 10, 5);

        assertEquals(10, mision.objetivo());
        assertEquals(1, mision.record(10));
        assertEquals(15, mision.objetivo(), "la segunda vuelta cuesta 5 mas");
        assertEquals(0, mision.record(14));
        assertEquals(1, mision.record(1));
        assertEquals(20, mision.objetivo());
    }

    @Test
    void restaurarConEscaladaRehaceLasVueltas() {
        // Con escalada no se puede dividir: hay que recorrer las vueltas una a una porque
        // cada una vale distinto. 10 + 15 = 25, y sobran 3 de la tercera (que cuesta 20).
        Mission mision = new Mission("DIAMANTES", 10, 5);
        mision.restore(28);

        assertEquals(2, mision.vueltas());
        assertEquals(3, mision.dentro());
        assertEquals(20, mision.objetivo());
        assertEquals(28, mision.logrado(), "el total vuelve a salir igual");
    }

    @Test
    void sinEscaladaElObjetivoNoSeMueve() {
        Mission mision = new Mission("DIAMANTES", 10);
        mision.record(30);

        assertEquals(10, mision.objetivo());
        assertEquals(3, mision.vueltas());
    }

    @Test
    void sigueContandoDespuesDeCompletar() {
        // La mision no se acaba al cumplirse: da otra vuelta. Si terminara, el resto del
        // live se quedaria sin nada que perseguir.
        Mission mision = new Mission("DIAMANTES", 10);
        mision.record(10);

        assertEquals(0, mision.dentro(), "vuelta nueva empieza en cero");
        assertEquals(1, mision.record(10));
        assertEquals(20, mision.logrado());
    }

    @Test
    void unLoteGrandeCompletaLasVueltasQueCorresponden() {
        Mission mision = new Mission("DIAMANTES", 10);

        assertEquals(3, mision.record(32));
        assertEquals(2, mision.dentro());
    }

    @Test
    void laBarraVaDentroDeLaVueltaActual() {
        Mission mision = new Mission("DIAMANTES", 10);
        mision.record(13);

        assertEquals(0.3f, mision.fraccion(), 0.001f);
        assertEquals(3, mision.dentro());
    }

    @Test
    void restaurarNoCantaLasVueltasYaHechas() {
        Mission mision = new Mission("DIAMANTES", 10);
        mision.restore(37);

        assertEquals(7, mision.dentro());
        assertEquals(0, mision.record(2), "39 sigue dentro de la cuarta vuelta");
        assertEquals(1, mision.record(1), "el 40 si la completa");
    }

    @Test
    void loNegativoNoCuenta() {
        Mission mision = new Mission("DIAMANTES", 10);

        assertEquals(0, mision.record(-5));
        assertEquals(0, mision.logrado());
    }

    @Test
    void laEtiquetaTieneRespaldo() {
        assertEquals("MISION", new Mission("  ", 10).label());
        assertTrue(new Mission("DIAMANTES", 10).activa());
    }
}
