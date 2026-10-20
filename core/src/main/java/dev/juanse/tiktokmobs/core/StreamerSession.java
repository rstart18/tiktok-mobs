package dev.juanse.tiktokmobs.core;

import dev.juanse.tiktokmobs.core.dedup.LikeAccumulator;

/**
 * El estado de UN live, agrupado: monedas, nivel de caos, likes y vidas.
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
    private final LifePool vidas;
    private final LikeGoal likeGoal;
    private final Mission mision;

    public StreamerSession(String streamerId, int goalCoins, int donacionesPorNivelDeCaos,
                           int likeEvery) {
        this(streamerId, goalCoins, donacionesPorNivelDeCaos, likeEvery, 20);
    }

    public StreamerSession(String streamerId, int goalCoins, int donacionesPorNivelDeCaos,
                           int likeEvery, int vidasMax) {
        this(streamerId, goalCoins, donacionesPorNivelDeCaos, likeEvery, vidasMax, 0, "META");
    }

    public StreamerSession(String streamerId, int goalCoins, int donacionesPorNivelDeCaos,
                           int likeEvery, int vidasMax, int likeGoalTarget,
                           String likeGoalLabel) {
        this(streamerId, goalCoins, donacionesPorNivelDeCaos, likeEvery, vidasMax,
                likeGoalTarget, likeGoalLabel, "MISION", 0, 0);
    }

    public StreamerSession(String streamerId, int goalCoins, int donacionesPorNivelDeCaos,
                           int likeEvery, int vidasMax, int likeGoalTarget,
                           String likeGoalLabel, String misionLabel, int misionObjetivo,
                           int misionEscalada) {
        this.streamerId = streamerId == null || streamerId.isBlank() ? "main" : streamerId;
        this.ledger = new DonorLedger(goalCoins);
        this.chaos = new ChaosLevel(donacionesPorNivelDeCaos);
        this.likes = new LikeAccumulator(likeEvery);
        this.vidas = new LifePool(vidasMax);
        this.likeGoal = new LikeGoal(likeGoalTarget, likeGoalLabel);
        this.mision = new Mission(misionLabel, misionObjetivo, misionEscalada);
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

    public LifePool vidas() {
        return vidas;
    }

    public LikeGoal likeGoal() {
        return likeGoal;
    }

    public Mission mision() {
        return mision;
    }
}
