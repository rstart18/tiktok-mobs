package dev.juanse.tiktokmobs.core;

import dev.juanse.tiktokmobs.core.dedup.LikeAccumulator;

/**
 * El estado de UN live, agrupado: monedas, nivel de caos y likes.
 *
 * <p>Con varios streamers en el mismo servidor, cada live va a tener su propia meta de
 * donacion, su propio top de donantes, su propia escalera de caos y su propio contador de
 * likes. Juntarlo todo en una clase evita que a mitad del plan quede un {@link DonorLedger}
 * por streamer y un {@link ChaosLevel} global suelto por descuido: aqui se ven las tres
 * piezas al mismo tiempo y es dificil olvidarse de una.
 *
 * <p>Nada global dentro: dos sesiones no comparten monedas, ni nivel, ni umbrales de likes.
 */
public final class StreamerSession {

    private final String streamerId;
    private final DonorLedger ledger;
    private final ChaosLevel chaos;
    private final LikeAccumulator likes;

    public StreamerSession(String streamerId, int goalCoins, int donacionesPorNivelDeCaos,
                           int likeEvery) {
        this.streamerId = streamerId == null || streamerId.isBlank() ? "main" : streamerId;
        this.ledger = new DonorLedger(goalCoins);
        this.chaos = new ChaosLevel(donacionesPorNivelDeCaos);
        this.likes = new LikeAccumulator(likeEvery);
    }

    public String streamerId() {
        return streamerId;
    }

    public DonorLedger ledger() {
        return ledger;
    }

    public ChaosLevel chaos() {
        return chaos;
    }

    public LikeAccumulator likes() {
        return likes;
    }
}
