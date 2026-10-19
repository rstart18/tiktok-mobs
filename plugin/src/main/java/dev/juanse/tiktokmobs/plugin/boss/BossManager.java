package dev.juanse.tiktokmobs.plugin.boss;

import dev.juanse.tiktokmobs.core.boss.BossDefinition;
import dev.juanse.tiktokmobs.core.boss.BossTitle;
import dev.juanse.tiktokmobs.plugin.runtime.SafeSpawn;
import dev.juanse.tiktokmobs.plugin.runtime.SpawnRegistry;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.World;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.function.BooleanSupplier;

/**
 * El jefe: uno a la vez <b>por streamer</b>, cada uno dueño de su propio estado.
 *
 * <p>Reglas que vienen del diseño y que esta clase hace cumplir:
 * <ul>
 *   <li><b>Fuera del tope normal.</b> El jefe no entra en {@link SpawnRegistry}, así que no puede
 *       ser evictado a mitad de pelea por una horda que llegue después. Sus esbirros sí entran,
 *       en la cola del live que lo invocó.</li>
 *   <li><b>Enfurecer, no encolar.</b> Otra donación de jefe sobre el mismo live mientras hay uno
 *       vivo le suma vida y añade al donante al nombre. Dos jefes del mismo live a la vez no es
 *       más espectáculo, es lag; dos jefes de lives distintos sí conviven, cada uno con su barra.</li>
 *   <li><b>Encolar si el servidor va ahogado.</b> El jefe es la compra más cara del catálogo:
 *       no se puede quedar en un mensaje de chat. Cada live tiene su propia cola.</li>
 *   <li><b>Atribución en la entidad.</b> Donantes y streamerId van en la PDC, no en memoria, así
 *       sobrevive a reinicios y a chunks que se descargan.</li>
 * </ul>
 *
 * <p>Todo lo de aquí corre en el hilo principal: lo llama el executor, que ya está dentro del
 * tick. Las tareas repetitivas se cancelan en la muerte, en el despawn, al descargarse el chunk
 * y en {@code onDisable}.
 */
public final class BossManager {

    /** Bits de fase, guardados en la PDC para que un chunk recargado no las repita. */
    private static final int PHASE_MINIONS = 1;
    private static final int PHASE_RAGE = 2;

    /** Cada cuánto se refresca barra y fases. Igual que el BossTracker de siempre. */
    private static final long TICK_PERIOD = 10L;

    /** Si el servidor no recupera en este tiempo, el jefe encolado se cancela y se avisa. */
    private static final long PENDING_TIMEOUT_MS = 120_000L;

    private static final Random RANDOM = new Random();

    public enum Outcome {
        /** Suspenso arrancado: el jefe sale en unos segundos. */
        SUMMONING,
        /** Ya había uno vivo <b>en ese live</b>: se le sumó vida y el donante al nombre. */
        ENRAGED,
        /** Servidor ahogado: guardado para cuando recupere. */
        QUEUED
    }

    /** Un jefe esperando a que el TPS recupere. */
    private record Pending(BossDefinition definition, Location where,
                           List<String> donors, int coins, long since) {
    }

    /**
     * El estado de un live: su jefe vivo (si lo hay), su suspenso en curso (si lo hay) y su
     * cola por TPS (si la hay). Si todos los campos están limpios, el slot se puede borrar.
     */
    private static final class Slot {
        final String streamerId;
        UUID activeId;
        BossDefinition activeDefinition;
        BossBar bar;
        BukkitTask bossTask;
        double maxHp;
        double vanillaMax;
        int totalCoins;
        List<String> donors = List.of();
        int ticks;

        BukkitTask summonTask;
        BossDefinition summoningDefinition;
        List<String> summoningDonors = List.of();
        int summoningCoins;

        Pending pending;

        Slot(String streamerId) {
            this.streamerId = streamerId;
        }

        boolean isEmpty() {
            return activeId == null && summonTask == null && pending == null;
        }
    }

