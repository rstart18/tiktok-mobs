package dev.juanse.tiktokmobs.plugin.runtime;

import dev.juanse.tiktokmobs.core.ActionResolver;
import dev.juanse.tiktokmobs.core.action.ResolvedAction;
import dev.juanse.tiktokmobs.core.event.GiftEvent;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicLong;

/**
 * La frontera entre los dos mundos, y la clase más importante del plugin.
 *
 * <p>Los eventos llegan en el hilo del WebSocket. La API de Bukkit <b>no es thread-safe</b>:
 * spawnear una entidad desde ese hilo corrompe el mundo o tumba el servidor. Así que ese hilo
 * solo tiene permiso para encolar; todo lo demás pasa en el hilo principal, con presupuesto.
 *
 * <p>Tres defensas apiladas:
 * <ol>
 *   <li>cola <b>acotada</b>: si TikTok nos inunda, descartamos en vez de petar la memoria;</li>
 *   <li>presupuesto por tick: un combo de 500 regalos se reparte en segundos, no en un frame;</li>
 *   <li>guardia de TPS: por debajo del suelo configurado dejamos de spawnear y solo avisamos.</li>
 * </ol>
 */
public final class GiftDispatcher {

    private final JavaPlugin plugin;
    private final ActionResolver resolver;
    private final ActionExecutor executor;
    private final BlockingQueue<Incoming> queue;

    /** Un evento con la etiqueta del live del que vino. */
    private record Incoming(GiftEvent event, String streamerId) {
    }
    private final int maxActionsPerTick;
    private final double tpsFloor;

    private final AtomicLong received = new AtomicLong();
    private final AtomicLong dropped = new AtomicLong();
    private final AtomicLong executed = new AtomicLong();

    private BukkitTask task;
    private volatile boolean degraded;

    /**
     * A dónde van los comentarios del live. No pasan por el resolver: un comentario no produce
     * ninguna acción en el mundo, solo se enseña. Hacerle dedup y tiers sería trabajo tirado
     * en el camino más caliente del plugin.
     */
    private volatile java.util.function.Consumer<GiftEvent> comments;

    public void setCommentSink(java.util.function.Consumer<GiftEvent> sink) {
        this.comments = sink;
    }

    public GiftDispatcher(JavaPlugin plugin,
                          ActionResolver resolver,
                          ActionExecutor executor,
                          int queueCapacity,
                          int maxActionsPerTick,
                          double tpsFloor) {
        this.plugin = plugin;
        this.resolver = resolver;
        this.executor = executor;
        this.queue = new ArrayBlockingQueue<>(Math.max(16, queueCapacity));
        this.maxActionsPerTick = Math.max(1, maxActionsPerTick);
        this.tpsFloor = tpsFloor;
    }

    /**
     * Único punto de entrada desde el hilo del WebSocket.
     * No toca Bukkit ni bloquea: encola y vuelve.
     */
    public void submit(GiftEvent event) {
        submit(event, "main");
    }

    /** @param streamerId id de la fuente (qué live mandó esto). */
    public void submit(GiftEvent event, String streamerId) {
        if (event == null) return;
        received.incrementAndGet();
        if (!queue.offer(new Incoming(event, streamerId))) {
            dropped.incrementAndGet();
        }
    }

    public void start() {
        stop();
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    private void tick() {
        double tps = Bukkit.getServer().getTPS()[0];
        degraded = tps < tpsFloor;
        executor.setDegraded(degraded);

        // En modo degradado seguimos vaciando la cola (para que no se atasque) pero el
        // executor convierte las acciones caras en un simple aviso.
        int budget = degraded ? 1 : maxActionsPerTick;

        for (int i = 0; i < budget; i++) {
            Incoming incoming = queue.poll();
            if (incoming == null) return;
            GiftEvent event = incoming.event();

            // Los comentarios se desvían antes del resolver: solo se muestran.
            if (event.type() == dev.juanse.tiktokmobs.core.event.EventType.COMMENT) {
                var sink = comments;
                if (sink != null) {
                    try {
                        sink.accept(event);
                    } catch (RuntimeException ex) {
                        plugin.getLogger().warning("comentario falló: " + ex);
                    }
                }
                continue;
            }

            List<ResolvedAction> actions;
            try {
                actions = resolver.resolve(event, incoming.streamerId());
            } catch (RuntimeException ex) {
                plugin.getLogger().warning("resolver falló con " + event.id() + ": " + ex);
                continue;
            }

            // La primera vez que aparece un regalo lo decimos con su id: es lo que hace falta
            // para mapearlo, y el nombre que ves en el chat no sirve (viene traducido).
            var firstSighting = resolver.takeFirstSighting();
            if (firstSighting != null) {
                plugin.getLogger().info("[gift] nuevo: '" + firstSighting.name()
                        + "' id=" + firstSighting.id()
                        + " coins=" + firstSighting.coins()
                        + (resolver.table().hasExplicit(firstSighting)
                        ? " (mapeado)" : " (sin mapeo propio: usa los tramos)"));
            }

            // El nivel de caos solo vale si se ve. Anunciarlo es la mitad del invento:
            // es lo que convierte donaciones sueltas en una carrera colectiva.
            int nivelNuevo = resolver.takeChaosLevelUp();
            int metasDeLikes = resolver.takeLikeGoalReached(incoming.streamerId());
            if (nivelNuevo > 0) {
                var chaos = resolver.chaos();
                String aviso = "&4&l☠ NIVEL DE CAOS " + nivelNuevo + " &7- todo sale x"
                        + String.format(java.util.Locale.ROOT, "%.2f", chaos.multiplier())
                        + (chaos.atMax() ? " &c(tope)" : "");
                for (var player : Bukkit.getOnlinePlayers()) {
                    player.sendMessage(Messages.color(aviso));
                }
                plugin.getLogger().info("[caos] nivel " + nivelNuevo
                        + " (x" + chaos.multiplier() + ")");
            }

            // La meta de likes: el unico objetivo en el que participa quien no gasta nada.
            // Se anuncia con su nombre porque "60.000 likes" no le dice nada a nadie y
            // "el WITHER sale a los 60.000" se explica solo.
            if (metasDeLikes > 0) {
                var meta = resolver.likeGoal(incoming.streamerId());
                String aviso = "&d&l\u2605 " + meta.label() + " &7- meta de &d"
                        + meta.target() + "&7 likes completada";
                for (var player : Bukkit.getOnlinePlayers()) {
                    player.sendMessage(Messages.color(aviso));
                }
                // Tope de 2, igual que la meta de donacion: un lote gigante no puede
                // encadenar diez premios y tumbar el tick.
                for (int premio = 0; premio < Math.min(metasDeLikes, 2); premio++) {
                    executor.runWorldEvent(resolver.table().likeGoalEvent(),
                            incoming.streamerId());
                }
                plugin.getLogger().info("[likes] meta '" + meta.label() + "' x" + metasDeLikes);
            }

            for (ResolvedAction action : actions) {
                try {
                    executor.execute(action);
                    executed.incrementAndGet();
                } catch (RuntimeException ex) {
                    // Una acción mal configurada no debe tumbar el bucle del live.
                    plugin.getLogger().warning("acción " + action.spec().kind()
                            + " falló: " + ex);
                }
            }
        }
    }

    public long received() {
        return received.get();
    }

    public long dropped() {
        return dropped.get();
    }

    public long executed() {
        return executed.get();
    }

    public int queued() {
        return queue.size();
    }

    public boolean degraded() {
        return degraded;
    }
}
