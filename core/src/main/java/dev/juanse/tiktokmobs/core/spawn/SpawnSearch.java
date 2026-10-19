package dev.juanse.tiktokmobs.core.spawn;

import java.util.ArrayList;
import java.util.List;

/**
 * Dónde intentar poner un mob, en orden de preferencia.
 *
 * <p>La versión ingenua —un anillo a radio fijo, y si no cabe subir unos bloques— funciona
 * en campo abierto y falla en todo lo demás. En una mina el anillo cae dentro de la pared,
 * subir topa con piedra maciza, y el mob acaba spawneando dentro de la roca y asfixiándose.
 *
 * <p>Así que esto genera candidatos en espiral: primero el anillo completo, luego anillos
 * cada vez más cerrados, y en cada uno busca arriba <b>y abajo</b>. El último candidato son
 * siempre los pies del jugador, que por definición son un sitio donde cabe algo vivo.
 *
 * <p>Geometría pura, sin Minecraft: el que llama decide qué posición es habitable.
 */
public final class SpawnSearch {

    /** Un sitio a probar. */
    public record Candidate(double x, double y, double z) {
    }

    /** Cuántos anillos, del radio pedido hacia el centro. */
    private static final int RINGS = 4;

    /** Orden de búsqueda vertical: primero a tu altura, luego alternando arriba y abajo. */
    private static final int[] VERTICAL = {0, 1, -1, 2, -2, 3, -3, 4};

    private SpawnSearch() {
    }

    /**
     * @param index  cuál de los mobs del lote es este (reparte el anillo)
     * @param total  cuántos van en el lote
     * @return candidatos ordenados; el último es siempre el centro exacto.
     */
    public static List<Candidate> candidates(double cx, double cy, double cz,
                                             int radius, int index, int total) {
        List<Candidate> out = new ArrayList<>(RINGS * VERTICAL.length + 1);
        int safeTotal = Math.max(1, total);

        for (int ring = 0; ring < RINGS; ring++) {
            double r = radius * (1.0 - ring / (double) RINGS);

            // Se gira un poco cada anillo para no repetir siempre la misma linea
            // cuando el sitio de delante esta tapiado.
            double angle = (2 * Math.PI * index) / safeTotal + ring * 0.7;

            double x = cx + Math.cos(angle) * r;
            double z = cz + Math.sin(angle) * r;

            for (int dy : VERTICAL) {
                out.add(new Candidate(x, cy + dy, z));
            }
        }

        // Si los anillos fallan todos (tunel estrecho), se prueban los vecinos
        // inmediatos ANTES de caer en el punto exacto del jugador. Sin esto, en una
        // mina todos los mobs del lote acaban en el mismo bloque y mueren por
        // apelotonamiento (entity cramming mata al pasar de 24 en un bloque).
        for (int r = 1; r <= 2; r++) {
            for (int i = 0; i < 8; i++) {
                double a = (2 * Math.PI * ((i + index) % 8)) / 8.0;
                double x = cx + Math.cos(a) * r;
                double z = cz + Math.sin(a) * r;
                for (int dy : new int[]{0, 1, -1}) {
                    out.add(new Candidate(x, cy + dy, z));
                }
            }
        }

        // Ultimo recurso: donde esta el jugador cabe seguro.
        out.add(new Candidate(cx, cy, cz));
        return out;
    }

    public static int maxCandidates() {
        return RINGS * VERTICAL.length + 2 * 8 * 3 + 1;
    }
}
