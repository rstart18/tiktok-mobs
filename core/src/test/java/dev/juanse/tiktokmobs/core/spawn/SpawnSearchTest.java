package dev.juanse.tiktokmobs.core.spawn;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpawnSearchTest {

    private static List<SpawnSearch.Candidate> buscar(int radius, int index, int total) {
        return SpawnSearch.candidates(100.0, 64.0, 200.0, radius, index, total);
    }

    @Test
    void elUltimoCandidatoEsSiempreElCentro() {
        // La red de seguridad del caso de la mina: si todo esta tapiado, el mob sale
        // donde esta el jugador, que es un sitio donde seguro cabe algo vivo.
        List<SpawnSearch.Candidate> c = buscar(8, 0, 5);
        SpawnSearch.Candidate ultimo = c.get(c.size() - 1);

        assertEquals(100.0, ultimo.x(), 0.0001);
        assertEquals(64.0, ultimo.y(), 0.0001);
        assertEquals(200.0, ultimo.z(), 0.0001);
    }

    @Test
    void buscaArribaYTambienAbajo() {
        // El fallo original: solo subia. En una cueva, arriba es piedra maciza.
        Set<Double> alturas = buscar(8, 0, 1).stream()
                .map(SpawnSearch.Candidate::y)
                .collect(Collectors.toSet());

        assertTrue(alturas.contains(63.0), "tiene que probar un bloque por debajo");
        assertTrue(alturas.contains(61.0), "y tres por debajo");
        assertTrue(alturas.contains(65.0), "sin dejar de probar por encima");
    }

    @Test
    void losAnillosSeVanCerrandoHaciaElCentro() {
        List<SpawnSearch.Candidate> c = buscar(12, 0, 1);

        double primero = distancia(c.get(0));
        double medio = distancia(c.get(c.size() / 2));

        assertTrue(primero > medio,
                "en un tunel estrecho hay que acabar pegado al jugador: " + primero + " vs " + medio);
        assertEquals(12.0, primero, 0.001, "el primer intento usa el radio pedido");
    }

    @Test
    void repartirElLoteSeparaLosMobs() {
        // Dos mobs del mismo lote no deben nacer en el mismo punto.
        SpawnSearch.Candidate a = buscar(10, 0, 4).get(0);
        SpawnSearch.Candidate b = buscar(10, 2, 4).get(0);

        assertTrue(Math.hypot(a.x() - b.x(), a.z() - b.z()) > 10.0,
                "indices opuestos del anillo deberian quedar enfrentados");
    }

    @Test
    void conRadioCeroLosAnillosCaenEnElCentroPeroIgualSeBuscanVecinos() {
        List<SpawnSearch.Candidate> c = buscar(0, 0, 3);

        // Los anillos, al tener radio 0, son todos el mismo punto.
        assertEquals(100.0, c.get(0).x(), 0.0001);
        assertEquals(200.0, c.get(0).z(), 0.0001);

        // Pero tiene que haber alternativas alrededor: si todos los mobs del lote
        // nacen en el mismo bloque se matan entre ellos por apelotonamiento.
        assertTrue(c.stream().anyMatch(v -> distancia(v) > 0.5),
                "hacen falta vecinos aunque el radio sea 0");
    }

    @Test
    void siElAnilloEstaTapiadoQuedanVecinosPegadosAlJugador() {
        // El caso de la mina: el anillo entero es pared, pero a un bloque hay hueco.
        List<SpawnSearch.Candidate> c = buscar(6, 0, 4);

        long cerca = c.stream().filter(v -> {
            double d = distancia(v);
            return d > 0.5 && d < 2.5;
        }).count();

        assertTrue(cerca >= 8, "deberia haber varios candidatos a 1-2 bloques, habia " + cerca);
    }

    @Test
    void siempreHayCandidatosAunqueElLoteSeaRaro() {
        assertTrue(buscar(5, 0, 0).size() > 1, "total 0 no debe romper el reparto");
        assertEquals(SpawnSearch.maxCandidates(), buscar(5, 0, 1).size());
    }

    private static double distancia(SpawnSearch.Candidate c) {
        return Math.hypot(c.x() - 100.0, c.z() - 200.0);
    }
}
