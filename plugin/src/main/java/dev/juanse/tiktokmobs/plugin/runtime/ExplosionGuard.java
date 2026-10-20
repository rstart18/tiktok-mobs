package dev.juanse.tiktokmobs.plugin.runtime;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.plugin.Plugin;

/**
 * Las explosiones de donación no se comen el mundo.
 *
 * <p>Un creeper cargado tiene potencia 6 contra los 3 del normal: el doble de radio y varias
 * veces más bloques. A 5 monedas el regalo, un live deja el mapa convertido en cráteres y no
 * hay forma de deshacerlo. Vaciando la lista de bloques, la explosión se sigue viendo y
 * oyendo igual, y al jugador le hace el mismo daño: lo único que no pasa es el agujero.
 *
 * <p>Es el mismo criterio que el suelo de lava, que usa magma en vez de lava porque la lava
 * fluye y no se deshace. Lo espectacular sí, lo irreversible no.
 *
 * <p>Solo aplica a lo que marcó {@link SpawnMark}. Un creeper que te encuentras minando
 * explota como siempre: esto arregla las donaciones, no cambia las reglas del survival.
 */
public final class ExplosionGuard implements Listener {

    private final SpawnMark mark;

    public ExplosionGuard(Plugin plugin) {
        this.mark = new SpawnMark(plugin);
    }

    /**
     * {@code HIGH} y no {@code MONITOR} porque aquí hay que modificar el evento. Con
     * {@code ignoreCancelled} respetamos a quien ya lo haya cancelado: si una protección de
     * terreno dijo que no, no somos nosotros quien lo reabre.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onExplode(EntityExplodeEvent event) {
        if (!mark.esDeDonacion(event.getEntity())) return;
        event.blockList().clear();
    }
}
