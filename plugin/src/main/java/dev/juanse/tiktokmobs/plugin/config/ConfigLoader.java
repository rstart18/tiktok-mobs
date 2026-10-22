package dev.juanse.tiktokmobs.plugin.config;

import dev.juanse.tiktokmobs.core.action.ActionKind;
import dev.juanse.tiktokmobs.core.action.ActionSpec;
import dev.juanse.tiktokmobs.core.boss.BossDefinition;
import dev.juanse.tiktokmobs.core.config.MappingTable;
import dev.juanse.tiktokmobs.core.event.EventType;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Logger;

/** Traduce config.yml a objetos del core. Es el único sitio del proyecto que sabe de YAML. */
public final class ConfigLoader {

    private ConfigLoader() {
    }

    public static PluginSettings load(FileConfiguration cfg, Logger log) {
        return load(cfg, null, log);
    }

    /**
     * @param panel el catalogo.yml que escribió el panel web, o null. Se aplica DESPUÉS del
     *              config: sus 'gifts' y 'gift-ids' mandan sobre los del config, y si trae
     *              'tiers' esos SUSTITUYEN a la escalera del config entera.
     */
    public static PluginSettings load(FileConfiguration cfg, ConfigurationSection panel,
                                      Logger log) {
        PANEL.set(panel);
        try {
            return construir(cfg, log);
        } finally {
            PANEL.remove();
        }
    }

    /**
     * Se pasa por un ThreadLocal y no por parámetro para no arrastrar el bloque del panel por
     * toda la cadena de métodos privados, que ya tiene bastantes argumentos.
     */
    private static final ThreadLocal<ConfigurationSection> PANEL = new ThreadLocal<>();

    private static PluginSettings construir(FileConfiguration cfg, Logger log) {
        PluginSettings.SourceType sourceType = enumOf(
                PluginSettings.SourceType.class,
                cfg.getString("source.type", "fake"),
                PluginSettings.SourceType.FAKE,
                log, "source.type");

        PluginSettings.TargetMode targetMode = enumOf(
                PluginSettings.TargetMode.class,
                cfg.getString("target.mode", "first-online"),
                PluginSettings.TargetMode.FIRST_ONLINE,
                log, "target.mode");

        return new PluginSettings(
                sourceType,
                cfg.getString("source.tikfinity.url", "ws://127.0.0.1:21213/"),
                cfg.getInt("source.tikfinity.reconnect-max-seconds", 60),
                targetMode,
                cfg.getString("target.player-name", ""),
                cfg.getString("target.location.world", "world"),
                cfg.getDouble("target.location.x", 0.5),
                cfg.getDouble("target.location.y", 64.0),
                cfg.getDouble("target.location.z", 0.5),
                Math.max(1, cfg.getInt("limits.max-live-spawned", 200)),
                Math.max(1, cfg.getInt("limits.max-actions-per-tick", 4)),
                cfg.getDouble("limits.tps-floor", 17.0),
                Math.max(16, cfg.getInt("limits.queue-capacity", 500)),
                Math.max(5, cfg.getInt("limits.dedup-ttl-seconds", 120)) * 1000L,
                cfg.getBoolean("feedback.broadcast", true),
                cfg.getBoolean("feedback.scoreboard", true),
                Math.max(1, cfg.getInt("feedback.scoreboard-size", 5)),
                cfg.getBoolean("feedback.goal-bar", true),
                cfg.getBoolean("feedback.title", true),
                Math.max(0L, Math.round(cfg.getDouble("feedback.title-gap-seconds", 1.5) * 1000)),
                Math.max(1, cfg.getInt("chaos.every-donations", 20)),
                cfg.getBoolean("chat.enabled", true),
                Math.max(1, cfg.getInt("chat.max-per-second", 3)),
                Math.max(0, cfg.getInt("chat.min-coins", 0)),
                cfg.getBoolean("overlay.enabled", true),
                cfg.getString("overlay.bind", "127.0.0.1"),
                cfg.getInt("overlay.port", 8787),
                cfg.getString("overlay.token", ""),
                Math.max(0, cfg.getInt("overlay.alert-min-coins", 500)),
                Math.max(10L, cfg.getLong("overlay.state-refresh-ticks", 40L)),
                sources(cfg, log),
                bosses(cfg, log),
                mapping(cfg, log));
    }