    private final JavaPlugin plugin;
    private final SpawnRegistry registry;
    private final BossPdc pdc;
    private final BooleanSupplier degraded;
    /** id del config -> definición. Hace falta para readoptar leyendo el id de la PDC. */
    private final java.util.function.Function<String, BossDefinition> lookup;
    private BossModelBridge models = BossModelBridge.NONE;

    /** Un slot por live: capacidad 1 por streamer. Clave "main" en el escenario monousuario. */
    private final Map<String, Slot> slots = new LinkedHashMap<>();
    private BukkitTask pendingTask;

    public BossManager(JavaPlugin plugin, SpawnRegistry registry, BossPdc pdc,
                       BooleanSupplier degraded,
                       java.util.function.Function<String, BossDefinition> lookup) {
        this.plugin = plugin;
        this.registry = registry;
        this.pdc = pdc;
        this.degraded = degraded == null ? () -> false : degraded;
        this.lookup = lookup == null ? id -> null : lookup;
    }

    public void start() {
        stopAllTasks();
        models = BossModelBridge.detect(plugin.getLogger());

        // Un /reload no debería dejar al jefe sin barra: se readopta lo que ya esté vivo.
        adoptLoaded();

        pendingTask = Bukkit.getScheduler().runTaskTimer(plugin, this::drainPending, 40L, 20L);
    }

    /** Cancela todo y suelta las barras. Los jefes se quedan vivos: su estado está en la PDC. */
    public void stop() {
        for (Slot slot : slots.values()) {
            cancelTasks(slot);
            hideBar(slot);
            clearActive(slot);
        }
        if (pendingTask != null) {
            pendingTask.cancel();
            pendingTask = null;
        }
    }

    private void stopAllTasks() {
        for (Slot slot : slots.values()) cancelTasks(slot);
        if (pendingTask != null) {
            pendingTask.cancel();
            pendingTask = null;
        }
    }

    private void cancelTasks(Slot slot) {
        if (slot.bossTask != null) {
            slot.bossTask.cancel();
            slot.bossTask = null;
        }
        if (slot.summonTask != null) {
            slot.summonTask.cancel();
            slot.summonTask = null;
        }
    }

    private Slot slot(String streamerId) {
        String key = streamerId == null || streamerId.isBlank() ? "main" : streamerId;
        return slots.computeIfAbsent(key, Slot::new);
    }

    // ------------------------------------------------------------------ entrada

    /**
     * Lo único que llama el executor.
     *
     * @param at sitio YA validado por el buscador. No se vuelve a buscar: si se revalidara al
     *           final del suspenso, el jefe podría salir en otro punto y las partículas
     *           quedarían en el sitio equivocado.
     * @param streamerId el live que invoca al jefe. Cada live tiene su propio slot de un jefe.
     */
    public Outcome request(BossDefinition definition, Location at, String donor, int coins,
                           String streamerId) {
        Slot slot = slot(streamerId);
        LivingEntity alive = activeEntity(slot);

        if (alive != null) {
            enrage(slot, alive, donor, coins);
            return Outcome.ENRAGED;
        }

        // Suspenso ya en marcha en ese live: el segundo donante se suma al que está naciendo.
        if (slot.summonTask != null) {
            slot.summoningDonors = BossTitle.add(slot.summoningDonors, donor);
            slot.summoningCoins += Math.max(0, coins);
            Fx.burst(at);
            Fx.sound(at, Fx.SOUND_ENRAGE, 1.0f, 1.4f);
            return Outcome.ENRAGED;
        }

        if (degraded.getAsBoolean()) {
            queue(slot, definition, at, donor, coins);
            return Outcome.QUEUED;
        }

        beginSummon(slot, definition, at, BossTitle.add(List.of(), donor), Math.max(0, coins));
        return Outcome.SUMMONING;
    }

