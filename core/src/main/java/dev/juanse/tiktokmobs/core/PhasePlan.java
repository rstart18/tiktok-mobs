package dev.juanse.tiktokmobs.core;

import java.util.ArrayList;
import java.util.List;

/**
 * El reloj del formato: fases con duración y un interruptor de donaciones.
 *
 * <p>Parece poca cosa y en realidad es lo que hace que un mismo servidor pueda jugar tres
 * formatos distintos sin tocar una línea de código, solo cambiando el YAML:
 *
 * <ul>
 *   <li><b>Aguanta</b>: una sola fase, sin límite, donaciones siempre activas. El marcador
 *       es cuánto duras.</li>
 *   <li><b>Termina a tiempo</b>: una sola fase con cuenta atrás. Compites contra el reloj
 *       mientras el público te estorba.</li>
 *   <li><b>Rondas</b>: dos fases alternando, construir con las donaciones cerradas y
 *       destruir con ellas abiertas.</li>
 * </ul>
 *
 * <p>Lo importante del tercero: separar los dos papeles <b>en el tiempo</b> le da al público
 * un momento que esperar. Un goteo constante de destrucción mientras construyes no es
 * tensión, es que no puedes jugar.
 *
 * <p><b>Sin fases configuradas el plan está apagado y las donaciones siempre pasan</b>, que
 * es exactamente como se comportaba el plugin antes de que esto existiera.
 */
public final class PhasePlan {

    /**
     * @param segundos duración; <b>0 significa sin límite</b> y la fase no avanza sola.
     * @param donaciones si las donaciones disparan durante esta fase. Cuando es false no se
     *                   pierden: quedan retenidas y caen de golpe al abrirse la siguiente,
     *                   que es la mitad del espectáculo.
     */
    public record Fase(String nombre, int segundos, boolean donaciones) {

        public Fase {
            nombre = nombre == null || nombre.isBlank() ? "FASE" : nombre.trim();
            segundos = Math.max(0, segundos);
        }

        public boolean infinita() {
            return segundos <= 0;
        }
    }

    private final List<Fase> fases;
    private final boolean ciclico;

    private int indice;
    private long inicioMs;
    private boolean terminado;

    public PhasePlan(List<Fase> fases, boolean ciclico) {
        this.fases = fases == null ? List.of() : List.copyOf(fases);
        this.ciclico = ciclico;
    }

    public static PhasePlan apagado() {
        return new PhasePlan(List.of(), false);
    }

    /** Sin fases no hay formato: el plugin se comporta como siempre. */
    public boolean activo() {
        return !fases.isEmpty() && !terminado;
    }

    public void iniciar(long ahoraMs) {
        indice = 0;
        inicioMs = ahoraMs;
        terminado = fases.isEmpty();
    }

    public Fase actual() {
        return activo() ? fases.get(indice) : null;
    }

    /**
     * La pregunta que hace el dispatcher en cada evento.
     *
     * <p>Con el plan apagado devuelve true, no false: un plan sin configurar no puede dejar
     * el servidor mudo.
     */
    public boolean donacionesActivas() {
        Fase f = actual();
        return f == null || f.donaciones();
    }

    /** Segundos que faltan, o -1 si la fase no tiene límite. */
    public int restantes(long ahoraMs) {
        Fase f = actual();
        if (f == null || f.infinita()) return -1;
        long pasados = Math.max(0, (ahoraMs - inicioMs) / 1000L);
        return (int) Math.max(0, f.segundos() - pasados);
    }

    /** Lo transcurrido de la fase, 0.0 a 1.0. Una fase sin límite se queda en 0. */
    public float fraccion(long ahoraMs) {
        Fase f = actual();
        if (f == null || f.infinita()) return 0f;
        long pasados = Math.max(0, (ahoraMs - inicioMs) / 1000L);
        return Math.min(1f, pasados / (float) f.segundos());
    }

    /**
     * Avanza si a la fase se le acabó el tiempo.
     *
     * @return la fase nueva si acaba de cambiar, o null si no cambió nada. Así quien llama
     *         la anuncia una sola vez, igual que el nivel de caos.
     */
    public Fase avanzarSiToca(long ahoraMs) {
        if (!activo()) return null;

        Fase f = fases.get(indice);
        if (f.infinita() || ahoraMs - inicioMs < f.segundos() * 1000L) return null;

        if (indice + 1 < fases.size()) {
            indice++;
        } else if (ciclico) {
            indice = 0;
        } else {
            terminado = true;
            return null;
        }
        inicioMs = ahoraMs;
        return fases.get(indice);
    }

    /** Salta a la siguiente a mano, para el comando. */
    public Fase siguiente(long ahoraMs) {
        if (fases.isEmpty()) return null;
        terminado = false;
        indice = (indice + 1) % fases.size();
        inicioMs = ahoraMs;
        return fases.get(indice);
    }

    public List<Fase> fases() {
        return new ArrayList<>(fases);
    }

    public boolean ciclico() {
        return ciclico;
    }
}
