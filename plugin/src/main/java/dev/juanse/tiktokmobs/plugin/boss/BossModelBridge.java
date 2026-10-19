package dev.juanse.tiktokmobs.plugin.boss;

import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Optional;
import java.util.logging.Logger;

/**
 * La capa opcional de modelo personalizado.
 *
 * <p>Decisión: <b>BetterModel</b>, no ModelEngine. BetterModel declara soporte de 1.21.4 a
 * 26.2.x y Java 25 —justo nuestro objetivo—, es MIT y gratis, y publica API en Maven Central.
 * ModelEngine es de pago, de código cerrado y no hay confirmación pública de que corra en 26.2.
 *
 * <p>Se habla con él <b>por reflexión</b>, igual que con Floodgate, y por el mismo motivo:
 * así el jar no declara ninguna dependencia nueva, carga igual en un servidor sin BetterModel
 * y no hay forma de que un cambio de versión del plugin ajeno impida arrancar el nuestro.
 *
 * <p>Sin BetterModel instalado, o con {@code model-id} vacío, el jefe es la entidad vanilla
 * equipada. Esa ruta es la que se prueba en vivo y la que ven los jugadores de Bedrock por
 * Geyser, que no reciben el modelo (BetterModel no documenta soporte Bedrock; GeyserModelEngine
 * existe pero es para ModelEngine). Para ellos la pelea es idéntica: cambia el aspecto.
 */
public interface BossModelBridge {

    /** Monta el modelo sobre la entidad ya spawneada. @return true si se montó de verdad. */
    boolean mount(Entity entity, String modelId);

    String describe();

    BossModelBridge NONE = new BossModelBridge() {
        @Override
        public boolean mount(Entity entity, String modelId) {
            return false;
        }

        @Override
        public String describe() {
            return "sin capa de modelo (los jefes salen en vanilla)";
        }
    };

    /** Mira qué hay instalado y devuelve el puente que toque, o NONE. */
    static BossModelBridge detect(Logger log) {
        if (Bukkit.getPluginManager().getPlugin("BetterModel") == null) return NONE;

        BossModelBridge bridge = Reflective.tryBuild(log);
        if (bridge == null) {
            log.warning("[boss] BetterModel está instalado pero no reconozco su API en esta "
                    + "versión. Los jefes saldrán en vanilla. Dime la versión exacta y lo ajusto.");
            return NONE;
        }
        log.info("[boss] " + bridge.describe());
        return bridge;
    }

    /**
     * Puente reflexivo contra BetterModel.
     *
     * <p>Honestidad por delante: la forma exacta del API no está verificada contra una
     * instalación real, así que esto busca la firma documentada y, si no la encuentra o si la
     * llamada falla, devuelve false y el jefe se queda vanilla. Nunca lanza hacia arriba.
     */
    final class Reflective implements BossModelBridge {

        private static final List<String> ENTRY_POINTS = List.of(
                "kr.toxicity.model.api.BetterModel",
                "kr.toxicity.model.api.BetterModelPlugin");

        private final Method modelLookup;
        private final Logger log;
        private volatile boolean warned;

        private Reflective(Method modelLookup, Logger log) {
            this.modelLookup = modelLookup;
            this.log = log;
        }

        static Reflective tryBuild(Logger log) {
            for (String className : ENTRY_POINTS) {
                try {
                    Class<?> type = Class.forName(className);
                    Method method = type.getMethod("model", String.class);
                    if (java.lang.reflect.Modifier.isStatic(method.getModifiers())) {
                        return new Reflective(method, log);
                    }
                } catch (ReflectiveOperationException | LinkageError ignored) {
                    // Siguiente candidato.
                }
            }
            return null;
        }

        @Override
        public boolean mount(Entity entity, String modelId) {
            try {
                Object model = modelLookup.invoke(null, modelId);
                if (model instanceof Optional<?> maybe) {
                    model = maybe.orElse(null);
                }
                if (model == null) {
                    warnOnce("[boss] BetterModel no tiene ningún modelo con id '" + modelId + "'");
                    return false;
                }
                return create(model, entity);
            } catch (ReflectiveOperationException | LinkageError | RuntimeException ex) {
                warnOnce("[boss] no pude montar el modelo '" + modelId + "': " + ex);
                return false;
            }
        }

        /** Busca el método que ata el modelo a la entidad. Los nombres cambian entre versiones. */
        private boolean create(Object model, Entity entity) throws ReflectiveOperationException {
            for (Method method : model.getClass().getMethods()) {
                if (method.getParameterCount() != 1) continue;
                if (!method.getParameterTypes()[0].isAssignableFrom(entity.getClass())
                        && !method.getParameterTypes()[0].isAssignableFrom(Entity.class)) {
                    continue;
                }
                String name = method.getName();
                if (name.equals("create") || name.equals("getOrCreate") || name.equals("apply")) {
                    method.invoke(model, entity);
                    return true;
                }
            }
            warnOnce("[boss] el modelo existe pero no encuentro cómo atarlo a la entidad");
            return false;
        }

        /** Un aviso por sesión: en vivo, un warning por donación llena el log y esconde lo bueno. */
        private void warnOnce(String message) {
            if (warned) return;
            warned = true;
            log.warning(message);
        }

        @Override
        public String describe() {
            return "BetterModel detectado (capa de modelo activa, sin verificar contra tu versión)";
        }
    }
}
