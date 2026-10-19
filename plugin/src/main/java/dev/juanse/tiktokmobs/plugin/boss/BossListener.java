package dev.juanse.tiktokmobs.plugin.boss;

import dev.juanse.tiktokmobs.core.boss.BossTitle;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.title.Title;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.event.world.EntitiesUnloadEvent;

import java.time.Duration;
import java.util.List;

/**
 * Los cuatro momentos en los que el jefe deja de estar donde creíamos.
 *
 * <p>El importante de verdad es {@link #onPlayerDeath}: que al morir te salga de quién fue el
 * regalo es lo que convierte la muerte en contenido. Y para que funcione hay que seguir la
 * cadena hasta el responsable, porque lo que te mata casi nunca es el jefe en sí: es su flecha
 * o uno de sus esbirros.
 */
public final class BossListener implements Listener {

    private final BossManager bosses;
    private final BossPdc pdc;

    public BossListener(BossManager bosses, BossPdc pdc) {
        this.bosses = bosses;
        this.pdc = pdc;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityDeath(EntityDeathEvent event) {
        if (pdc.isBoss(event.getEntity())) {
            bosses.onDeath(event.getEntity());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        List<String> donors = bosses.attributionOf(responsible(player));
        if (donors.isEmpty()) return;

        player.showTitle(Title.title(
                Component.text("HAS MUERTO", NamedTextColor.DARK_RED),
                Component.text("cortesía de " + BossTitle.join(donors), NamedTextColor.GRAY),
                Title.Times.times(Duration.ofMillis(200), Duration.ofSeconds(3),
                        Duration.ofMillis(600))));
    }

    /**
     * Quién te mató de verdad.
     *
     * <p>Una flecha o una bola de fuego son entidades propias y no llevan atribución, así que
     * sin resolver el {@code shooter} el título no saldría nunca en un jefe que dispara.
     */
    private Entity responsible(Player player) {
        EntityDamageEvent cause = player.getLastDamageCause();
        if (!(cause instanceof EntityDamageByEntityEvent byEntity)) return null;

        Entity damager = byEntity.getDamager();
        if (damager instanceof Projectile projectile
                && projectile.getShooter() instanceof Entity shooter) {
            return shooter;
        }
        return damager;
    }

    /** Chunk descargado: hay que soltar la barra y parar las tareas, o quedan colgadas. */
    @EventHandler
    public void onEntitiesUnload(EntitiesUnloadEvent event) {
        for (Entity entity : event.getEntities()) {
            if (pdc.isBoss(entity)) bosses.onUnload(entity);
        }
    }

    /** Y al volver a cargarse, el jefe recupera su barra y sus fases (que viven en la PDC). */
    @EventHandler
    public void onEntitiesLoad(EntitiesLoadEvent event) {
        for (Entity entity : event.getEntities()) {
            if (pdc.isBoss(entity)) bosses.adopt(entity);
        }
    }
}
