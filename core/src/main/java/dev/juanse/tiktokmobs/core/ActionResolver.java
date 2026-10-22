package dev.juanse.tiktokmobs.core;

import dev.juanse.tiktokmobs.core.action.ActionSpec;
import dev.juanse.tiktokmobs.core.action.ResolvedAction;
import dev.juanse.tiktokmobs.core.config.MappingTable;
import dev.juanse.tiktokmobs.core.dedup.DedupPolicy;
import dev.juanse.tiktokmobs.core.dedup.LikeAccumulator;
import dev.juanse.tiktokmobs.core.event.EventType;
import dev.juanse.tiktokmobs.core.event.GiftEvent;
import dev.juanse.tiktokmobs.core.event.GiftInfo;
import dev.juanse.tiktokmobs.core.util.NameSanitizer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * El cerebro: evento crudo → lista de acciones a ejecutar. Sin Bukkit, sin red, sin estado
 * global. Es la clase que puedes testear a fondo sin levantar un servidor, y es donde vale
 * la pena tener cobertura real.
 */
public final class ActionResolver {

    /** Techo de acciones generadas por un solo evento. Un lote de 5000 likes no rompe el tick. */
    private static final int MAX_ACTIONS_PER_EVENT = 5;

    private volatile MappingTable table;
    private final DedupPolicy dedup;
    private final int donacionesPorNivelDeCaos;
    /**
     * Una sesion por live. Se crea bajo demanda la primera vez que llega un evento con un
     * streamerId nuevo: asi un servidor con un solo stream solo tiene la entrada "main" y
     * se comporta exactamente igual que antes del cambio.
     */
    private final Map<String, StreamerSession> sessions = new ConcurrentHashMap<>();
    private final GiftRegistry gifts = new GiftRegistry();
    private volatile GiftInfo lastUnseen;
    /** Nivel al que acaba de subir el caos, por streamer. Se consume con takeChaosLevelUp. */
    private final Map<String, Integer> lastChaosLevelUp = new ConcurrentHashMap<>();

    /** Metas de likes recien completadas, por streamer. Se consume con takeLikeGoalReached. */
    private final Map<String, Integer> lastLikeGoal = new ConcurrentHashMap<>();

    public ActionResolver(MappingTable table, DedupPolicy dedup) {
        this(table, dedup, 20);
    }

    /** @param donacionesPorNivelDeCaos cada cuántas donaciones sube el nivel de caos */
    public ActionResolver(MappingTable table, DedupPolicy dedup, int donacionesPorNivelDeCaos) {
        this.table = table == null ? MappingTable.empty() : table;
        this.dedup = dedup == null ? new DedupPolicy(60_000L) : dedup;
        this.donacionesPorNivelDeCaos = donacionesPorNivelDeCaos;
    }

    private StreamerSession session(String streamerId) {
        String key = streamerId == null || streamerId.isBlank() ? "main" : streamerId;
        return sessions.computeIfAbsent(key, id -> new StreamerSession(
                id, table.donationGoalCoins(), donacionesPorNivelDeCaos, table.likeEvery(),
                20, table.likeGoal(), table.likeGoalLabel(),
                table.misionLabel(), table.misionObjetivo(), table.misionEscalada()));
    }

    public List<ResolvedAction> resolve(GiftEvent event) {
        return resolve(event, "main");
    }

    /**
     * @param streamerId de qué live viene el evento. Con varios streamers en el mismo
     *                   servidor, dos lives distintos pueden emitir el mismo msgId, así que
     *                   el dedup tiene que ser por fuente o se pisarían entre ellos.
     */
    public List<ResolvedAction> resolve(GiftEvent event, String streamerId) {
        if (event == null || !dedup.accept(event, streamerId)) return List.of();

        String donor = NameSanitizer.clean(event.user().rawLabel());
        StreamerSession session = session(streamerId);

        List<ResolvedAction> actions = switch (event.type()) {
            case GIFT -> giftActions(event, donor, streamerId);
            case LIKE -> likeActions(event, donor, streamerId, session);
            default -> simpleActions(event, donor, streamerId);
        };

        if (event.type() != EventType.GIFT || event.coins() <= 0) return actions;

        // Cada donación empuja el nivel de caos. Se apunta aquí, en el único sitio por el que
        // pasan todas, y no en el executor: una donación con cinco acciones sigue siendo una.
        int subioA = session.chaos().record();
        if (subioA > 0) lastChaosLevelUp.put(session.streamerId(), subioA);

        int goalsReached = session.ledger().record(donor, event.coins());
        if (goalsReached <= 0 || !table.goalEnabled()) return actions;

        List<ResolvedAction> withGoal = new ArrayList<>(actions);
        for (int i = 0; i < Math.min(goalsReached, 2); i++) {
            withGoal.add(new ResolvedAction(table.goalReward(), event, donor, event.coins(), streamerId));
        }
        return withGoal;
    }

