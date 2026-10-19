package dev.juanse.tiktokmobs.plugin.runtime;

import dev.juanse.tiktokmobs.core.action.ActionSpec;
import dev.juanse.tiktokmobs.core.action.ResolvedAction;
import dev.juanse.tiktokmobs.core.boss.BossDefinition;
import dev.juanse.tiktokmobs.plugin.boss.BossManager;
import dev.juanse.tiktokmobs.plugin.config.PluginSettings;
import dev.juanse.tiktokmobs.plugin.overlay.OverlayFeed;
import dev.juanse.tiktokmobs.plugin.ui.BossTracker;
import dev.juanse.tiktokmobs.plugin.ui.TitleFeed;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.Locale;

/** Traduce ActionSpec a llamadas de Bukkit. Todo lo que hay aquí corre en el hilo principal. */
public final class BukkitActionExecutor implements ActionExecutor {

    /** Una hora en ticks: suficiente para un live y sin depender de INFINITE_DURATION. */
    private static final int LONG_DURATION_TICKS = 20 * 60 * 60;


    private final JavaPlugin plugin;
    private final TargetResolver targets;
    private final SpawnRegistry registry;
    private final BossTracker bossTracker;
    private final BossManager bosses;
    private final OverlayFeed overlay;

    private volatile PluginSettings settings;
    private volatile TitleFeed titles;
    private volatile boolean degraded;

    public BukkitActionExecutor(JavaPlugin plugin,
                                TargetResolver targets,
                                SpawnRegistry registry,
                                BossTracker bossTracker,
                                BossManager bosses,
                                OverlayFeed overlay,
                                PluginSettings settings) {
        this.plugin = plugin;
        this.targets = targets;
        this.registry = registry;
        this.bossTracker = bossTracker;
        this.bosses = bosses;
        this.overlay = overlay == null ? OverlayFeed.DISABLED : overlay;
        this.settings = settings;
        this.titles = new TitleFeed(settings.titleFeed(), settings.titleGapMs());
    }

    public void setSettings(PluginSettings settings) {
        this.settings = settings;
        this.titles = new TitleFeed(settings.titleFeed(), settings.titleGapMs());
    }

    @Override
    public void setDegraded(boolean degraded) {
        this.degraded = degraded;
    }

    @Override
    public void execute(ResolvedAction action) {
        Location at = targets.resolve(action.streamerId());
        if (at == null || at.getWorld() == null) return;

        // Guardia de TPS: anunciamos la donación pero no pagamos su coste.
        // El overlay igual lo muestra: el viewer que donó tiene que ver su nombre en pantalla
        // aunque el servidor vaya justo. Lo que no se hace es spawnear.
        if (degraded && isExpensive(action.spec())) {
            announce(action, " &8(servidor cargado: solo aviso)");
            overlay.publish(action, Messages.describeAction(action), true);
            return;
        }

        // El jefe se anuncia solo: sus títulos y mensajes los manda el BossManager, que sabe
        // si está naciendo, enfureciéndose o encolado. Un aviso genérico encima sobraría.
        boolean selfAnnounced = false;

        switch (action.spec().kind()) {
            case SPAWN, HORDE -> spawnMobs(action, at);
            case BOSS -> {
                if (action.spec().isSuperBoss()) {
                    if (!superBoss(action, at)) return;
                    selfAnnounced = true;
                } else {
                    spawnBoss(action, at);
                }
            }
            case EFFECT -> applyEffect(action);
            case WORLD_EVENT -> {
                if (!runWorldEvent(action, at)) return;
            }
            case GIVE -> {
                if (!giveItem(action)) return;
            }
            case NONE -> {
                return;
            }
        }

        if (!selfAnnounced) {
            announce(action, "");
            // El jefe ya saca sus propios títulos: dos encima no caben en pantalla.
            titles.show(action.donorLabel(), Messages.describeAction(action));
        }
        overlay.publish(action, Messages.describeAction(action), false);
    }

    /**
     * Jefe del bloque {@code bosses:}.
     *
     * <p>El sitio se resuelve <b>aquí</b>, antes del suspenso, y se le pasa ya hecho: si se
     * revalidara al final de los 3 segundos el jefe podría salir en otro punto y las partículas
     * se quedarían marcando el sitio equivocado.
     */
    private boolean superBoss(ResolvedAction action, Location at) {
        BossDefinition definition = settings.bosses().get(action.spec().bossId());
        if (definition == null) {
            plugin.getLogger().warning("[config] la acción apunta a boss: '"
                    + action.spec().bossId() + "' pero no hay ningún jefe con ese id en 'bosses:'");
            return false;
        }

        Location where = SafeSpawn.findTall(at, Math.max(action.spec().radius(), 5));
        bosses.request(definition, where, action.donorLabel(), action.coins());
        return true;
    }