    private void queue(Slot slot, BossDefinition definition, Location at, String donor, int coins) {
        if (slot.pending != null && slot.pending.definition().id().equals(definition.id())) {
            slot.pending = new Pending(definition, slot.pending.where(),
                    BossTitle.add(slot.pending.donors(), donor),
                    slot.pending.coins() + Math.max(0, coins), slot.pending.since());
        } else {
            slot.pending = new Pending(definition, at.clone(),
                    BossTitle.add(List.of(), donor), Math.max(0, coins), System.currentTimeMillis());
        }

        broadcast("&e[Jefe] &fel servidor va cargado: el jefe de &e"
                + BossTitle.join(slot.pending.donors()) + " &fsale en cuanto recupere.");
    }

    /** Se ejecuta cada segundo: suelta los jefes encolados cuando el TPS vuelve a su sitio. */
    private void drainPending() {
        for (Slot slot : slots.values()) {
            if (slot.pending == null) continue;

            if (System.currentTimeMillis() - slot.pending.since() > PENDING_TIMEOUT_MS) {
                broadcast("&c[Jefe] el servidor no recuperó: se cancela el jefe de &f"
                        + BossTitle.join(slot.pending.donors())
                        + "&c. Usa &f/ttmobs testboss&c para soltarlo a mano.");
                plugin.getLogger().warning("[boss] jefe encolado cancelado por timeout: "
                        + BossTitle.join(slot.pending.donors())
                        + " (" + slot.pending.coins() + " monedas)");
                slot.pending = null;
                continue;
            }

            if (degraded.getAsBoolean() || activeEntity(slot) != null || slot.summonTask != null) {
                continue;
            }

            Pending ready = slot.pending;
            slot.pending = null;
            beginSummon(slot, ready.definition(), ready.where(), ready.donors(), ready.coins());
        }
    }

    // ------------------------------------------------------------------ suspenso

