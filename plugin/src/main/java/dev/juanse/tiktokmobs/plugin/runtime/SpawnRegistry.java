package dev.juanse.tiktokmobs.plugin.runtime;

import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.UUID;

/**
 * Techo global de entidades spawneadas, con política FIFO: cuando se llega al límite,
 * el mob más antiguo desaparece para hacer sitio al nuevo.
 *
 * <p>Sin esto, dos horas de live dejan miles de mobs vivos acumulados: el TPS se va al suelo
 * y ya no lo recuperas sin reiniciar. Que el mob viejo muera es un precio aceptable;
 * un servidor a 4 TPS en directo no lo es.
 *
 * <p>Solo se usa desde el hilo principal, de ahí el ArrayDeque sin sincronizar.
 */
public final class SpawnRegistry {

    private final Deque<UUID> order = new ArrayDeque<>();
    private final int max;

    public SpawnRegistry(int max) {
        this.max = Math.max(1, max);
    }

    public void track(Entity entity) {
        if (entity == null) return;
        order.addLast(entity.getUniqueId());
        evictWhileOverLimit();
    }

    private void evictWhileOverLimit() {
        while (order.size() > max) {
            UUID oldest = order.pollFirst();
            if (oldest == null) return;
            Entity entity = Bukkit.getEntity(oldest);
            if (entity != null && entity.isValid()) {
                entity.remove();
            }
        }
    }

    /** Limpia del registro los que ya murieron por su cuenta. */
    public void prune() {
        order.removeIf(id -> {
            Entity entity = Bukkit.getEntity(id);
            return entity == null || !entity.isValid();
        });
    }

    /** @return cuántas entidades se eliminaron. */
    public int removeAll() {
        int removed = 0;
        UUID id;
        while ((id = order.pollFirst()) != null) {
            Entity entity = Bukkit.getEntity(id);
            if (entity != null && entity.isValid()) {
                entity.remove();
                removed++;
            }
        }
        return removed;
    }

    public int tracked() {
        return order.size();
    }

    public int max() {
        return max;
    }
}
