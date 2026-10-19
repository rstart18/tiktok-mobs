package dev.juanse.tiktokmobs.plugin;

import dev.juanse.tiktokmobs.core.ActionResolver;
import dev.juanse.tiktokmobs.core.dedup.DedupPolicy;
import dev.juanse.tiktokmobs.core.source.FakeGiftSource;
import dev.juanse.tiktokmobs.core.source.GiftSource;
import dev.juanse.tiktokmobs.core.boss.BossDefinition;
import dev.juanse.tiktokmobs.plugin.boss.BossListener;
import dev.juanse.tiktokmobs.plugin.boss.BossManager;
import dev.juanse.tiktokmobs.plugin.boss.BossPdc;
import dev.juanse.tiktokmobs.plugin.command.TikTokMobsCommand;
import dev.juanse.tiktokmobs.plugin.compat.BedrockCompat;
import dev.juanse.tiktokmobs.plugin.config.ConfigLoader;
import dev.juanse.tiktokmobs.plugin.config.PluginSettings;
import dev.juanse.tiktokmobs.plugin.overlay.OverlayFeed;
import dev.juanse.tiktokmobs.plugin.overlay.OverlayPayloads;
import dev.juanse.tiktokmobs.plugin.overlay.OverlayServer;
import dev.juanse.tiktokmobs.plugin.runtime.BukkitActionExecutor;
import dev.juanse.tiktokmobs.plugin.runtime.GiftDispatcher;
import dev.juanse.tiktokmobs.plugin.runtime.LedgerStore;
import dev.juanse.tiktokmobs.plugin.runtime.SafeSpawn;
import dev.juanse.tiktokmobs.plugin.runtime.SpawnRegistry;
import dev.juanse.tiktokmobs.plugin.runtime.TargetResolver;
import dev.juanse.tiktokmobs.plugin.source.TikFinityWebSocketSource;
import dev.juanse.tiktokmobs.plugin.ui.BossTracker;
import dev.juanse.tiktokmobs.plugin.ui.ChatRelay;
import dev.juanse.tiktokmobs.plugin.ui.DonorBoard;
import dev.juanse.tiktokmobs.plugin.ui.GoalBar;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.command.PluginCommand;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Ensamblaje. Esta clase solo cablea piezas: no hay lógica de negocio aquí, está en el core.
 *
 * <p>Arquitectura:
 * <pre>
 *   GiftSource (hilo ajeno)
 *        |  submit()  -- solo encola, prohibido tocar Bukkit
 *        v
 *   GiftDispatcher (hilo principal, 1 tick, con presupuesto y guardia de TPS)
 *        |  ActionResolver (core: dedup, rachas, tiers, meta)
 *        v
 *   BukkitActionExecutor -> mundo
 * </pre>
 */
public final class TikTokMobsPlugin extends JavaPlugin implements Listener {

    private PluginSettings settings;
    private ActionResolver resolver;
    private SpawnRegistry spawnRegistry;
    private TargetResolver targets;
    private BukkitActionExecutor executor;
    private GiftDispatcher dispatcher;
    private BossTracker bossTracker;
    private BossPdc bossPdc;
    private BossManager bossManager;
    private ChatRelay chatRelay = ChatRelay.DISABLED;
    private dev.juanse.tiktokmobs.plugin.runtime.Spectacle spectacle;
    private dev.juanse.tiktokmobs.plugin.config.CatalogStore catalogStore;
    private GoalBar goalBar;
    private DonorBoard donorBoard;

    private LedgerStore ledgerStore;
    private org.bukkit.scheduler.BukkitTask ledgerTask;
    private OverlayServer overlayServer;
    private OverlayFeed overlayFeed = OverlayFeed.DISABLED;
    private org.bukkit.scheduler.BukkitTask overlayStateTask;
    /** Último estado publicado, para mandarlo a quien conecte después (recarga de OBS). */
    private volatile String lastOverlayState;

    private FakeGiftSource fake;
    private final java.util.Map<String, GiftSource> liveSources = new java.util.LinkedHashMap<>();
    private volatile boolean rawDump;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        bootstrap();