    /**
     * Techo de repeticiones de un evento de mundo en una sola donación.
     *
     * <p>Los mobs ya tienen su propio tope y además se pueden despawnear. Un evento de mundo
     * no: 40 rondas de lluvia de TNT no se pueden deshacer, y 40 teletransportes al cielo
     * seguidos no son 40 veces más divertidos, son un bloqueo.
     */
    private static final int MAX_WORLD_EVENT_REPEATS = 10;

    /**
     * Los eventos que NO tiene sentido repetir: se ejecutan una sola vez por donación,
     * por mucho que acumule.
     *
     * <p>water_drop ya te deja en el cielo; repetirlo en el mismo tick te deja en el mismo
     * sitio con baldes de sobra. El jefe tiene su propia acumulación, que es enfurecerse.
     */
    private static final java.util.Set<String> SOLO_UNA_VEZ =
            java.util.Set.of("water_drop", "night", "day", "thunder",
                    // Un tiro por donación. Acumulando, 5 regalos serían 5 tiros y un 60%
                    // de morir: eso deja de ser suspenso y pasa a ser una sentencia.
                    "ruleta_rusa",
                    // Te manda al Nether una vez; repetirlo en el mismo tick no hace nada.
                    "nether",
                    "taladro");

    /**
     * Ejecuta el evento de mundo tantas veces como la donación dé de sí.
     *
     * <p>Antes esto corría UNA vez pasara lo que pasara, así que 10 donas daban los mismos
     * 3 rayos que una. Para el que donó eso se ve exactamente igual que si no hubiera
     * funcionado, y es la queja más repetida del proyecto.
     */
    private boolean runWorldEvent(ResolvedAction action, Location at) {
        String id = action.spec().worldEventId();

        int veces = SOLO_UNA_VEZ.contains(id.toLowerCase(Locale.ROOT))
                ? 1
                : Math.min(action.spec().effectiveCount(action.coins()), MAX_WORLD_EVENT_REPEATS);

        Player objetivo = targets.resolvePlayer();

        for (int i = 0; i < veces; i++) {
            if (!WorldEvents.run(id, at, registry, objetivo, action.streamerId())) {
                plugin.getLogger().warning("evento de mundo desconocido: '" + id
                        + "'. Disponibles: " + WorldEvents.AVAILABLE);
                return false;
            }
        }
        return true;
    }

