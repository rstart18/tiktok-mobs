package dev.juanse.tiktokmobs.plugin.runtime;

import dev.juanse.tiktokmobs.core.action.ResolvedAction;

/** Puerto de salida: ejecuta una acción en el mundo. Siempre en el hilo principal. */
public interface ActionExecutor {

    void execute(ResolvedAction action);

    /** Cuando el TPS cae, el dispatcher pide modo degradado: avisar sin spawnear. */
    void setDegraded(boolean degraded);

    /**
     * Dispara un evento de mundo que no viene de una donacion: hoy, el premio de la meta de
     * likes. Por defecto no hace nada, para que un ejecutor de pruebas no tenga que
     * implementarlo solo por existir.
     */
    default void runWorldEvent(String eventId, String streamerId) {
        // sin efecto
    }
}
