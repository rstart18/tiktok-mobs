package dev.juanse.tiktokmobs.core;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * La meta grande de likes, con nombre propio: <i>"WITHER - 48.740 / 60.000 likes"</i>.
 *
 * <p>Es distinta de la meta de donacion y no la sustituye. La de monedas la empujan los que
 * pagan; esta la empuja <b>todo el mundo</b>, porque los likes son gratis e infinitos. Eso la
 * convierte en el unico objetivo colectivo en el que puede participar quien no gasta, que
 * resulta ser la mayoria del directo. Y de paso es la metrica que mas mueve el algoritmo.
 *
 * <p>Lo del nombre no es decoracion. "Meta: 60.000 likes" no le dice nada a nadie; "el WITHER
 * sale a los 60.000" se explica solo y da una razon concreta para seguir dandole. La
 * recompensa tiene que ser algo que se pueda nombrar y que la gente quiera ver.
 *
 * <p>Se puede cruzar varias veces en un live: al llegar, se cobra y el contador sigue hacia la
 * siguiente. Un objetivo que solo se alcanza una vez deja el resto de la noche sin nada que
 * empujar.
 */
public final class LikeGoal {

    private final int target;
    private final String label;
    private final AtomicInteger cobradas = new AtomicInteger();

    public LikeGoal(int target, String label) {
        this.target = Math.max(0, target);
        this.label = label == null || label.isBlank() ? "META" : label.trim();
    }

    /** Sin objetivo configurado no hay barra ni premio: la meta esta apagada. */
    public boolean activa() {
        return target > 0;
    }

    /**
     * Apunta el total de likes del live.
     *
     * @return cuantas metas se han completado con esta actualizacion (normalmente 0 o 1;
     *         mas de una si entro un lote enorme de golpe).
     */
    public int record(int totalLikes) {
        if (!activa()) return 0;

        int alcanzadas = Math.max(0, totalLikes) / target;
        int previas = cobradas.getAndSet(alcanzadas);
        return Math.max(0, alcanzadas - previas);
    }

    /** 0.0 a 1.0 dentro de la meta actual, listo para BossBar.progress(). */
    public float fraccion(int totalLikes) {
        if (!activa()) return 0f;
        return (Math.max(0, totalLikes) % target) / (float) target;
    }

    /** Cuantos likes faltan para la siguiente. */
    public int faltan(int totalLikes) {
        if (!activa()) return 0;
        return target - (Math.max(0, totalLikes) % target);
    }

    /** Los likes que llevan dentro de la meta actual, para pintar "48.740 / 60.000". */
    public int dentro(int totalLikes) {
        if (!activa()) return 0;
        return Math.max(0, totalLikes) % target;
    }

    public int target() {
        return target;
    }

    public String label() {
        return label;
    }

    /**
     * Tras un reinicio a mitad de live, los likes se restauran del disco. Hay que decirle
     * cuantas metas ya se cobraron o al primer like creeria que acaban de completarse todas
     * de golpe y soltaria el premio una vez por cada una.
     */
    public void restoreFrom(int totalLikes) {
        cobradas.set(activa() ? Math.max(0, totalLikes) / target : 0);
    }
}
