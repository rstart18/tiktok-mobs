package dev.juanse.tiktokmobs.plugin.runtime;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.PlayerDeathEvent;

/**
 * Los dos enganches que necesitan los efectos con duración.
 *
 * <p><b>Taladro:</b> mientras dura, cada bloque que rompes se lleva los 8 de alrededor. Va en
 * {@code MONITOR} con {@code ignoreCancelled}: si otro plugin —una protección de terreno, por
 * ejemplo— cancela la rotura, aquí no llegamos. Un premio de donación no puede ser la forma
 * de saltarse la protección del mundo.
 *
 * <p><b>Ruleta rusa:</b> la muerte que provoca la ruleta no te cuesta el inventario. Es la
 * diferencia entre un evento que se puede mandar veinte veces y uno que te hace cerrar el
 * live la segunda vez que cae.
 */
public final class SpectacleListener implements Listener {

    private final Spectacle spectacle;

    public SpectacleListener(Spectacle spectacle) {
        this.spectacle = spectacle;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        if (!spectacle.estaTaladrando(event.getPlayer())) return;
        spectacle.romperAlrededor(event.getPlayer(), event.getBlock());
    }

    /**
     * Prioridad alta y no MONITOR: aquí sí hay que modificar el evento, y conviene hacerlo
     * antes de que otro plugin dé por hecho que los drops son los que ve.
     */
    @EventHandler(priority = EventPriority.HIGH)
    public void onDeath(PlayerDeathEvent event) {
        if (!spectacle.esMuertePorRuleta(event.getEntity())) return;

        event.setKeepInventory(true);
        event.setKeepLevel(true);
        event.getDrops().clear();
        event.setDroppedExp(0);
    }
}
