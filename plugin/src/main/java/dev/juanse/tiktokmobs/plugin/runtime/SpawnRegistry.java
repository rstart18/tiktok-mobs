package dev.juanse.tiktokmobs.plugin.runtime;

import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Techo de entidades spawneadas, con política FIFO: cuando se llega al límite, el mob más
 * antiguo desaparece para hacer sitio al nuevo.
 *
 * <p>Sin esto, dos horas de live dejan miles de mobs vivos acumulados: el TPS se va al suelo
 * y ya no lo recuperas sin reiniciar. Que el mob viejo muera es un precio aceptable;
 * un servidor a 4 TPS en directo no lo es.
 *
 * <p>El tope es <b>por streamer</b>: con cinco audiencias a la vez, un tope global haría que
 * la audiencia más generosa ahogara a las demás, y el live que no esta donando dejaria de
 * ver sus propios mobs. Cada live tiene su propia cola FIFO y su propio limite.
 *
 * <p>Solo se usa desde el hilo principal, de ahí los ArrayDeque sin sincronizar.
 */
public final class SpawnRegistry {

    private final Map<String, Deque<UUID>> colas = new LinkedHashMap<>();
    private final int max;

    public SpawnRegistry(int max) {
        this.max = Math.max(1, max);
    }

    public void track(String streamerId, Entity entity) {
        if (entity == null) return;
        Deque<UUID> cola = cola(streamerId);
        cola.addLast(entity.getUniqueId());
        evictWhileOverLimit(cola);
    }

    private Deque<UUID> cola(String streamerId) {
        String key = streamerId == null || streamerId.isBlank() ? "main" : streamerId;
        return colas.computeIfAbsent(key, k -> new ArrayDeque<>());
    }

    private void evictWhileOverLimit(Deque<UUID> cola) {
        while (cola.size() > max) {
            UUID oldest = cola.pollFirst();
            if (oldest == null) return;
            Entity entity = Bukkit.getEntity(oldest);
            if (entity != null && entity.isValid()) {
                entity.remove();
            }
        }
    }

    /** Limpia de todas las colas los que ya murieron por su cuenta. */
    public void prune() {
        for (Deque<UUID> cola : colas.values()) {
            cola.removeIf(id -> {
                Entity entity = Bukkit.getEntity(id);
                return entity == null || !entity.isValid();
            });
        }
    }

    /** @return cuántas entidades se eliminaron, sumando todos los lives. */
    public int removeAll() {
        int removed = 0;
        for (Deque<UUID> cola : colas.values()) {
            removed += drain(cola);
        }
        return removed;
    }

    /** Limpia solo lo que vino de ese live: útil cuando una fuente se desconecta. */
    public int removeAll(String streamerId) {
        String key = streamerId == null || streamerId.isBlank() ? "main" : streamerId;
        Deque<UUID> cola = colas.get(key);
        if (cola == null) return 0;
        return drain(cola);
    }

    private int drain(Deque<UUID> cola) {
        int removed = 0;
        UUID id;
        while ((id = cola.pollFirst()) != null) {
            Entity entity = Bukkit.getEntity(id);
            if (entity != null && entity.isValid()) {
                entity.remove();
                removed++;
            }
        }
        return removed;
    }

    /** @return total de entidades vigiladas en todos los lives. */
    public int tracked() {
        int total = 0;
        for (Deque<UUID> cola : colas.values()) total += cola.size();
        return total;
    }

    public int max() {
        return max;
    }
}