    /**
     * Lee el bloque {@code bosses:}.
     *
     * <p>Que esto sea config y no código es el punto: cambiar de jefe tiene que ser cambiar el
     * .bbmodel y estas líneas, sin recompilar nada ni esperar a que yo esté disponible.
     */
    private static Map<String, BossDefinition> bosses(FileConfiguration cfg, Logger log) {
        ConfigurationSection section = cfg.getConfigurationSection("bosses");
        if (section == null) return Map.of();

        Map<String, BossDefinition> out = new java.util.LinkedHashMap<>();

        for (String id : section.getKeys(false)) {
            ConfigurationSection boss = section.getConfigurationSection(id);
            if (boss == null) continue;

            BossDefinition.Builder builder = BossDefinition.builder(id)
                    .baseEntity(boss.getString("base-entity", "WITHER_SKELETON"))
                    .modelId(boss.getString("model-id", ""))
                    .hpBase(boss.getDouble("hp-base", 40.0))
                    .hpPerCoin(boss.getDouble("hp-per-coin", 0.5))
                    .hpMax(boss.getDouble("hp-max", 300.0))
                    .titleOne(boss.getString("title-one", ""))
                    .titleMany(boss.getString("title-many", ""))
                    .suspenseTicks((int) Math.round(boss.getDouble("suspense-seconds", 3.0) * 20))
                    .minions(boss.getDouble("phases.minions.at-health", 0.5),
                            boss.getInt("phases.minions.min", 2),
                            boss.getInt("phases.minions.max", 3),
                            boss.getString("phases.minions.entity", ""))
                    .rageAt(boss.getDouble("phases.rage.at-health", 0.2));

            for (Map<?, ?> raw : boss.getMapList("gear")) {
                builder.gear(new BossDefinition.Gear(
                        intFrom(raw.get("min-coins"), 0),
                        stringFrom(raw.get("weapon")),
                        stringFrom(raw.get("helmet")),
                        stringFrom(raw.get("chest")),
                        stringFrom(raw.get("legs")),
                        stringFrom(raw.get("boots")),
                        intFrom(raw.get("enchant-level"), 0)));
            }

            out.put(id, builder.build());
        }

        if (!out.isEmpty()) {
            log.info("[config] jefes definidos: " + String.join(", ", out.keySet()));
        }
        return Map.copyOf(out);
    }

    /**
     * Lee la lista de lives conectados. Si no hay bloque 'sources', se construye uno con la
     * fuente de siempre, para que un config viejo siga funcionando sin tocar nada.
     */
    private static List<StreamSource> sources(FileConfiguration cfg, Logger log) {
        List<StreamSource> sources = new java.util.ArrayList<>();

        for (Map<?, ?> raw : cfg.getMapList("sources")) {
            String id = stringFrom(raw.get("id"));
            if (id.isBlank()) {
                log.warning("[config] una entrada de 'sources' no tiene 'id': ignorada");
                continue;
            }
            sources.add(new StreamSource(id, stringFrom(raw.get("url")), stringFrom(raw.get("player"))));
        }

        if (sources.isEmpty()) {
            String url = cfg.getString("source.type", "fake").equalsIgnoreCase("tikfinity")
                    ? cfg.getString("source.tikfinity.url", "ws://127.0.0.1:21213/")
                    : "";
            sources.add(new StreamSource("main", url, cfg.getString("target.player-name", "")));
        }

        long ids = sources.stream().map(StreamSource::id).distinct().count();
        if (ids != sources.size()) {
            log.warning("[config] hay 'sources' con el mismo id: el dedup los mezclara");
        }
        return List.copyOf(sources);
    }

