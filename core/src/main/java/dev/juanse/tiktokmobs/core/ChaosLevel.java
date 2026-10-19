package dev.juanse.tiktokmobs.core;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * El nivel de caos: el live escala solo y no vuelve atrás.
 *
 * <p>Cada N donaciones sube un nivel, y cada nivel multiplica lo que sale. Lo interesante no
 * es el multiplicador, es que <b>es permanente dentro del live</b>: a los viewers les da una
 * razón para donar que no es "quiero ver un zombi", sino "quiero ver hasta dónde llega esto".
 * Eso convierte donaciones sueltas en una carrera colectiva.
 *
 * <p>Con techo, claro: sin él, a la hora de live el servidor está a 4 TPS y ya no hay live.
 */
public final class ChaosLevel {

    /** Cuánto suma cada nivel al multiplicador. Nivel 4 = el doble de mobs. */
    private static final double POR_NIVEL = 0.25;

    /** Techo del multiplicador. Por encima, el servidor no aguanta y deja de ser gracioso. */
    public static final double MAX_MULTIPLIER = 3.0;

    /** Techo de niveles, derivado del techo del multiplicador. */
    public static final int MAX_LEVEL = (int) Math.round((MAX_MULTIPLIER - 1.0) / POR_NIVEL);

    private final int donacionesPorNivel;
    private final AtomicInteger donaciones = new AtomicInteger();
    private final AtomicInteger nivel = new AtomicInteger();

    public ChaosLevel(int donacionesPorNivel) {
        this.donacionesPorNivel = Math.max(1, donacionesPorNivel);
    }

    /**
     * Apunta una donación.
     *
     * @return el nivel nuevo si acaba de subir, o 0 si no cambió nada. Así quien llama sabe
     *         si tiene que anunciarlo, sin tener que recordar el nivel anterior.
     */
    public int record() {
        int total = donaciones.incrementAndGet();
        int objetivo = Math.min(total / donacionesPorNivel, MAX_LEVEL);

        if (objetivo > nivel.get()) {
            nivel.set(objetivo);
            return objetivo;
        }
        return 0;
    }

    public int level() {
        return nivel.get();
    }

    public int donations() {
        return donaciones.get();
    }

    /** Cuántas donaciones faltan para el siguiente nivel. 0 si ya está al tope. */
    public int toNextLevel() {
        if (nivel.get() >= MAX_LEVEL) return 0;
        int siguiente = (nivel.get() + 1) * donacionesPorNivel;
        return Math.max(0, siguiente - donaciones.get());
    }

    /** Por cuánto se multiplica lo que sale. 1.0 al empezar. */
    public double multiplier() {
        return Math.min(MAX_MULTIPLIER, 1.0 + nivel.get() * POR_NIVEL);
    }

    /** Aplica el multiplicador a una cantidad, sin bajar nunca de lo que ya había. */
    public int apply(int cantidad) {
        return Math.max(cantidad, (int) Math.round(cantidad * multiplier()));
    }

    public void restore(int donacionesPrevias, int nivelPrevio) {
        donaciones.set(Math.max(0, donacionesPrevias));
        nivel.set(Math.max(0, Math.min(nivelPrevio, MAX_LEVEL)));
    }

    public void reset() {
        donaciones.set(0);
        nivel.set(0);
    }

    public boolean atMax() {
        return nivel.get() >= MAX_LEVEL;
    }
}
