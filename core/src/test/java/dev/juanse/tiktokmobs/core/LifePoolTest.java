package dev.juanse.tiktokmobs.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LifePoolTest {

    @Test
    void empiezaLlena() {
        LifePool vidas = new LifePool(20);

        assertEquals(20, vidas.vidas());
        assertEquals(20, vidas.max());
        assertFalse(vidas.agotada());
    }

    @Test
    void cadaMuerteRestaUna() {
        LifePool vidas = new LifePool(3);

        assertEquals(2, vidas.perder());
        assertEquals(1, vidas.perder());
        assertEquals(0, vidas.perder());
    }

    @Test
    void noBajaDeCeroPorMuchoQueMueras() {
        // Si bajara a negativo, la barra se saldria de rango y el climax se dispararia
        // varias veces seguidas en vez de una.
        LifePool vidas = new LifePool(1);
        vidas.perder();

        assertEquals(0, vidas.perder());
        assertEquals(0, vidas.perder());
        assertTrue(vidas.agotada());
    }

    @Test
    void ganarAvisaSiDeVerdadSubio() {
        LifePool vidas = new LifePool(2);
        vidas.perder();

        assertTrue(vidas.ganar(), "quedaba hueco: tiene que subir");
        assertEquals(2, vidas.vidas());
        assertFalse(vidas.ganar(), "ya estaba al tope: no debe anunciar +1 VIDA");
        assertEquals(2, vidas.vidas());
    }

    @Test
    void rellenarVuelveAlMaximo() {
        LifePool vidas = new LifePool(5);
        vidas.perder();
        vidas.perder();
        vidas.rellenar();

        assertEquals(5, vidas.vidas());
    }

    @Test
    void laFraccionSirveParaLaBarra() {
        LifePool vidas = new LifePool(4);

        assertEquals(1.0f, vidas.fraccion(), 0.001f);
        vidas.perder();
        assertEquals(0.75f, vidas.fraccion(), 0.001f);
    }

    @Test
    void restaurarRecortaALoPosible() {
        // El ledger de disco puede venir de una config con otro maximo: si decia 50 y ahora
        // el tope son 20, restaurar 50 dejaria la barra por encima del 100%.
        LifePool vidas = new LifePool(20);

        vidas.restore(50);
        assertEquals(20, vidas.vidas());

        vidas.restore(-3);
        assertEquals(0, vidas.vidas());
    }

    @Test
    void elMaximoTieneTecho() {
        assertEquals(LifePool.MAX_POSIBLE, new LifePool(5000).max());
        assertEquals(1, new LifePool(0).max());
    }
}