    private static MappingTable mapping(FileConfiguration cfg, Logger log) {
        MappingTable.Builder builder = MappingTable.builder()
                .likeEvery(cfg.getInt("likes.every", 100))
                .likeGoal(cfg.getInt("likes.goal", 0))
                .likeGoalLabel(cfg.getString("likes.goal-label", "META"))
                .likeGoalEvent(cfg.getString("likes.goal-event", ""))
                .misionLabel(cfg.getString("mision.label", "MISION"))
                .misionObjetivo(cfg.getInt("mision.objetivo", 0))
                .misionBloques(cfg.getString("mision.bloques",
                        "DIAMOND_ORE,DEEPSLATE_DIAMOND_ORE"))
                .misionEscalada(cfg.getInt("mision.escalada", 0))
                .misionVidas(cfg.getInt("mision.vidas", 3))
                .fases(leerFases(cfg, log))
                .fasesCiclicas(cfg.getBoolean("fases.ciclico", false));

        ConfigurationSection gifts = cfg.getConfigurationSection("gifts");
        if (gifts != null) {
            for (String giftName : gifts.getKeys(false)) {
                ActionSpec spec = spec(gifts.getConfigurationSection(giftName), log, "gifts." + giftName);
                if (spec != null) builder.gift(giftName, spec);
            }
        }

        ConfigurationSection giftIds = cfg.getConfigurationSection("gift-ids");
        if (giftIds != null) {
            leerGiftIds(giftIds, builder, log, "config");
        } else {
            log.info("[config] no hay bloque 'gift-ids': todo depende de los nombres y los tramos");
        }

        // El panel va DESPUÉS a propósito: lo que se toca desde la web manda sobre el config.
        ConfigurationSection panel = PANEL.get();
        if (panel != null) {
            ConfigurationSection panelNombres = panel.getConfigurationSection("gifts");
            if (panelNombres != null) {
                for (String giftName : panelNombres.getKeys(false)) {
                    ActionSpec spec = spec(panelNombres.getConfigurationSection(giftName), log,
                            "panel.gifts." + giftName);
                    if (spec != null) builder.gift(giftName, spec);
                }
            }

            ConfigurationSection panelIds = panel.getConfigurationSection("gift-ids");
            if (panelIds != null) {
                leerGiftIds(panelIds, builder, log, "panel");
            }
        }

        // Los tramos del panel SUSTITUYEN a los del config, no se suman: si se mezclaran,
        // cada guardado desde la web duplicaria la escalera entera.
        ConfigurationSection panelRoot = PANEL.get();
        List<Map<?, ?>> tiersDelPanel = panelRoot == null
                ? List.of() : panelRoot.getMapList("tiers");
        boolean mandaElPanel = !tiersDelPanel.isEmpty();

        List<Map<?, ?>> tiers = mandaElPanel ? tiersDelPanel : cfg.getMapList("tiers");
        String origenTramos = mandaElPanel ? "panel" : "config";

        for (Map<?, ?> raw : tiers) {
            int maxCoins = intFrom(raw.get("max-coins"), Integer.MAX_VALUE);
            ActionSpec spec = specFromMap(raw, log,
                    origenTramos + ".tiers[max-coins=" + maxCoins + "]");
            if (spec != null) builder.tier(maxCoins, spec);
        }
        if (mandaElPanel) {
            log.info("[panel] " + tiers.size() + " tramo(s) sustituyen a los del config");
        }

        ActionSpec likeSpec = spec(cfg.getConfigurationSection("likes"), log, "likes");
        if (likeSpec != null) builder.on(EventType.LIKE, likeSpec);

        ActionSpec followSpec = spec(cfg.getConfigurationSection("follow"), log, "follow");
        if (followSpec != null) builder.on(EventType.FOLLOW, followSpec);

        ActionSpec shareSpec = spec(cfg.getConfigurationSection("share"), log, "share");
        if (shareSpec != null) builder.on(EventType.SHARE, shareSpec);

        ActionSpec subSpec = spec(cfg.getConfigurationSection("subscribe"), log, "subscribe");
        if (subSpec != null) builder.on(EventType.SUBSCRIBE, subSpec);

        ActionSpec goalReward = spec(cfg.getConfigurationSection("goal.reward"), log, "goal.reward");
        if (goalReward != null) builder.goal(cfg.getInt("goal.coins", 0), goalReward);

        MappingTable table = builder.build();
        if (table.tierCount() == 0) {
            log.warning("[config] no hay 'tiers' definidos: los regalos que no estén en 'gifts' "
                    + "no harán nada. Define al menos un tramo.");
        }
        return table;
    }