    /** Da el item al jugador objetivo; si no le cabe, se lo deja a los pies. */
    private boolean giveItem(ResolvedAction action) {
        Material material;
        try {
            material = Material.valueOf(action.spec().item().trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            plugin.getLogger().warning("item desconocido en el config: '" + action.spec().item() + "'");
            return false;
        }

        Player player = targets.resolvePlayer();
        if (player == null) return false;

        int amount = Math.min(action.spec().effectiveCount(action.coins()), 64);
        ItemStack stack = new ItemStack(material, amount);

        // Lo que no entra en el inventario se cae al suelo en vez de perderse:
        // el donante pago por ello y tiene que verlo.
        player.getInventory().addItem(stack).values()
                .forEach(sobra -> player.getWorld().dropItemNaturally(player.getLocation(), sobra));
        return true;
    }

    /**
     * Qué acciones se degradan a un simple aviso cuando el TPS está por el suelo.
     *
     * <p>El jefe del bloque {@code bosses:} queda fuera a propósito: es la compra más cara del
     * catálogo y no puede acabar en un mensaje de chat. Se encarga él de encolarse y salir
     * cuando el servidor recupere, que es lo que el BossManager hace con su propia cola.
     */
    private boolean isExpensive(ActionSpec spec) {
        return switch (spec.kind()) {
            case HORDE -> true;
            case BOSS -> !spec.isSuperBoss();
            case SPAWN -> spec.count() > 3 || spec.perCoin() > 0;
            default -> false;
        };
    }

    /** El multiplicador del nivel de caos. Lo pone el plugin al arrancar. */
    private volatile java.util.function.IntUnaryOperator chaos = cantidad -> cantidad;

    public void setChaos(java.util.function.IntUnaryOperator chaos) {
        this.chaos = chaos == null ? cantidad -> cantidad : chaos;
    }

    private void spawnMobs(ResolvedAction action, Location at) {
        ActionSpec spec = action.spec();
        EntityType type = entityType(spec.mob());
        if (type == null) return;

        // Proporcional a la donacion cuando el tramo lo pide, con suelo y techo, y encima
        // el nivel de caos, que es lo que hace que el live escale solo segun avanza.
        int count = Math.min(
                chaos.applyAsInt(spec.effectiveCount(action.coins())),
                ActionSpec.HARD_MAX_COUNT);

        // Bloques ya usados en este lote: dos mobs en el mismo bloque empiezan a
        // hacerse dano por apelotonamiento, y en una mina es facil que todos los
        // candidatos colapsen en el mismo sitio.
        java.util.Set<Long> ocupados = new java.util.HashSet<>();

        for (int i = 0; i < count; i++) {
            Location where = safeSpawn(at, spec.radius(), i, count, ocupados);
            Entity entity = at.getWorld().spawnEntity(where, type);
            if (spec.nameplate()) {
                nameplate(entity, action.donorLabel());
            }
            registry.track(action.streamerId(), entity);
        }
    }

    private void spawnBoss(ResolvedAction action, Location at) {
        ActionSpec spec = action.spec();
        EntityType type = entityType(spec.mob());
        if (type == null) return;

        Entity entity = at.getWorld().spawnEntity(safeSpawn(at, Math.max(spec.radius(), 4), 0, 1), type);
        entity.setGlowing(true);
        nameplate(entity, "Jefe de " + action.donorLabel());
        registry.track(action.streamerId(), entity);

        if (!(entity instanceof LivingEntity living)) return;

        // Recién spawneado, getHealth() == vida máxima: lo usamos como línea base
        // en vez de la API de atributos, cuyo enum cambió de nombre entre 1.21.x.
        double baseline = living.getHealth();
        int amplifier = healthBoostAmplifier(baseline, spec.healthScale());

        if (amplifier >= 0) {
            living.addPotionEffect(new PotionEffect(
                    PotionEffectType.HEALTH_BOOST, LONG_DURATION_TICKS, amplifier, false, false));
            // HEALTH_BOOST sube el máximo pero no cura: hay que rellenar al tick siguiente,
            // cuando el modificador ya está aplicado.
            double extra = (amplifier + 1) * 4.0;
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (!living.isValid()) return;
                try {
                    living.setHealth(living.getHealth() + extra);
                } catch (IllegalArgumentException ignored) {
                    // La vida ya estaba al máximo: nada que hacer.
                }
                bossTracker.track(living, "Jefe de " + action.donorLabel());
            });
        } else {
            bossTracker.track(living, "Jefe de " + action.donorLabel());
        }
    }

    /** HEALTH_BOOST da +4 de vida por nivel. Devuelve -1 si no hay que escalar nada. */
    private int healthBoostAmplifier(double baseline, double scale) {
        if (scale <= 1.0 || baseline <= 0) return -1;
        double extra = baseline * (scale - 1.0);
        int amplifier = (int) Math.round(extra / 4.0) - 1;
        return Math.max(0, Math.min(amplifier, 120));
    }

    private void applyEffect(ResolvedAction action) {
        PotionEffectType type = potionEffect(action.spec().effect());
        if (type == null) {
            plugin.getLogger().warning("efecto de poción desconocido: '" + action.spec().effect() + "'");
            return;
        }
        int ticks = Math.max(20, action.spec().seconds() * 20);
        PotionEffect effect = new PotionEffect(type, ticks, 0, true, true);

        for (Player player : Bukkit.getOnlinePlayers()) {
            player.addPotionEffect(effect);
        }
    }

    private void nameplate(Entity entity, String label) {
        // label ya pasó por NameSanitizer en el core: sin códigos de color, sin saltos de línea.
        entity.customName(Component.text(label));
        entity.setCustomNameVisible(true);
    }

    /**
     * Busca un sitio donde el mob quepa de verdad.
     *
     * <p>La lógica vive en {@link SafeSpawn} porque la comparten los mobs normales y el jefe
     * con sus esbirros. Duplicarla es cómo volvimos a spawnear dentro de la piedra en la mina.
     */
    private Location safeSpawn(Location center, int radius, int index, int total) {
        return SafeSpawn.find(center, radius, index, total);
    }

    private Location safeSpawn(Location center, int radius, int index, int total,
                               java.util.Set<Long> ocupados) {
        return SafeSpawn.find(center, radius, index, total, ocupados);
    }

    private EntityType entityType(String raw) {
        try {
            return EntityType.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            plugin.getLogger().warning("mob desconocido en el config: '" + raw + "'");
            return null;
        }
    }

    @SuppressWarnings("deprecation")
    private PotionEffectType potionEffect(String raw) {
        if (raw == null || raw.isBlank()) return null;
        return PotionEffectType.getByName(raw.trim().toUpperCase(Locale.ROOT));
    }

    private void announce(ResolvedAction action, String suffix) {
        if (!settings.broadcast()) return;

        Component message = action.spec().message().isBlank()
                ? Messages.defaultAnnounce(action)
                : Messages.render(action.spec().message(), action);

        if (!suffix.isBlank()) {
            message = message.append(Messages.color(suffix));
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            player.sendMessage(message);
        }
    }
}
