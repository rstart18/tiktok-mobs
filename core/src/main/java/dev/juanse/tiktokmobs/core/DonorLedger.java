package dev.juanse.tiktokmobs.core;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Lleva la cuenta de monedas por donante y el total del live.
 * Alimenta dos cosas que son puro motor de engagement: el top de donantes en el sidebar
 * y la barra de meta de donación.
 */
public final class DonorLedger {

    public record Donor(String label, int coins) {
    }

    private final Map<String, AtomicInteger> coinsByDonor = new ConcurrentHashMap<>();
    private final AtomicInteger totalCoins = new AtomicInteger();
    private final AtomicInteger goalsReached = new AtomicInteger();
    private final int goalCoins;

    public DonorLedger(int goalCoins) {
        this.goalCoins = Math.max(0, goalCoins);
    }

    /** @return cuántas metas se completaron con esta donación (normalmente 0 o 1). */
    public int record(String donorLabel, int coins) {
        if (coins <= 0) return 0;

        coinsByDonor.computeIfAbsent(donorLabel, k -> new AtomicInteger()).addAndGet(coins);
        int total = totalCoins.addAndGet(coins);

        if (goalCoins <= 0) return 0;
        int reached = total / goalCoins;
        int previous = goalsReached.getAndSet(reached);
        return Math.max(0, reached - previous);
    }

    public int totalCoins() {
        return totalCoins.get();
    }

    public int goalCoins() {
        return goalCoins;
    }

    /** 0.0 a 1.0 dentro de la meta actual. Listo para BossBar.setProgress. */
    public double goalProgress() {
        if (goalCoins <= 0) return 0.0;
        return (totalCoins.get() % goalCoins) / (double) goalCoins;
    }

    public List<Donor> top(int limit) {
        List<Donor> donors = new ArrayList<>(coinsByDonor.size());
        coinsByDonor.forEach((label, coins) -> donors.add(new Donor(label, coins.get())));
        donors.sort(Comparator.comparingInt(Donor::coins).reversed()
                .thenComparing(Donor::label));
        return donors.size() <= limit ? donors : new ArrayList<>(donors.subList(0, limit));
    }

    /** Lo que hay ahora, para guardarlo a disco. */
    /** Cuánto lleva donado esta persona en el live. 0 si no ha donado nada. */
    public int coinsOf(String donorLabel) {
        if (donorLabel == null) return 0;
        AtomicInteger coins = coinsByDonor.get(donorLabel);
        return coins == null ? 0 : coins.get();
    }

    public java.util.Map<String, Integer> snapshot() {
        java.util.Map<String, Integer> out = new java.util.LinkedHashMap<>();
        coinsByDonor.forEach((label, coins) -> out.put(label, coins.get()));
        return out;
    }

    /**
     * Vuelve a cargar un live que se quedo a medias (un reinicio, un corte de luz).
     *
     * <p>Lo delicado es {@code goalsReached}: si solo restauras el total, al arrancar
     * el servidor creeria que todas las metas se acaban de cumplir y te soltaria los
     * jefes otra vez de golpe.
     */
    public void restore(java.util.Map<String, Integer> donors) {
        coinsByDonor.clear();
        int total = 0;

        if (donors != null) {
            for (java.util.Map.Entry<String, Integer> e : donors.entrySet()) {
                if (e.getKey() == null || e.getValue() == null || e.getValue() <= 0) continue;
                coinsByDonor.put(e.getKey(), new AtomicInteger(e.getValue()));
                total += e.getValue();
            }
        }

        totalCoins.set(total);
        goalsReached.set(goalCoins > 0 ? total / goalCoins : 0);
    }

    public void reset() {
        coinsByDonor.clear();
        totalCoins.set(0);
        goalsReached.set(0);
    }
}
