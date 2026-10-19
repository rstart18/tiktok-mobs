package dev.juanse.tiktokmobs.core.boss;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BossTitleTest {

    private static final String ONE = "{donador}, el Implacable";
    private static final String MANY = "{donadores}, los Implacables";

    @Test
    void unDonanteUsaLaPlantillaSingular() {
        assertEquals("Pedro, el Implacable", BossTitle.compose(List.of("Pedro"), ONE, MANY));
    }

    @Test
    void dosDonantesComparten() {
        assertEquals("Pedro y Ana, los Implacables",
                BossTitle.compose(List.of("Pedro", "Ana"), ONE, MANY));
    }

    @Test
    void tresCaben() {
        assertEquals("Pedro, Ana y Luis, los Implacables",
                BossTitle.compose(List.of("Pedro", "Ana", "Luis"), ONE, MANY));
    }

    @Test
    void aPartirDeCuatroSeResume() {
        // Sin esto, cinco donantes dejan un cartel de media pantalla encima del bicho
        // y ya no se ve la pelea, que es lo que la gente vino a ver.
        String name = BossTitle.compose(List.of("Pedro", "Ana", "Luis", "Marta", "Juan"), ONE, MANY);

        assertEquals("Pedro, Ana y 3 más, los Implacables", name);
        assertTrue(name.length() < 50, "el nombre tiene que caber en pantalla: " + name);
    }

    @Test
    void sinDonantesNoHayNombre() {
        assertEquals("", BossTitle.compose(List.of(), ONE, MANY));
        assertEquals("", BossTitle.compose(null, ONE, MANY));
    }

    @Test
    void elMismoDonanteNoSeDuplica() {
        // Caso real: un combo de regalos del mismo viewer. Su nombre no puede salir tres veces.
        List<String> donors = BossTitle.add(BossTitle.add(List.of("Pedro"), "Pedro"), "Ana");

        assertEquals(List.of("Pedro", "Ana"), donors);
    }

    @Test
    void hayUnTopeDeDonantesGuardados() {
        List<String> donors = List.of();
        for (int i = 0; i < 20; i++) {
            donors = BossTitle.add(donors, "donante" + i);
        }
        assertEquals(BossTitle.STORED, donors.size(), "la PDC no es sitio para una lista infinita");
    }

    @Test
    void elGuardadoVaYVuelveIgual() {
        // Esto es lo que viaja en la PersistentDataContainer: si no sobrevive el ida y vuelta,
        // el jefe pierde su atribucion al recargarse el chunk.
        List<String> original = List.of("Pedro", "Ana María", "Luis.2");
        assertEquals(original, BossTitle.parse(BossTitle.store(original)));
    }

    @Test
    void unaCadenaVaciaNoDaDonantesFantasma() {
        assertEquals(List.of(), BossTitle.parse(""));
        assertEquals(List.of(), BossTitle.parse(null));
        assertEquals(List.of(), BossTitle.parse("||  ||"));
    }

    @Test
    void siNoHayPlantillaQuedanLosNombres() {
        assertEquals("Pedro y Ana", BossTitle.compose(List.of("Pedro", "Ana"), "", ""));
    }
}
