package dev.juanse.tiktokmobs.core.dedup;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Los likes llegan en lotes de decenas por segundo: no puedes reaccionar a cada uno.
 * Esto acumula y te dice cuántos umbrales se cruzaron con el último lote, así una ráfaga
 * de 350 likes con umbral 100 dispara 3 acciones y no 350.
 */
public final class LikeAccumulator {

    private final int every;
    private final AtomicInteger total = new AtomicInteger();
    private final AtomicInteger consumedThresholds = new AtomicInteger();

    public LikeAccumulator(int every) {
        this.every = Math.max(1, every);
    }

    /** @return cuántos umbrales se cruzaron al sumar este lote (0 si ninguno). */
    public int add(int likes) {
        if (likes <= 0) return 0;
        int newTotal = total.addAndGet(likes);
        int thresholds = newTotal / every;
        int previous = consumedThresholds.getAndSet(thresholds);
        return Math.max(0, thresholds - previous);
    }

    public int total() {
        return total.get();
    }

    public int every() {
        return every;
    }

    /** Igual que en el ledger: restaurar el total sin volver a disparar los umbrales ya pasados. */
    public void restore(int likes) {
        int safe = Math.max(0, likes);
        total.set(safe);
        consumedThresholds.set(safe / every);
    }

    /** Llamar al empezar un live nuevo. */
    public void reset() {
        total.set(0);
        consumedThresholds.set(0);
    }
}