    /**
     * Lee un bloque de mapeos por id y lo mete en la tabla.
     *
     * <p>Registra en el log lo que ENTRA de verdad. Un mapeo que se cae en silencio es la peor
     * clase de fallo: en vivo solo ves que "ese regalo no hace nada", y a ciegas te puedes
     * pasar dos directos culpando al regalo equivocado.
     */
    private static void leerGiftIds(ConfigurationSection giftIds, MappingTable.Builder builder,
                                    Logger log, String origen) {
        List<String> cargados = new java.util.ArrayList<>();

        for (String rawId : giftIds.getKeys(false)) {
            int id;
            try {
                id = Integer.parseInt(rawId.trim());
            } catch (NumberFormatException ex) {
                log.warning("[" + origen + "] gift-ids." + rawId + " no es un id numérico: ignorado");
                continue;
            }

            ConfigurationSection section = giftIds.getConfigurationSection(rawId);
            if (section == null) {
                log.warning("[" + origen + "] gift-ids." + rawId + " no se pudo leer como bloque. "
                        + "¿Usaste una referencia YAML (*algo)? Escribe el bloque completo.");
                continue;
            }

            ActionSpec spec = spec(section, log, origen + ".gift-ids." + rawId);
            if (spec == null) continue;

            builder.gift(id, spec);
            cargados.add(id + "=" + spec.describe());
        }

        log.info("[" + origen + "] gift-ids cargados (" + cargados.size() + "): "
                + String.join(", ", cargados));
    }

    private static ActionSpec spec(ConfigurationSection section, Logger log, String path) {
        if (section == null) return null;

        ActionKind kind = ActionKind.from(section.getString("action", "none"));
        if (kind == ActionKind.NONE) return null;

        ActionSpec spec = ActionSpec.of(kind)
                .mob(section.getString("mob", ""))
                .count(section.getInt("count", 1))
                .radius(section.getInt("radius", 5))
                .nameplate(section.getBoolean("nameplate", true))
                .healthScale(section.getDouble("health-scale", 1.0))
                .worldEvent(section.getString("event", ""))
                .effect(section.getString("effect", ""))
                .seconds(section.getInt("seconds", 10))
                .message(section.getString("message", ""))
                .label(section.getString("label", ""))
                .displayCoins(section.getInt("coins", 0))
                .perCoin(section.getDouble("per-coin", 0.0))
                .maxCount(section.getInt("max-count", 0))
                .item(section.getString("item", ""))
                .bossId(section.getString("boss", ""))
                .minCoins(section.getInt("min-coins", 0))
                .build();

        return validate(spec, log, path);
    }

    private static ActionSpec specFromMap(Map<?, ?> raw, Logger log, String path) {
        ActionKind kind = ActionKind.from(stringFrom(raw.get("action")));
        if (kind == ActionKind.NONE) return null;

        ActionSpec spec = ActionSpec.of(kind)
                .mob(stringFrom(raw.get("mob")))
                .count(intFrom(raw.get("count"), 1))
                .radius(intFrom(raw.get("radius"), 5))
                .nameplate(!Boolean.FALSE.equals(raw.get("nameplate")))
                .healthScale(doubleFrom(raw.get("health-scale"), 1.0))
                .worldEvent(stringFrom(raw.get("event")))
                .effect(stringFrom(raw.get("effect")))
                .seconds(intFrom(raw.get("seconds"), 10))
                .message(stringFrom(raw.get("message")))
                .label(stringFrom(raw.get("label")))
                .displayCoins(intFrom(raw.get("coins"), 0))
                .perCoin(doubleFrom(raw.get("per-coin"), 0.0))
                .maxCount(intFrom(raw.get("max-count"), 0))
                .item(stringFrom(raw.get("item")))
                .bossId(stringFrom(raw.get("boss")))
                .minCoins(intFrom(raw.get("min-coins"), 0))
                .build();

        return validate(spec, log, path);
    }

