package dev.juanse.tiktokmobs.core;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Las vidas del live: un contador visible que el publico baja y sube.
 *
 * <p>Es lo que le da columna vertebral al catalogo. Sin esto, los regalos son efectos
 * sueltos sin relacion: unos sacan mobs, otros dan objetos, y ninguno significa nada
 * respecto al otro. Con un contador de vidas, todo el catalogo se reparte solo en dos
 * bandos: lo que <b>ataca</b> (creepers, nether, lava) y lo que <b>defiende</b> (totem,
 * manzana, vida extra). El mismo catalogo, pero contando una historia.
 *
 * <p>Lo importante del diseño: las donaciones <b>no restan vidas directamente</b>. Restan
 * las muertes. El regalo crea el peligro, el peligro mata, la muerte cuesta una vida. Si el
 * regalo restara solo, seria un boton de "quitarle un punto" y daria igual lo que pase en
 * pantalla; asi, lo que se ve en el juego <i>es</i> lo que mueve el marcador.
 *
 * <p>Llegar a cero no acaba el live: es el climax. Quien llama decide que pasa y vuelve a
 * llenar. Un contador que se agota y termina la transmision se usa una vez; uno que se
 * agota, explota y se reinicia se puede vivir cinco veces en una noche.
 */
public final class LifePool {

    /** Techo duro. Mas alla, el contador deja de leerse de un vistazo en camara. */
    public static final int MAX_POSIBLE = 100;

    private final int max;
    private final AtomicInteger vidas;

    public LifePool(int max) {
        this.max = Math.max(1, Math.min(max, MAX_POSIBLE));
        this.vidas = new AtomicInteger(this.max);
    }

    /**
     * Una muerte.
     *
     * @return las vidas que quedan. <b>0 significa que se agotaron</b>, y es la señal de que
     *         quien llama tiene que montar el climax y rellenar.
     */
    public int perder() {
        return vidas.updateAndGet(actual -> Math.max(0, actual - 1));
    }

    /**
     * Una vida extra.
     *
     * @return true si de verdad subio. Si ya estaba al tope devuelve false, para que el
     *         anuncio no mienta diciendo "+1 VIDA" cuando no cambio nada.
     */
    public boolean ganar() {
        int previo = vidas.getAndUpdate(actual -> Math.min(max, actual + 1));
        return previo < max;
    }

    public int vidas() {
        return vidas.get();
    }

    public int max() {
        return max;
    }

    public boolean agotada() {
        return vidas.get() <= 0;
    }

    /** 0.0 a 1.0, listo para BossBar.progress(). */
    public float fraccion() {
        return Math.max(0f, Math.min(1f, vidas.get() / (float) max));
    }

    /** Vuelve al maximo. Es lo que se llama despues del climax. */
    public void rellenar() {
        vidas.set(max);
    }

    /** Recupera lo guardado en disco tras un reinicio a mitad de live. */
    public void restore(int guardadas) {
        vidas.set(Math.max(0, Math.min(guardadas, max)));
    }
}