    private List<ResolvedAction> giftActions(GiftEvent event, String donor, String streamerId) {
        GiftInfo gift = event.gift();
        if (gift != null) {
            // Apuntamos TODO regalo que entra, con su id. Es la única forma fiable de saber
            // qué mapear después, porque el nombre llega traducido al idioma de la cuenta.
            lastUnseen = gifts.record(gift, table.hasExplicit(gift)) ? gift : null;
        }

        return event.giftInfo()
                .flatMap(table::forGift)
                .map(spec -> List.of(new ResolvedAction(spec, event, donor, event.coins(), streamerId)))
                .orElse(List.of());
    }

    private List<ResolvedAction> likeActions(GiftEvent event, String donor, String streamerId,
                                             StreamerSession session) {
        int crossed = session.likes().add(event.likeCount());

        // La meta grande se mide sobre el TOTAL, no sobre los umbrales pequenios: un lote que
        // no llega a cruzar los 100 likes del 'every' puede perfectamente cruzar los 60.000
        // de la meta, y si lo metieramos tras el return de abajo se perderia el premio.
        int metas = session.likeGoal().record(session.likes().total());
        if (metas > 0) lastLikeGoal.put(session.streamerId(), metas);

        if (crossed <= 0) return List.of();

        Optional<ActionSpec> spec = table.forEventType(EventType.LIKE);
        if (spec.isEmpty()) return List.of();

        int repeats = Math.min(crossed, MAX_ACTIONS_PER_EVENT);
        List<ResolvedAction> actions = new ArrayList<>(repeats);
        for (int i = 0; i < repeats; i++) {
            actions.add(new ResolvedAction(spec.get(), event, donor, 0, streamerId));
        }
        return actions;
    }

    private List<ResolvedAction> simpleActions(GiftEvent event, String donor, String streamerId) {
        return table.forEventType(event.type())
                .map(spec -> List.of(new ResolvedAction(spec, event, donor, 0, streamerId)))
                .orElse(List.of());
    }

    public GiftRegistry gifts() {
        return gifts;
    }

    /** El regalo que se acaba de ver por primera vez, o null. Para loguearlo una sola vez. */
    public GiftInfo takeFirstSighting() {
        GiftInfo gift = lastUnseen;
        lastUnseen = null;
        return gift;
    }

    /** Atajo al live "main" mientras la UI siga siendo monousuario. */
    @Deprecated
    public LikeAccumulator likes() {
        return likes("main");
    }

    /** Atajo al live "main" mientras la UI siga siendo monousuario. */
    @Deprecated
    public ChaosLevel chaos() {
        return chaos("main");
    }

    /**
     * Atajo al live "main" mientras la UI siga siendo monousuario.
     *
     * @return el nivel al que acaba de subir el caos del live "main", o 0.
     */
    @Deprecated
    public int takeChaosLevelUp() {
        return takeChaosLevelUp("main");
    }

    /** Atajo al live "main" mientras la UI siga siendo monousuario. */
    @Deprecated
    public DonorLedger ledger() {
        return ledger("main");
    }

    // ------------------------------------------------------------------ per-streamer

    public DonorLedger ledger(String streamerId) {
        return session(streamerId).ledger();
    }

    public LifePool vidas(String streamerId) {
        return session(streamerId).vidas();
    }

    public ChaosLevel chaos(String streamerId) {
        return session(streamerId).chaos();
    }

    public LikeAccumulator likes(String streamerId) {
        return session(streamerId).likes();
    }

    /** El nivel al que acaba de subir el caos de ese live, o 0. Para anunciarlo una sola vez. */
    /**
     * Cuantas metas de likes se completaron desde la ultima vez que se pregunto, y las
     * descuenta. Mismo patron que el nivel de caos: quien lo consume lo anuncia una vez.
     */
    public int takeLikeGoalReached(String streamerId) {
        String key = streamerId == null || streamerId.isBlank() ? "main" : streamerId;
        Integer metas = lastLikeGoal.remove(key);
        return metas == null ? 0 : metas;
    }

    public Mission mision(String streamerId) {
        return session(streamerId).mision();
    }

    public LikeGoal likeGoal(String streamerId) {
        return session(streamerId).likeGoal();
    }

    public int takeChaosLevelUp(String streamerId) {
        String key = streamerId == null || streamerId.isBlank() ? "main" : streamerId;
        Integer nivel = lastChaosLevelUp.remove(key);
        return nivel == null ? 0 : nivel;
    }

    /**
     * Los lives con estado en este resolver, en el orden en el que llegaron. Lo usa el
     * {@code LedgerStore} para guardar cada sesion bajo su propia clave en disco.
     */
    public Set<String> streamerIds() {
        return Collections.unmodifiableSet(new LinkedHashSet<>(sessions.keySet()));
    }

    public MappingTable table() {
        return table;
    }

    /**
     * Cambia el catalogo en caliente, para los modos.
     *
     * <p>Las sesiones ya abiertas conservan su marcador, su nivel de caos y sus vidas: un
     * cambio de formato a mitad de directo no deberia borrar lo que el publico lleva
     * construido. Lo unico que cambia es a que accion mapea cada regalo de aqui en
     * adelante.
     */
    public void setTable(MappingTable nueva) {
        if (nueva != null) this.table = nueva;
    }

    public DedupPolicy dedup() {
        return dedup;
    }
}