    /** Falla temprano y en voz alta: un mob mal escrito tiene que verse al arrancar, no en vivo. */
    private static ActionSpec validate(ActionSpec spec, Logger log, String path) {
        // Un BOSS con 'boss:' saca su entidad del bloque 'bosses:', así que no necesita 'mob'.
        boolean needsMob = spec.kind() == ActionKind.SPAWN
                || spec.kind() == ActionKind.HORDE
                || (spec.kind() == ActionKind.BOSS && !spec.isSuperBoss());

        if (needsMob && spec.mob().isBlank()) {
            log.warning("[config] " + path + ": accion '" + spec.kind() + "' sin 'mob': ignorada");
            return null;
        }
        if (spec.kind() == ActionKind.EFFECT && spec.effect().isBlank()) {
            log.warning("[config] " + path + ": accion 'effect' sin 'effect': ignorada");
            return null;
        }
        if (spec.kind() == ActionKind.GIVE && spec.item().isBlank()) {
            log.warning("[config] " + path + ": accion 'give' sin 'item': ignorada");
            return null;
        }
        if (spec.kind() == ActionKind.WORLD_EVENT && spec.worldEventId().isBlank()) {
            log.warning("[config] " + path + ": accion 'world_event' sin 'event': ignorada");
            return null;
        }
        return spec;
    }

    private static <E extends Enum<E>> E enumOf(Class<E> type, String raw, E fallback,
                                                Logger log, String path) {
        if (raw == null || raw.isBlank()) return fallback;
        String normalized = raw.trim().toUpperCase(Locale.ROOT).replace('-', '_');
        try {
            return Enum.valueOf(type, normalized);
        } catch (IllegalArgumentException ex) {
            log.warning("[config] " + path + " = '" + raw + "' no es válido, uso " + fallback);
            return fallback;
        }
    }

    private static String stringFrom(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static int intFrom(Object value, int fallback) {
        if (value instanceof Number number) return number.intValue();
        try {
            return Integer.parseInt(stringFrom(value).trim());
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private static double doubleFrom(Object value, double fallback) {
        if (value instanceof Number number) return number.doubleValue();
        try {
            return Double.parseDouble(stringFrom(value).trim());
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    /**
     * Las fases del formato. Sin bloque {@code fases:} la lista queda vacia y el plugin se
     * comporta como siempre: las donaciones pasan todas, sin reloj.
     */
    private static java.util.List<dev.juanse.tiktokmobs.core.PhasePlan.Fase> leerFases(
            ConfigurationSection cfg, java.util.logging.Logger log) {

        java.util.List<?> crudas = cfg.getList("fases.lista");
        if (crudas == null || crudas.isEmpty()) return java.util.List.of();

        java.util.List<dev.juanse.tiktokmobs.core.PhasePlan.Fase> out = new java.util.ArrayList<>();
        for (Object cruda : crudas) {
            if (!(cruda instanceof java.util.Map<?, ?> mapa)) continue;
            Object nombre = mapa.get("nombre");
            Object segundos = mapa.get("segundos");
            Object donaciones = mapa.get("donaciones");
            out.add(new dev.juanse.tiktokmobs.core.PhasePlan.Fase(
                    nombre == null ? "FASE" : String.valueOf(nombre),
                    segundos instanceof Number n ? n.intValue() : 0,
                    !(donaciones instanceof Boolean b) || b));
        }
        if (log != null && !out.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (var f : out) {
                if (sb.length() > 0) sb.append(" -> ");
                sb.append(f.nombre()).append('(')
                  .append(f.infinita() ? "sin limite" : f.segundos() + "s")
                  .append(f.donaciones() ? ", donaciones ON" : ", donaciones OFF").append(')');
            }
            log.info("[fases] " + out.size() + " fase(s): " + sb);
        }
        return out;
    }

}
