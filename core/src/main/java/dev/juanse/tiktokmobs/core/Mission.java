package dev.juanse.tiktokmobs.core;


/**
 * La mision del streamer: lo que <b>el</b> esta intentando conseguir.
 *
 * <p>Es la tercera pata del directo, y la unica que no controla el publico:
 *
 * <ul>
 *   <li><b>Vidas</b> ({@link LifePool}): lo que puede perder.</li>
 *   <li><b>Meta de likes</b> ({@link LikeGoal}): lo que empuja la sala gratis.</li>
 *   <li><b>Mision</b>: lo que persigue el que juega.</li>
 * </ul>
 *
 * <p>Sin ella no hay historia, solo un tipo recibiendo cosas. Y de aqui sale la regla que
 * ordena el catalogo entero: <b>el publico controla los obstaculos, no el destino</b>. Pueden
 * complicarte el camino todo lo que paguen, pero si una donacion te entrega lo que ibas a
 * buscar, la busqueda deja de significar nada y con ella el directo.
 *
 * <p>Por eso lo que cuenta es <b>lo que se pica</b>, no lo que se tiene: una donacion puede
 * regalarte un diamante, pero no puede bajar a la mina por ti.
 */
public final class Mission {

    private final String label;
    private final int base;
    private final int escalada;

    private int enVuelta;
    private int vueltas;

    public Mission(String label, int objetivo) {
        this(label, objetivo, 0);
    }

    /**
     * @param escalada cuanto sube el objetivo con cada vuelta cumplida. Es la escalada del
     *                 streamer, en espejo a la del nivel de caos: ellos suben el caos con
     *                 donaciones, el sube su propio liston cumpliendo. Con 0, la mision se
     *                 queda siempre igual.
     */
    public Mission(String label, int objetivo, int escalada) {
        this.label = label == null || label.isBlank() ? "MISION" : label.trim();
        this.base = Math.max(0, objetivo);
        this.escalada = Math.max(0, escalada);
    }

    /** Sin objetivo configurado no hay mision ni barra. */
    public boolean activa() {
        return base > 0;
    }

    /** Lo que hay que conseguir en la vuelta de ahora. Crece con cada una cumplida. */
    public synchronized int objetivo() {
        return base + escalada * vueltas;
    }

    /**
     * Apunta lo conseguido.
     *
     * @return cuantas vueltas se completaron con esto (normalmente 0 o 1).
     */
    public synchronized int record(int cuantos) {
        if (!activa() || cuantos <= 0) return 0;

        enVuelta += cuantos;
        int cumplidas = 0;
        // Un lote grande puede cruzar varias vueltas, y cada una deja la siguiente mas cara.
        while (enVuelta >= objetivo()) {
            enVuelta -= objetivo();
            vueltas++;
            cumplidas++;
        }
        return cumplidas;
    }

    /** Lo conseguido dentro de la vuelta actual, para pintar "3 / 10". */
    public synchronized int dentro() {
        return enVuelta;
    }

    public synchronized int vueltas() {
        return vueltas;
    }

    public String label() {
        return label;
    }

    /** 0.0 a 1.0 dentro de la vuelta actual, listo para BossBar.progress(). */
    public synchronized float fraccion() {
        if (!activa()) return 0f;
        return Math.min(1f, enVuelta / (float) objetivo());
    }

    /** Total conseguido en el live, para guardarlo en disco. */
    public synchronized int logrado() {
        int total = enVuelta;
        for (int v = 0; v < vueltas; v++) {
            total += base + escalada * v;
        }
        return total;
    }

    /**
     * Tras un reinicio a mitad de live. Se vuelven a recorrer las vueltas con el total
     * guardado, porque con escalada cada una vale distinto y no se puede dividir.
     */
    public synchronized void restore(int conseguido) {
        enVuelta = 0;
        vueltas = 0;
        if (!activa()) return;

        int resto = Math.max(0, conseguido);
        while (resto >= objetivo()) {
            resto -= objetivo();
            vueltas++;
        }
        enVuelta = resto;
    }
}
