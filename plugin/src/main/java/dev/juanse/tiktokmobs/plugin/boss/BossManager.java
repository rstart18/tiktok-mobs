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
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.UUID;
import java.util.function.BooleanSupplier;

/**
 * El jefe: uno a la vez, y el único dueño de su estado.
 *
 * <p>Reglas que vienen del diseño y que esta clase hace cumplir:
 * <ul>
 *   <li><b>Fuera del tope normal.</b> El jefe no entra en {@link SpawnRegistry}, así que no puede
 *       ser evictado a mitad de pelea por una horda que llegue después. Sus esbirros sí entran.</li>
 *   <li><b>Enfurecer, no encolar.</b> Otra donación de jefe mientras hay uno vivo le suma vida
 *       y añade al donante al nombre. Dos jefes a la vez no es más espectáculo, es lag.</li>
 *   <li><b>Encolar si el servidor va ahogado.</b> El jefe es la compra más cara del catálogo:
 *       no se puede quedar en un mensaje de chat. Se guarda y sale cuando el TPS recupera.</li>
 *   <li><b>Atribución en la entidad.</b> Todo va en la PersistentDataContainer, no en un mapa
 *       en memoria, así sobrevive a reinicios y a chunks que se descargan.</li>
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
        /** Ya había uno vivo: se le sumó vida y el donante al nombre. */
        ENRAGED,
        /** Servidor ahogado: guardado para cuando recupere. */
        QUEUED
    }

    /** Un jefe esperando a que el TPS recupere. */
    private record Pending(BossDefinition definition, Location where,
                           List<String> donors, int coins, long since) {
    }

    private final JavaPlugin plugin;
    private final SpawnRegistry registry;
    private final BossPdc pdc;
    private final BooleanSupplier degraded;
    /** id del config -> definición. Hace falta para readoptar leyendo el id de la PDC. */
    private final java.util.function.Function<String, BossDefinition> lookup;
    private BossModelBridge models = BossModelBridge.NONE;

    // -------- estado del jefe activo (solo se toca desde el hilo principal)
    private UUID activeId;
    private BossDefinition activeDefinition;
    private BossBar bar;
    private BukkitTask bossTask;
    private double maxHp;
    private double vanillaMax;
    private int totalCoins;
    private List<String> donors = List.of();
    private int ticks;

    // -------- suspenso en curso
    private BukkitTask summonTask;
    private BossDefinition summoningDefinition;
    private List<String> summoningDonors = List.of();
    private int summoningCoins;

    // -------- cola por TPS
    private Pending pending;
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
        stopTasks();
        models = BossModelBridge.detect(plugin.getLogger());

        // Un /reload no debería dejar al jefe sin barra: se readopta lo que ya esté vivo.
        adoptLoaded();

        pendingTask = Bukkit.getScheduler().runTaskTimer(plugin, this::drainPending, 40L, 20L);
    }

    /** Cancela todo y suelta la barra. El jefe se queda vivo: su estado está en la PDC. */
    public void stop() {
        stopTasks();
        hideBar();
        clearState();
    }

    private void stopTasks() {
        if (bossTask != null) {
            bossTask.cancel();
            bossTask = null;
        }
        if (summonTask != null) {
            summonTask.cancel();
            summonTask = null;
        }
        if (pendingTask != null) {
            pendingTask.cancel();
            pendingTask = null;
        }
    }

    // ------------------------------------------------------------------ entrada

    /**
     * Lo único que llama el executor.
     *
     * @param at sitio YA validado por el buscador. No se vuelve a buscar: si se revalidara al
     *           final del suspenso, el jefe podría salir en otro punto y las partículas
     *           quedarían en el sitio equivocado.
     */
    public Outcome request(BossDefinition definition, Location at, String donor, int coins) {
        LivingEntity alive = activeEntity();

        if (alive != null) {
            enrage(alive, donor, coins);
            return Outcome.ENRAGED;
        }

        // Suspenso ya en marcha: el segundo donante se suma al que está naciendo.
        if (summonTask != null) {
            summoningDonors = BossTitle.add(summoningDonors, donor);
            summoningCoins += Math.max(0, coins);
            Fx.burst(at);
            Fx.sound(at, Fx.SOUND_ENRAGE, 1.0f, 1.4f);
            return Outcome.ENRAGED;
        }

        if (degraded.getAsBoolean()) {
            queue(definition, at, donor, coins);
            return Outcome.QUEUED;
        }

        beginSummon(definition, at, BossTitle.add(List.of(), donor), Math.max(0, coins));
        return Outcome.SUMMONING;
    }

    private void queue(BossDefinition definition, Location at, String donor, int coins) {
        if (pending != null && pending.definition().id().equals(definition.id())) {
            pending = new Pending(definition, pending.where(),
                    BossTitle.add(pending.donors(), donor),
                    pending.coins() + Math.max(0, coins), pending.since());
        } else {
            pending = new Pending(definition, at.clone(),
                    BossTitle.add(List.of(), donor), Math.max(0, coins), System.currentTimeMillis());
        }

        broadcast("&e[Jefe] &fel servidor va cargado: el jefe de &e"
                + BossTitle.join(pending.donors()) + " &fsale en cuanto recupere.");
    }

    /** Se ejecuta cada segundo: suelta el jefe encolado cuando el TPS vuelve a su sitio. */
    private void drainPending() {
        if (pending == null) return;

        if (System.currentTimeMillis() - pending.since() > PENDING_TIMEOUT_MS) {
            broadcast("&c[Jefe] el servidor no recuperó: se cancela el jefe de &f"
                    + BossTitle.join(pending.donors()) + "&c. Usa &f/ttmobs testboss&c para soltarlo a mano.");
            plugin.getLogger().warning("[boss] jefe encolado cancelado por timeout: "
                    + BossTitle.join(pending.donors()) + " (" + pending.coins() + " monedas)");
            pending = null;
            return;
        }

        if (degraded.getAsBoolean() || activeEntity() != null || summonTask != null) return;

        Pending ready = pending;
        pending = null;
        beginSummon(ready.definition(), ready.where(), ready.donors(), ready.coins());
    }

    // ------------------------------------------------------------------ suspenso

    private void beginSummon(BossDefinition definition, Location at, List<String> donorNames,
                             int coins) {
        summoningDefinition = definition;
        summoningDonors = donorNames;
        summoningCoins = coins;

        String who = BossTitle.join(donorNames);
        showTitle(Component.text("⚔ " + who + " invoca un jefe", NamedTextColor.DARK_RED),
                Component.text("prepárate", NamedTextColor.GRAY));
        broadcast("&4&l⚔ &c" + who + " &finvoca un jefe...");
        Fx.sound(at, Fx.SOUND_SUMMON, 1.0f, 0.8f);

        int total = Math.max(20, definition.suspenseTicks());
        final int[] elapsed = {0};

        summonTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            elapsed[0] += 4;

            double progress = Math.min(1.0, elapsed[0] / (double) total);
            Fx.ring(at, 2.5 - progress * 1.5, 16);
            Fx.pillar(at, 1.0 + progress * 2.0);
            Fx.sound(at, Fx.SOUND_TICK, 0.8f, 0.6f + (float) progress);

            if (elapsed[0] < total) return;

            if (summonTask != null) {
                summonTask.cancel();
                summonTask = null;
            }
            spawn(summoningDefinition, at, summoningDonors, summoningCoins);
            summoningDefinition = null;
            summoningDonors = List.of();
            summoningCoins = 0;
        }, 4L, 4L);
    }

    // ------------------------------------------------------------------ spawn

    private void spawn(BossDefinition definition, Location at, List<String> donorNames, int coins) {
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
        vanillaMax = living.getHealth();

        living.setPersistent(true);
        living.setGlowing(true);
        living.setRemoveWhenFarAway(false);
        if (living instanceof Mob mob) {
            mob.setTarget(nearestPlayer(living.getLocation(), 32));
        }

        activeId = living.getUniqueId();
        activeDefinition = definition;
        donors = donorNames;
        totalCoins = coins;
        ticks = 0;

        maxHp = BossHealth.setMax(living, definition.hpFor(coins), vanillaMax);
        BossHealth.heal(living, maxHp, maxHp);

        pdc.markBoss(living, definition.id(), donors, totalCoins, maxHp);
        equip(living, definition, coins);
        nameplate(living);

        // IMPORTANTE: nada de registry.track(living). El jefe va fuera del tope normal.
        if (definition.hasModel() && !models.mount(living, definition.modelId())) {
            plugin.getLogger().info("[boss] sin modelo: el jefe sale vanilla equipado "
                    + "(es también lo que ven los jugadores de Bedrock).");
        }

        showBar();
        startBossTask();

        broadcast("&4&l⚔ &c" + name() + " &cha aparecido &7(" + Math.round(maxHp) + " de vida)");
        Fx.burst(living.getLocation());
        Fx.sound(living.getLocation(), Fx.SOUND_SUMMON, 1.0f, 1.0f);
    }

    /** Readopta un jefe que ya existía: tras /reload o cuando su chunk vuelve a cargarse. */
    public void adopt(Entity entity) {
        if (activeId != null || !(entity instanceof LivingEntity living)) return;
        if (!pdc.isBoss(living)) return;

        BossDefinition definition = lookup.apply(pdc.definitionId(living));
        if (definition == null) {
            plugin.getLogger().warning("[boss] hay un jefe '" + pdc.definitionId(living)
                    + "' vivo pero ya no está en el config: se queda sin barra ni fases.");
            return;
        }

        activeId = living.getUniqueId();
        activeDefinition = definition;
        donors = pdc.donors(living);
        totalCoins = pdc.coins(living);
        maxHp = pdc.maxHp(living) > 0 ? pdc.maxHp(living) : definition.hpFor(totalCoins);
        vanillaMax = 0;
        ticks = 0;

        nameplate(living);
        showBar();
        startBossTask();
        plugin.getLogger().info("[boss] readoptado el jefe de " + BossTitle.join(donors));
    }

    private void adoptLoaded() {
        for (World world : Bukkit.getWorlds()) {
            for (LivingEntity living : world.getLivingEntities()) {
                if (!pdc.isBoss(living)) continue;
                adopt(living);
                if (activeId != null) return;
            }
        }
    }

    // ------------------------------------------------------------------ enfurecer

    private void enrage(LivingEntity living, String donor, int coins) {
        donors = BossTitle.add(donors, donor);
        totalCoins += Math.max(0, coins);

        double before = maxHp;
        maxHp = BossHealth.setMax(living, activeDefinition.hpAfterEnrage(maxHp, coins), vanillaMax);
        BossHealth.heal(living, maxHp - before, maxHp);

        pdc.setDonors(living, donors);
        pdc.setCoins(living, totalCoins);
        pdc.setMaxHp(living, maxHp);
        nameplate(living);
        updateBar(living);

        showTitle(Component.text("⚔ el jefe se enfurece", NamedTextColor.RED),
                Component.text(donor + " suma " + Math.round(maxHp - before) + " de vida",
                        NamedTextColor.GRAY));
        broadcast("&c&l⚔ &f" + donor + " &cenfurece al jefe &7(+"
                + Math.round(maxHp - before) + " de vida, ahora " + Math.round(maxHp) + ")");

        Fx.burst(living.getLocation());
        Fx.sound(living.getLocation(), Fx.SOUND_ENRAGE, 1.0f, 0.9f);
    }

    // ------------------------------------------------------------------ bucle

    private void startBossTask() {
        if (bossTask != null) bossTask.cancel();
        bossTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, TICK_PERIOD, TICK_PERIOD);
    }

    private void tick() {
        LivingEntity living = activeEntity();
        if (living == null) {
            // Muerto, despawneado o chunk descargado: se limpia solo, sin esperar al evento.
            cleanup();
            return;
        }

        ticks++;
        updateBar(living);

        double fraction = maxHp <= 0 ? 1.0 : living.getHealth() / maxHp;

        if (fraction <= activeDefinition.minionsAt() && !pdc.phaseFired(living, PHASE_MINIONS)) {
            pdc.markPhase(living, PHASE_MINIONS);
            summonMinions(living);
        }

        if (fraction <= activeDefinition.rageAt() && !pdc.phaseFired(living, PHASE_RAGE)) {
            pdc.markPhase(living, PHASE_RAGE);
            enterRage(living);
        }

        // Ataque extra de la fase de furia: un salto telegrafiado cada 6 segundos.
        if (pdc.phaseFired(living, PHASE_RAGE) && ticks % 12 == 0) {
            leap(living);
        }
    }

    private void summonMinions(LivingEntity boss) {
        broadcast("&6&l⚔ &eel jefe llama a los suyos");
        Fx.burst(boss.getLocation());
        Fx.sound(boss.getLocation(), Fx.SOUND_PHASE, 1.0f, 0.7f);

        // Con el servidor ahogado la fase se anuncia igual pero no se paga: los esbirros son
        // justo lo que lo hundiría más.
        if (degraded.getAsBoolean()) {
            broadcast("&7(servidor cargado: esta vez sin esbirros)");
            return;
        }

        EntityType type = entityType(activeDefinition.minionEntity());
        if (type == null) return;

        int min = activeDefinition.minionsMin();
        int max = activeDefinition.minionsMax();
        int count = min + (max > min ? RANDOM.nextInt(max - min + 1) : 0);
        if (count <= 0) return;

        java.util.Set<Long> ocupados = new java.util.HashSet<>();
        String label = BossTitle.join(donors);
        Player target = nearestPlayer(boss.getLocation(), 32);

        for (int i = 0; i < count; i++) {
            Location where = SafeSpawn.find(boss.getLocation(), 3, i, count, ocupados);
            Entity minion = boss.getWorld().spawnEntity(where, type);

            minion.customName(Component.text("esbirro de " + label));
            minion.setCustomNameVisible(true);
            pdc.markMinion(minion, donors);

            if (minion instanceof Mob mob && target != null) mob.setTarget(target);

            // Los esbirros SÍ van al tope normal: son mobs corrientes y deben poder ser
            // evictados como cualquier otro si se acumulan.
            // TODO C7: cuando BossManager pase a mapa por streamer, los esbirros deben ir
            //          a la cola del live que invocó al jefe, no a la de "main".
            registry.track("main", minion);
        }
    }

    private void enterRage(LivingEntity boss) {
        PotionEffectType speed = BossHealth.effectByName("speed");
        PotionEffectType strength = BossHealth.effectByName("strength", "increase_damage");

        if (speed != null) {
            boss.addPotionEffect(new PotionEffect(speed, 20 * 60 * 10, 1, false, false));
        }
        if (strength != null) {
            boss.addPotionEffect(new PotionEffect(strength, 20 * 60 * 10, 1, false, false));
        }

        if (bar != null) bar.color(BossBar.Color.RED);
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
    private void leap(LivingEntity boss) {
        Player target = nearestPlayer(boss.getLocation(), 20);
        if (target == null) return;

        Fx.ring(boss.getLocation(), 1.5, 12);
        Fx.sound(boss.getLocation(), Fx.SOUND_TELEGRAPH, 1.0f, 1.8f);

        UUID expected = activeId;
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!stillTheSameBoss(expected, boss) || !target.isOnline()) return;

            Vector jump = target.getLocation().toVector()
                    .subtract(boss.getLocation().toVector())
                    .normalize().multiply(0.9).setY(0.55);
            boss.setVelocity(jump);
            Fx.sound(boss.getLocation(), Fx.SOUND_ENRAGE, 0.8f, 1.6f);

            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (!stillTheSameBoss(expected, boss)) return;

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

    private boolean stillTheSameBoss(UUID expected, LivingEntity boss) {
        return expected != null && expected.equals(activeId) && boss.isValid() && !boss.isDead();
    }

    // ------------------------------------------------------------------ eventos

    /** Llamado por el listener cuando el jefe muere. */
    public void onDeath(Entity entity) {
        if (activeId == null || !activeId.equals(entity.getUniqueId())) return;

        String who = BossTitle.join(donors);
        showTitle(Component.text("JEFE DERROTADO", NamedTextColor.GOLD),
                Component.text("el de " + who + " ha caído", NamedTextColor.GRAY));
        broadcast("&6&l⚔ &eel jefe de &f" + who + " &eha sido derrotado");
        Fx.sound(entity.getLocation(), Fx.SOUND_DEATH, 1.0f, 1.0f);

        cleanup();
    }

    /** Chunk descargado: se suelta la barra y se paran las tareas. El jefe sigue vivo en disco. */
    public void onUnload(Entity entity) {
        if (activeId == null || !activeId.equals(entity.getUniqueId())) return;
        plugin.getLogger().info("[boss] el chunk del jefe se descargó: barra y tareas canceladas");
        cleanup();
    }

    public void showTo(Player player) {
        if (bar != null) player.showBossBar(bar);
    }

    // ------------------------------------------------------------------ estado

    public boolean isActive() {
        return activeEntity() != null;
    }

    public boolean isSummoning() {
        return summonTask != null;
    }

    public boolean hasPending() {
        return pending != null;
    }

    public List<String> donors() {
        return donors;
    }

    public String describe() {
        if (activeEntity() != null) {
            return "vivo: " + name() + " (" + Math.round(maxHp) + " de vida, "
                    + totalCoins + " monedas)";
        }
        if (summonTask != null) return "invocándose...";
        if (pending != null) {
            return "encolado por TPS: " + BossTitle.join(pending.donors());
        }
        return "ninguno";
    }

    /** El jefe vivo, o null si murió, despawneó o su chunk no está cargado. */
    private LivingEntity activeEntity() {
        if (activeId == null) return null;
        Entity entity = Bukkit.getEntity(activeId);
        if (entity instanceof LivingEntity living && living.isValid() && !living.isDead()) {
            return living;
        }
        return null;
    }

    private void cleanup() {
        if (bossTask != null) {
            bossTask.cancel();
            bossTask = null;
        }
        hideBar();
        clearState();
    }

    private void clearState() {
        activeId = null;
        donors = List.of();
        totalCoins = 0;
        maxHp = 0;
        vanillaMax = 0;
        ticks = 0;
    }

    // ------------------------------------------------------------------ presentación

    private String name() {
        return BossTitle.compose(donors, activeDefinition.titleOne(), activeDefinition.titleMany());
    }

    private void nameplate(LivingEntity living) {
        // Los nombres ya pasaron por NameSanitizer en el core: sin códigos de color ni saltos.
        living.customName(Component.text(name(), NamedTextColor.RED));
        living.setCustomNameVisible(true);
    }

    private void showBar() {
        hideBar();
        bar = BossBar.bossBar(Component.text(name(), NamedTextColor.RED), 1.0f,
                BossBar.Color.PURPLE, BossBar.Overlay.NOTCHED_12);
        for (Player player : Bukkit.getOnlinePlayers()) {
            player.showBossBar(bar);
        }
    }

    private void updateBar(LivingEntity living) {
        if (bar == null) return;
        float progress = (float) Math.max(0.0, Math.min(1.0,
                maxHp <= 0 ? 1.0 : living.getHealth() / maxHp));
        bar.progress(progress);
        bar.name(Component.text(name() + "  " + Math.round(living.getHealth())
                + "/" + Math.round(maxHp), NamedTextColor.RED));
    }

    private void hideBar() {
        if (bar == null) return;
        for (Player player : Bukkit.getOnlinePlayers()) {
            player.hideBossBar(bar);
        }
        bar = null;
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
