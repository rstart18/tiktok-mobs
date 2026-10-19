package dev.juanse.tiktokmobs.core;

import dev.juanse.tiktokmobs.core.dedup.LikeAccumulator;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LedgerRestoreTest {

    @Test
    void recuperaMonedasYDonantesTrasUnReinicio() {
        DonorLedger antes = new DonorLedger(1000);
        antes.record("juanse", 60);
        antes.record("ana", 43);

        Map<String, Integer> guardado = antes.snapshot();

        DonorLedger despues = new DonorLedger(1000);
        despues.restore(guardado);

        assertEquals(103, despues.totalCoins());
        assertEquals("juanse", despues.top(5).get(0).label());
        assertEquals(43, despues.top(5).get(1).coins());
    }

    @Test
    void alRecuperarNoSeVuelvenADispararLasMetasYaCumplidas() {
        // Si solo se restaura el total, el servidor creeria que las dos metas acaban de
        // cumplirse y soltaria los dos jefes de golpe al arrancar.
        DonorLedger ledger = new DonorLedger(1000);
        ledger.restore(Map.of("ballena", 2500));

        assertEquals(2500, ledger.totalCoins());
        assertEquals(0, ledger.record("alguien", 1),
                "2500 ya pasó dos metas: no debe disparar ninguna al volver");

        // Pero la siguiente meta real (los 3000) sí tiene que saltar.
        assertEquals(1, ledger.record("alguien", 499));
    }

    @Test
    void laBarraDeMetaSigueDondeEstaba() {
        DonorLedger ledger = new DonorLedger(1000);
        ledger.restore(Map.of("juanse", 1240));

        assertEquals(0.24, ledger.goalProgress(), 0.001);
    }

    @Test
    void losLikesTambienVuelvenSinRepetirUmbrales() {
        LikeAccumulator likes = new LikeAccumulator(100);
        likes.restore(350);

        assertEquals(350, likes.total());
        assertEquals(0, likes.add(10), "360 sigue dentro del mismo umbral");
        assertEquals(1, likes.add(50), "400 cruza el siguiente");
    }

    @Test
    void unArchivoVacioOCorruptoNoRompeNada() {
        DonorLedger ledger = new DonorLedger(1000);
        ledger.record("juanse", 50);

        ledger.restore(null);
        assertEquals(0, ledger.totalCoins());

        Map<String, Integer> sucio = new LinkedHashMap<>();
        sucio.put("ok", 30);
        sucio.put("negativo", -5);
        sucio.put("nulo", null);
        ledger.restore(sucio);

        assertEquals(30, ledger.totalCoins(), "las entradas basura se ignoran");
    }
}