        PluginCommand command = getCommand("ttmobs");
        if (command != null) {
            TikTokMobsCommand handler = new TikTokMobsCommand(this);
            command.setExecutor(handler);
            command.setTabCompleter(handler);
        }
        getServer().getPluginManager().registerEvents(this, this);
        getServer().getPluginManager().registerEvents(new BossListener(bossManager, bossPdc), this);
        getServer().getPluginManager().registerEvents(
                new dev.juanse.tiktokmobs.plugin.runtime.SpectacleListener(spectacle), this);

        getLogger().info("listo. Fuente: " + sourceDescription());
        getLogger().info(BedrockCompat.describe());
        if (settings.sourceType() == PluginSettings.SourceType.FAKE) {
            getLogger().info("modo 'fake': prueba con /ttmobs test Rose 30 antes de abrir el live.");
        }
    }

    @Override
    public void onDisable() {
        teardown();
    }

    // ---------------------------------------------------------------- ciclo de vida

    private void bootstrap() {
        // El panel escribe su propio archivo y manda sobre el bloque gift-ids del config.
        if (catalogStore == null) {
            catalogStore = new dev.juanse.tiktokmobs.plugin.config.CatalogStore(this);
        } else {
            catalogStore.recargar();
        }
        settings = ConfigLoader.load(getConfig(), catalogStore.raiz(), getLogger());

        spawnRegistry = new SpawnRegistry(settings.maxLiveSpawned());
        targets = new TargetResolver(settings);
        resolver = new ActionResolver(settings.mapping(), new DedupPolicy(settings.dedupTtlMs()),
                settings.chaosEvery());

        // Antes de nada: recuperar el marcador del live anterior. Un reinicio o un corte
        // de luz no deberia borrar las monedas que la gente ya puso.
        ledgerStore = new LedgerStore(this, resolver);
        ledgerStore.load();
        ledgerTask = Bukkit.getScheduler().runTaskTimer(this, () -> ledgerStore.save(), 600L, 600L);

        bossTracker = new BossTracker(this);
        bossTracker.start();

        // El jefe consulta el estado degradado del dispatcher, que se crea más abajo. El
        // lambda lee el campo en cada llamada, así que no importa el orden de construcción.
        bossPdc = new BossPdc(this);
        bossManager = new BossManager(this, spawnRegistry, bossPdc,
                () -> dispatcher != null && dispatcher.degraded(),
                settings.bosses()::get);

        startOverlay();

        executor = new BukkitActionExecutor(this, targets, spawnRegistry, bossTracker,
                bossManager, overlayFeed, settings);
        dispatcher = new GiftDispatcher(this, resolver, executor,
                settings.queueCapacity(), settings.maxActionsPerTick(), settings.tpsFloor());
        dispatcher.start();
        bossManager.start();

        spectacle = new dev.juanse.tiktokmobs.plugin.runtime.Spectacle(this);
        dev.juanse.tiktokmobs.plugin.runtime.WorldEvents.setSpectacle(spectacle);
        executor.setChaos(resolver.chaos()::apply);

        chatRelay = new ChatRelay(settings.chatEnabled(),
                settings.chatMaxPerSecond(), settings.chatMinCoins());
        dispatcher.setCommentSink(event -> chatRelay.show(event,
                resolver.ledger().coinsOf(
                        dev.juanse.tiktokmobs.core.util.NameSanitizer.clean(
                                event.user().rawLabel()))));

        if (settings.goalBar() && settings.mapping().donationGoalCoins() > 0) {
            goalBar = new GoalBar(this, resolver.ledger());
            goalBar.start();
        }
        if (settings.scoreboard()) {
            donorBoard = new DonorBoard(this, resolver.ledger(), settings.scoreboardSize());
            donorBoard.start();
        }

        // El inyector manual está SIEMPRE activo, también con la fuente real conectada:
        // así puedes probar un mapeo nuevo en medio de un live sin esperar una donación.
        fake = new FakeGiftSource();
        fake.start(dispatcher::submit);

        // El endpoint de ingesta necesita el dispatcher ya creado, por eso se cablea aquí
        // y no dentro de startOverlay().
        if (overlayServer != null) {
            overlayServer.setIngest((streamerId, json) -> {
                onRawMessage(streamerId, json);
                dev.juanse.tiktokmobs.plugin.source.TikFinityParser.parse(json)
                        .ifPresent(event -> dispatcher.submit(event, streamerId));
            });
        }

        openSource();
    }

    /**
     * Arranca el servidor del overlay y la tarea que refresca su estado.
     *
     * <p>El payload de estado se construye SIEMPRE en el hilo principal (ahí se puede leer el
     * ledger sin sorpresas) y se guarda en {@code lastOverlayState}. El servidor HTTP, que
     * corre en sus propios hilos, solo lee esa cadena ya hecha: nunca toca el estado del juego.
     */
    private void startOverlay() {
        if (!settings.overlayEnabled()) {
            overlayFeed = OverlayFeed.DISABLED;
            return;
        }

        overlayServer = new OverlayServer(
                getLogger(),
                settings.overlayBind(),
                settings.overlayPort(),
                settings.overlayToken(),
                () -> lastOverlayState,
                () -> OverlayPayloads.catalog(settings.mapping()));

        if (!overlayServer.start()) {
            overlayServer = null;
            overlayFeed = OverlayFeed.DISABLED;
            return;
        }

        overlayFeed = new OverlayFeed(overlayServer, settings.overlayAlertMinCoins());

        var panel = new dev.juanse.tiktokmobs.plugin.overlay.PanelService(this, catalogStore);
        overlayServer.setPanel(panel);
        getLogger().info("[panel] configurador del catalogo en http://"
                + settings.overlayBind() + ":" + overlayServer.boundPort()
                + "/panel?token=" + panel.token());

        overlayStateTask = Bukkit.getScheduler().runTaskTimer(this, () -> {
            String json = overlayFeed.publishState(
                    resolver.ledger().totalCoins(),
                    resolver.likes().total(),
                    resolver.ledger().top(5));
            lastOverlayState = json;
        }, 20L, settings.overlayStateRefreshTicks());
    }

    private void stopOverlay() {
        if (overlayStateTask != null) {
            overlayStateTask.cancel();
            overlayStateTask = null;
        }
        if (overlayServer != null) {
            overlayServer.stop();
            overlayServer = null;
        }
        overlayFeed = OverlayFeed.DISABLED;
        lastOverlayState = null;
    }

    private void teardown() {
        if (ledgerTask != null) {
            ledgerTask.cancel();
            ledgerTask = null;
        }
        if (ledgerStore != null) ledgerStore.save();
        closeSource();
        if (dispatcher != null) dispatcher.stop();
        // Antes que nada: las tareas repetitivas del jefe (barra y fases) tienen que morir aquí
        // o quedan colgadas apuntando a un plugin que ya no existe.
        // Antes que nada: devolver los bloques que el suelo-es-lava dejó convertidos y quitar
        // los kits prestados. Si esto no corre, le queda el mundo con magma para siempre.
        if (spectacle != null) spectacle.shutdown();
        if (bossManager != null) bossManager.stop();
        if (bossTracker != null) bossTracker.stop();
        if (goalBar != null) goalBar.stop();
        if (donorBoard != null) donorBoard.stop();
        stopOverlay();
        goalBar = null;
        donorBoard = null;
    }

    /** Recarga el config sin reiniciar el servidor. Los mobs ya spawneados se quedan. */
    public void reloadEverything() {
        teardown();
        reloadConfig();
        bootstrap();
    }

    /**
     * Abre una conexión por cada live configurado.
     *
     * <p>Una fuente con url (la tuya, {@code ws://127.0.0.1:21213/}) se conecta hacia fuera.
     * Una fuente sin url espera en modo relay: el PC de ese streamer empuja a /ingest, así
     * no tiene que abrir puertos ni montar túneles.
     */
    public void openSource() {
        closeSource();
        targets.clearStreamers();

        for (dev.juanse.tiktokmobs.plugin.config.StreamSource source : settings.sources()) {
            if (source.hasOwnPlayer()) {
                targets.bindStreamer(source.id(), source.player());
            }

            if (!source.dialsOut()) {
                getLogger().info("[source] '" + source.id() + "' en modo relay"
                        + " (esperando POST en /ingest?source=" + source.id() + ")");
                continue;
            }

            GiftSource live = new TikFinityWebSocketSource(
                    source.url(),
                    settings.reconnectMaxSeconds(),
                    getLogger(),
                    json -> onRawMessage(source.id(), json));
            live.start(event -> dispatcher.submit(event, source.id()));
            liveSources.put(source.id(), live);
        }
    }

    public void closeSource() {
        liveSources.values().forEach(GiftSource::close);
        liveSources.clear();
    }

    private void onRawMessage(String streamerId, String json) {
        if (!rawDump) return;
        getLogger().info("[raw:" + streamerId + "] "
                + (json.length() > 1500 ? json.substring(0, 1500) + "..." : json));
    }

    // ---------------------------------------------------------------- eventos

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        if (goalBar != null) goalBar.showTo(event.getPlayer());
        if (donorBoard != null) donorBoard.showTo(event.getPlayer());
        if (bossTracker != null) bossTracker.showAllTo(event.getPlayer());
        if (bossManager != null) bossManager.showTo(event.getPlayer());
    }

    // ---------------------------------------------------------------- API para el comando

    public FakeGiftSource fake() {
        return fake;
    }

    public dev.juanse.tiktokmobs.core.GiftRegistry giftRegistry() {
        return resolver.gifts();
    }

    public boolean toggleRawDump() {
        rawDump = !rawDump;
        return rawDump;
    }

    public int clearSpawned() {
        bossTracker.clear();
        return spawnRegistry.removeAll();
    }

    /**
     * Suelta un jefe a mano, sin esperar una donación.
     *
     * <p>Llama al BossManager por la misma puerta que el executor, así que pasa por el suspenso,
     * el enfurecer y la cola igual que una donación real: si funciona esto, funciona en vivo.
     *
     * @param bossId id del bloque 'bosses:', o vacío para el primero que haya definido
     * @return qué pasó, o null si no hay ningún jefe con ese id
     */
    public String testBoss(String donante, int monedas, String bossId) {
        if (settings.bosses().isEmpty()) return null;

        String id = bossId == null || bossId.isBlank()
                ? settings.bosses().keySet().iterator().next()
                : bossId;

        BossDefinition definition = settings.bosses().get(id);
        if (definition == null) return null;

        Location at = targets.resolve();
        if (at == null || at.getWorld() == null) return "sin objetivo: no hay jugador conectado";

        String donor = dev.juanse.tiktokmobs.core.util.NameSanitizer.clean(donante);
        BossManager.Outcome outcome = bossManager.request(
                definition, SafeSpawn.findTall(at, 5), donor, Math.max(0, monedas));

        return switch (outcome) {
            case SUMMONING -> "invocando '" + id + "' para " + donor + " (" + monedas + " monedas)";
            case ENRAGED -> "ya había un jefe vivo: enfurecido con " + donor;
            case QUEUED -> "servidor degradado: encolado hasta que recupere el TPS";
        };
    }

    public java.util.Set<String> bossIds() {
        return settings.bosses().keySet();
    }

    /** Los mapeos por id que están activos ahora mismo. Para el panel web. */
    public java.util.Map<Integer, dev.juanse.tiktokmobs.core.action.ActionSpec> mappingByGiftId() {
        return settings.mapping().byGiftId();
    }

    public java.util.Map<String, dev.juanse.tiktokmobs.core.action.ActionSpec> mappingByGiftName() {
        return settings.mapping().byGiftName();
    }

    public java.util.List<dev.juanse.tiktokmobs.core.config.MappingTable.Tier> mappingTiers() {
        return settings.mapping().tiers();
    }

    public void resetLedger() {
        resolver.ledger().reset();
        resolver.likes().reset();
        resolver.chaos().reset();
        resolver.dedup().clear();
        if (ledgerStore != null) ledgerStore.delete();
    }

    /**
     * Suma monedas al marcador SIN ejecutar la accion del tramo.
     * Para recuperar a mano lo que se perdio, no para simular una donacion.
     */
    public int addCoins(String donante, int monedas) {
        String limpio = dev.juanse.tiktokmobs.core.util.NameSanitizer.clean(donante);
        resolver.ledger().record(limpio, monedas);
        if (ledgerStore != null) ledgerStore.save();
        return resolver.ledger().totalCoins();
    }

    public void setTargetOverride(Location location) {
        targets.setOverride(location);
    }

    public String targetDescription() {
        return targets.describe();
    }

    public String overlayDescription() {
        if (overlayServer == null) {
            return settings.overlayEnabled() ? "no arrancó (mira el log)" : "desactivado en el config";
        }
        return overlayServer.describe()
                + (overlayServer.droppedEvents() > 0
                ? " | " + overlayServer.droppedEvents() + " eventos descartados" : "");
    }

    public String sourceDescription() {
        if (settings.sources().isEmpty()) return "sin fuentes";

        StringBuilder out = new StringBuilder();
        for (dev.juanse.tiktokmobs.plugin.config.StreamSource source : settings.sources()) {
            if (out.length() > 0) out.append(" | ");
            out.append(source.id()).append(": ");

            GiftSource live = liveSources.get(source.id());
            if (live != null) {
                out.append(live.connected() ? "conectado" : "desconectado");
            } else {
                out.append("relay");
            }
            if (source.hasOwnPlayer()) out.append(" -> ").append(source.player());
        }
        return out.toString();
    }

    public java.util.List<dev.juanse.tiktokmobs.plugin.config.StreamSource> sources() {
        return settings.sources();
    }

    public boolean sourceConnected(String id) {
        GiftSource live = liveSources.get(id);
        return live != null && live.connected();
    }

    public long ingestedEvents() {
        return overlayServer == null ? 0 : overlayServer.ingested();
    }

    public String statusReport() {
        spawnRegistry.prune();
        double tps = Bukkit.getServer().getTPS()[0];

        return "&6TikTokMobs&7 ---------------------------\n"
                + "&7fuente: &f" + sourceDescription() + "\n"
                + "&7objetivo: &f" + targets.describe() + "\n"
                + "&7eventos: &f" + dispatcher.received() + " recibidos&7, &f"
                + dispatcher.executed() + " acciones&7, &c" + dispatcher.dropped() + " descartados\n"
                + "&7cola: &f" + dispatcher.queued() + "&7 pendientes | TPS &f"
                + String.format(java.util.Locale.ROOT, "%.1f", tps)
                + (dispatcher.degraded() ? " &c(DEGRADADO)" : " &a(ok)") + "\n"
                + "&7entidades: &f" + spawnRegistry.tracked() + "&7/&f" + spawnRegistry.max()
                + "&7 | jefes simples: &f" + bossTracker.active() + "\n"
                + "&7jefe: &f" + bossManager.describe() + "\n"
                + "&7caos: &fnivel " + resolver.chaos().level()
                + "&7 (x" + String.format(java.util.Locale.ROOT, "%.2f", resolver.chaos().multiplier())
                + "&7) | faltan &f" + resolver.chaos().toNextLevel() + "&7 donaciones\n"
                + "&7monedas del live: &f" + resolver.ledger().totalCoins()
                + "&7 | meta: &f" + resolver.ledger().goalCoins()
                + "&7 | likes: &f" + resolver.likes().total() + "\n"
                + "&7overlay: &f" + overlayDescription() + "\n"
                + "&7mapeos: &f" + settings.mapping().mappedGifts() + " regalos&7, &f"
                + settings.mapping().tierCount() + " tramos\n"
                + "&7bedrock: &f" + BedrockCompat.describe();
    }
}