    private void beginSummon(Slot slot, BossDefinition definition, Location at,
                             List<String> donorNames, int coins) {
        slot.summoningDefinition = definition;
        slot.summoningDonors = donorNames;
        slot.summoningCoins = coins;

        String who = BossTitle.join(donorNames);
        showTitle(Component.text("⚔ " + who + " invoca un jefe", NamedTextColor.DARK_RED),
                Component.text("prepárate", NamedTextColor.GRAY));
        broadcast("&4&l⚔ &c" + who + " &finvoca un jefe...");
        Fx.sound(at, Fx.SOUND_SUMMON, 1.0f, 0.8f);

        int total = Math.max(20, definition.suspenseTicks());
        final int[] elapsed = {0};

        slot.summonTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            elapsed[0] += 4;

            double progress = Math.min(1.0, elapsed[0] / (double) total);
            Fx.ring(at, 2.5 - progress * 1.5, 16);
            Fx.pillar(at, 1.0 + progress * 2.0);
            Fx.sound(at, Fx.SOUND_TICK, 0.8f, 0.6f + (float) progress);

            if (elapsed[0] < total) return;

            if (slot.summonTask != null) {
                slot.summonTask.cancel();
                slot.summonTask = null;
            }
            spawn(slot, slot.summoningDefinition, at, slot.summoningDonors, slot.summoningCoins);
            slot.summoningDefinition = null;
            slot.summoningDonors = List.of();
            slot.summoningCoins = 0;
        }, 4L, 4L);
    }

    // ------------------------------------------------------------------ spawn

    private void spawn(Slot slot, BossDefinition definition, Location at,
                       List<String> donorNames, int coins) {
        World world = at.getWorld();
        if (world == null) return;

        EntityType type = entityType(definition.baseEntity());
        if (type == null) {
            plugin.getLogger().warning("[boss] base-entity desconocida: '"
                    + definition.baseEntity() + "'. No se invoca nada.");
            return;
        }

        Entity entity = world.spawnEntity(SafeSpawn.findTall(at, 2), type);
        if (!(entity instanceof LivingEntity living)) {
            entity.remove();
            plugin.getLogger().warning("[boss] base-entity '" + definition.baseEntity()
                    + "' no es un ser vivo: no puede ser un jefe.");
            return;
        }

        // Recién spawneado, getHealth() es la vida máxima vanilla: es la línea base del plan B.
        slot.vanillaMax = living.getHealth();

        living.setPersistent(true);
        living.setGlowing(true);
        living.setRemoveWhenFarAway(false);
        if (living instanceof Mob mob) {
            mob.setTarget(nearestPlayer(living.getLocation(), 32));
        }

        slot.activeId = living.getUniqueId();
        slot.activeDefinition = definition;
        slot.donors = donorNames;
        slot.totalCoins = coins;
        slot.ticks = 0;

        slot.maxHp = BossHealth.setMax(living, definition.hpFor(coins), slot.vanillaMax);
        BossHealth.heal(living, slot.maxHp, slot.maxHp);

        pdc.markBoss(living, definition.id(), slot.donors, slot.totalCoins, slot.maxHp,
                slot.streamerId);
        equip(living, definition, coins);
        nameplate(slot, living);

        // IMPORTANTE: nada de registry.track(living). El jefe va fuera del tope normal.
        if (definition.hasModel() && !models.mount(living, definition.modelId())) {
            plugin.getLogger().info("[boss] sin modelo: el jefe sale vanilla equipado "
                    + "(es también lo que ven los jugadores de Bedrock).");
        }

        showBar(slot, living);
        startBossTask(slot);

        broadcast("&4&l⚔ &c" + name(slot) + " &cha aparecido &7(" + Math.round(slot.maxHp)
                + " de vida)");
        Fx.burst(living.getLocation());
        Fx.sound(living.getLocation(), Fx.SOUND_SUMMON, 1.0f, 1.0f);
    }

    /** Readopta un jefe que ya existía: tras /reload o cuando su chunk vuelve a cargarse. */
    public void adopt(Entity entity) {
        if (!(entity instanceof LivingEntity living) || !pdc.isBoss(living)) return;

        String streamerId = pdc.streamerId(living);
        Slot slot = slot(streamerId);
        if (slot.activeId != null) return;

        BossDefinition definition = lookup.apply(pdc.definitionId(living));
        if (definition == null) {
            plugin.getLogger().warning("[boss] hay un jefe '" + pdc.definitionId(living)
                    + "' vivo pero ya no está en el config: se queda sin barra ni fases.");
            return;
        }

        slot.activeId = living.getUniqueId();
        slot.activeDefinition = definition;
        slot.donors = pdc.donors(living);
        slot.totalCoins = pdc.coins(living);
        slot.maxHp = pdc.maxHp(living) > 0 ? pdc.maxHp(living) : definition.hpFor(slot.totalCoins);
        slot.vanillaMax = 0;
        slot.ticks = 0;

        nameplate(slot, living);
        showBar(slot, living);
        startBossTask(slot);
        plugin.getLogger().info("[boss] readoptado el jefe de " + BossTitle.join(slot.donors)
                + " (live '" + slot.streamerId + "')");
    }

    private void adoptLoaded() {
        for (World world : Bukkit.getWorlds()) {
            for (LivingEntity living : world.getLivingEntities()) {
                if (pdc.isBoss(living)) adopt(living);
            }
        }
    }

    // ------------------------------------------------------------------ enfurecer

    private void enrage(Slot slot, LivingEntity living, String donor, int coins) {
        slot.donors = BossTitle.add(slot.donors, donor);
        slot.totalCoins += Math.max(0, coins);

        double before = slot.maxHp;
        slot.maxHp = BossHealth.setMax(living,
                slot.activeDefinition.hpAfterEnrage(slot.maxHp, coins), slot.vanillaMax);
        BossHealth.heal(living, slot.maxHp - before, slot.maxHp);

        pdc.setDonors(living, slot.donors);
        pdc.setCoins(living, slot.totalCoins);
        pdc.setMaxHp(living, slot.maxHp);
        nameplate(slot, living);
        updateBar(slot, living);

        showTitle(Component.text("⚔ el jefe se enfurece", NamedTextColor.RED),
                Component.text(donor + " suma " + Math.round(slot.maxHp - before) + " de vida",
                        NamedTextColor.GRAY));
        broadcast("&c&l⚔ &f" + donor + " &cenfurece al jefe &7(+"
                + Math.round(slot.maxHp - before) + " de vida, ahora " + Math.round(slot.maxHp) + ")");

        Fx.burst(living.getLocation());
        Fx.sound(living.getLocation(), Fx.SOUND_ENRAGE, 1.0f, 0.9f);
    }

    // ------------------------------------------------------------------ bucle

    private void startBossTask(Slot slot) {
        if (slot.bossTask != null) slot.bossTask.cancel();
        slot.bossTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> tick(slot),
                TICK_PERIOD, TICK_PERIOD);
    }

    private void tick(Slot slot) {
        LivingEntity living = activeEntity(slot);
        if (living == null) {
            // Muerto, despawneado o chunk descargado: se limpia solo, sin esperar al evento.
            cleanup(slot);
            return;
        }

        slot.ticks++;
        updateBar(slot, living);

        double fraction = slot.maxHp <= 0 ? 1.0 : living.getHealth() / slot.maxHp;

        if (fraction <= slot.activeDefinition.minionsAt()
                && !pdc.phaseFired(living, PHASE_MINIONS)) {
            pdc.markPhase(living, PHASE_MINIONS);
            summonMinions(slot, living);
        }

        if (fraction <= slot.activeDefinition.rageAt() && !pdc.phaseFired(living, PHASE_RAGE)) {
            pdc.markPhase(living, PHASE_RAGE);
            enterRage(slot, living);
        }

        // Ataque extra de la fase de furia: un salto telegrafiado cada 6 segundos.
        if (pdc.phaseFired(living, PHASE_RAGE) && slot.ticks % 12 == 0) {
            leap(slot, living);
        }
    }

    private void summonMinions(Slot slot, LivingEntity boss) {
        broadcast("&6&l⚔ &eel jefe llama a los suyos");
        Fx.burst(boss.getLocation());
        Fx.sound(boss.getLocation(), Fx.SOUND_PHASE, 1.0f, 0.7f);

        // Con el servidor ahogado la fase se anuncia igual pero no se paga: los esbirros son
        // justo lo que lo hundiría más.
        if (degraded.getAsBoolean()) {
            broadcast("&7(servidor cargado: esta vez sin esbirros)");
            return;
        }

        EntityType type = entityType(slot.activeDefinition.minionEntity());
        if (type == null) return;

        int min = slot.activeDefinition.minionsMin();
        int max = slot.activeDefinition.minionsMax();
        int count = min + (max > min ? RANDOM.nextInt(max - min + 1) : 0);
        if (count <= 0) return;

        java.util.Set<Long> ocupados = new java.util.HashSet<>();
        String label = BossTitle.join(slot.donors);
        Player target = nearestPlayer(boss.getLocation(), 32);

        for (int i = 0; i < count; i++) {
            Location where = SafeSpawn.find(boss.getLocation(), 3, i, count, ocupados);
            Entity minion = boss.getWorld().spawnEntity(where, type);

            minion.customName(Component.text("esbirro de " + label));
            minion.setCustomNameVisible(true);
            pdc.markMinion(minion, slot.donors);

            if (minion instanceof Mob mob && target != null) mob.setTarget(target);

            // Los esbirros SÍ van al tope normal, en la cola del live que invoco al jefe:
            // asi no ahogan a los mobs de otro live.
            registry.track(slot.streamerId, minion);
        }
    }

    private void enterRage(Slot slot, LivingEntity boss) {
        PotionEffectType speed = BossHealth.effectByName("speed");
        PotionEffectType strength = BossHealth.effectByName("strength", "increase_damage");

        if (speed != null) {
            boss.addPotionEffect(new PotionEffect(speed, 20 * 60 * 10, 1, false, false));
        }
        if (strength != null) {
            boss.addPotionEffect(new PotionEffect(strength, 20 * 60 * 10, 1, false, false));
        }

        if (slot.bar != null) slot.bar.color(BossBar.Color.RED);
        showTitle(Component.text("⚔ FURIA", NamedTextColor.DARK_RED),
                Component.text("más rápido y pega más fuerte", NamedTextColor.GRAY));
        broadcast("&4&l⚔ &cel jefe entra en FURIA");
        Fx.burst(boss.getLocation());
        Fx.sound(boss.getLocation(), Fx.SOUND_PHASE, 1.0f, 1.3f);
    }

    /**
     * Salto hacia el jugador más cercano, con onda de empuje al caer.
     *
     * <p>Se avisa un segundo antes a propósito: un ataque que no se puede esquivar no es
     * difícil, es injusto, y en cámara se ve como un bug.
     */
    private void leap(Slot slot, LivingEntity boss) {
        Player target = nearestPlayer(boss.getLocation(), 20);
        if (target == null) return;

        Fx.ring(boss.getLocation(), 1.5, 12);
        Fx.sound(boss.getLocation(), Fx.SOUND_TELEGRAPH, 1.0f, 1.8f);

        UUID expected = slot.activeId;
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!stillTheSameBoss(slot, expected, boss) || !target.isOnline()) return;

            Vector jump = target.getLocation().toVector()
                    .subtract(boss.getLocation().toVector())
                    .normalize().multiply(0.9).setY(0.55);
            boss.setVelocity(jump);
            Fx.sound(boss.getLocation(), Fx.SOUND_ENRAGE, 0.8f, 1.6f);

            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (!stillTheSameBoss(slot, expected, boss)) return;

                Location landing = boss.getLocation();
                Fx.burst(landing);
                for (Player nearby : landing.getWorld().getPlayers()) {
                    if (nearby.getLocation().distanceSquared(landing) > 16.0) continue;
                    Vector push = nearby.getLocation().toVector()
                            .subtract(landing.toVector());
                    if (push.lengthSquared() < 0.01) push = new Vector(0, 1, 0);
                    nearby.setVelocity(push.normalize().multiply(0.7).setY(0.45));
                }
            }, 20L);
        }, 20L);
    }

    private boolean stillTheSameBoss(Slot slot, UUID expected, LivingEntity boss) {
        return expected != null && expected.equals(slot.activeId)
                && boss.isValid() && !boss.isDead();
    }

    // ------------------------------------------------------------------ eventos

    /** Llamado por el listener cuando un jefe muere. Encuentra su slot por la PDC. */
    public void onDeath(Entity entity) {
        Slot slot = slotOf(entity);
        if (slot == null || slot.activeId == null
                || !slot.activeId.equals(entity.getUniqueId())) return;

        String who = BossTitle.join(slot.donors);
        showTitle(Component.text("JEFE DERROTADO", NamedTextColor.GOLD),
                Component.text("el de " + who + " ha caído", NamedTextColor.GRAY));
        broadcast("&6&l⚔ &eel jefe de &f" + who + " &eha sido derrotado");
        Fx.sound(entity.getLocation(), Fx.SOUND_DEATH, 1.0f, 1.0f);

        cleanup(slot);
    }

    /** Chunk descargado: se suelta la barra y se paran las tareas. El jefe sigue vivo en disco. */
    public void onUnload(Entity entity) {
        Slot slot = slotOf(entity);
        if (slot == null || slot.activeId == null
                || !slot.activeId.equals(entity.getUniqueId())) return;
        plugin.getLogger().info("[boss] el chunk del jefe de '" + slot.streamerId
                + "' se descargó: barra y tareas canceladas");
        cleanup(slot);
    }

    private Slot slotOf(Entity entity) {
        if (entity == null) return null;
        return slots.get(pdc.streamerId(entity));
    }

    public void showTo(Player player) {
        for (Slot slot : slots.values()) {
            if (slot.bar != null) player.showBossBar(slot.bar);
        }
    }

    // ------------------------------------------------------------------ estado

    /** @return true si al menos un live tiene un jefe vivo ahora mismo. */
    public boolean isActive() {
        for (Slot slot : slots.values()) {
            if (activeEntity(slot) != null) return true;
        }
        return false;
    }

    public boolean isSummoning() {
        for (Slot slot : slots.values()) {
            if (slot.summonTask != null) return true;
        }
        return false;
    }

    public boolean hasPending() {
        for (Slot slot : slots.values()) {
            if (slot.pending != null) return true;
        }
        return false;
    }

    /** Donantes del jefe del live "main", para comandos monousuario. */
    public List<String> donors() {
        Slot slot = slots.get("main");
        return slot == null ? List.of() : slot.donors;
    }

    /** Describe el estado de todos los lives, uno por linea cuando hay mas de uno. */
    public String describe() {
        if (slots.isEmpty()) return "ninguno";

        List<String> parts = new ArrayList<>();
        for (Slot slot : slots.values()) {
            String prefix = slots.size() == 1 ? "" : "'" + slot.streamerId + "': ";
            if (activeEntity(slot) != null) {
                parts.add(prefix + "vivo " + name(slot) + " (" + Math.round(slot.maxHp)
                        + " de vida, " + slot.totalCoins + " monedas)");
            } else if (slot.summonTask != null) {
                parts.add(prefix + "invocándose...");
            } else if (slot.pending != null) {
                parts.add(prefix + "encolado por TPS: "
                        + BossTitle.join(slot.pending.donors()));
            }
        }
        return parts.isEmpty() ? "ninguno" : String.join(" | ", parts);
    }

    /** El jefe vivo de ese live, o null si murió, despawneó o su chunk no está cargado. */
    private LivingEntity activeEntity(Slot slot) {
        if (slot.activeId == null) return null;
        Entity entity = Bukkit.getEntity(slot.activeId);
        if (entity instanceof LivingEntity living && living.isValid() && !living.isDead()) {
            return living;
        }
        return null;
    }

    private void cleanup(Slot slot) {
        if (slot.bossTask != null) {
            slot.bossTask.cancel();
            slot.bossTask = null;
        }
        hideBar(slot);
        clearActive(slot);
    }

    private void clearActive(Slot slot) {
        slot.activeId = null;
        slot.donors = List.of();
        slot.totalCoins = 0;
        slot.maxHp = 0;
        slot.vanillaMax = 0;
        slot.ticks = 0;
    }

    // ------------------------------------------------------------------ presentación

    private String name(Slot slot) {
        return BossTitle.compose(slot.donors, slot.activeDefinition.titleOne(),
                slot.activeDefinition.titleMany());
    }

    private void nameplate(Slot slot, LivingEntity living) {
        // Los nombres ya pasaron por NameSanitizer en el core: sin códigos de color ni saltos.
        living.customName(Component.text(name(slot), NamedTextColor.RED));
        living.setCustomNameVisible(true);
    }

    private void showBar(Slot slot, LivingEntity living) {
        hideBar(slot);
        slot.bar = BossBar.bossBar(Component.text(name(slot), NamedTextColor.RED), 1.0f,
                BossBar.Color.PURPLE, BossBar.Overlay.NOTCHED_12);
        for (Player player : Bukkit.getOnlinePlayers()) {
            player.showBossBar(slot.bar);
        }
    }

    private void updateBar(Slot slot, LivingEntity living) {
        if (slot.bar == null) return;
        float progress = (float) Math.max(0.0, Math.min(1.0,
                slot.maxHp <= 0 ? 1.0 : living.getHealth() / slot.maxHp));
        slot.bar.progress(progress);
        slot.bar.name(Component.text(name(slot) + "  " + Math.round(living.getHealth())
                + "/" + Math.round(slot.maxHp), NamedTextColor.RED));
    }

    private void hideBar(Slot slot) {
        if (slot.bar == null) return;
        for (Player player : Bukkit.getOnlinePlayers()) {
            player.hideBossBar(slot.bar);
        }
        slot.bar = null;
    }

    private void showTitle(Component main, Component sub) {
        Title title = Title.title(main, sub, Title.Times.times(
                Duration.ofMillis(200), Duration.ofMillis(1600), Duration.ofMillis(400)));
        for (Player player : Bukkit.getOnlinePlayers()) {
            player.showTitle(title);
        }
    }

    private void broadcast(String legacy) {
        Component message = dev.juanse.tiktokmobs.plugin.runtime.Messages.color(legacy);
        for (Player player : Bukkit.getOnlinePlayers()) {
            player.sendMessage(message);
        }
    }

    // ------------------------------------------------------------------ equipo

    /** Más monedas, mejor equipo. Es la parte visible del escalado: se ve antes que la vida. */
    private void equip(LivingEntity living, BossDefinition definition, int coins) {
        BossDefinition.Gear gear = definition.gearFor(coins);
        EntityEquipment equipment = living.getEquipment();
        if (gear == null || equipment == null) return;

        Enchantment sharpness = enchantment("sharpness");
        Enchantment protection = enchantment("protection");

        equipment.setItemInMainHand(item(gear.weapon(), sharpness, gear.enchantLevel()));
        equipment.setHelmet(item(gear.helmet(), protection, gear.enchantLevel()));
        equipment.setChestplate(item(gear.chest(), protection, gear.enchantLevel()));
        equipment.setLeggings(item(gear.legs(), protection, gear.enchantLevel()));
        equipment.setBoots(item(gear.boots(), protection, gear.enchantLevel()));

        // Sin drop: si el jefe suelta su equipo, la recompensa es el loot y no la pelea.
        equipment.setItemInMainHandDropChance(0f);
        equipment.setHelmetDropChance(0f);
        equipment.setChestplateDropChance(0f);
        equipment.setLeggingsDropChance(0f);
        equipment.setBootsDropChance(0f);
    }

    private ItemStack item(String materialName, Enchantment enchantment, int level) {
        if (materialName == null || materialName.isBlank()) return null;

        Material material;
        try {
            material = Material.valueOf(materialName.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            plugin.getLogger().warning("[boss] material desconocido en el equipo: '"
                    + materialName + "'");
            return null;
        }

        ItemStack stack = new ItemStack(material);
        if (enchantment != null && level > 0) {
            // addUnsafe: los niveles por encima del máximo vanilla son el escalado por monedas.
            stack.addUnsafeEnchantment(enchantment, level);
        }
        return stack;
    }

    /** Por clave de registro: los nombres del enum Enchantment también se renombraron. */
    private Enchantment enchantment(String key) {
        try {
            return Registry.ENCHANTMENT.get(NamespacedKey.minecraft(key));
        } catch (Throwable ignored) {
            return null;
        }
    }

    // ------------------------------------------------------------------ utilidades

    private EntityType entityType(String raw) {
        try {
            return EntityType.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private Player nearestPlayer(Location at, double radius) {
        World world = at.getWorld();
        if (world == null) return null;

        Player best = null;
        double bestDistance = radius * radius;

        for (Player player : world.getPlayers()) {
            if (player.isDead()) continue;
            double distance = player.getLocation().distanceSquared(at);
            if (distance <= bestDistance) {
                bestDistance = distance;
                best = player;
            }
        }
        return best;
    }

    /** Los nombres de los donantes de esta entidad (jefe o esbirro). Para el título de muerte. */
    public List<String> attributionOf(Entity entity) {
        List<String> stored = pdc.attribution(entity);
        return stored.isEmpty() ? new ArrayList<>() : stored;
    }
}
