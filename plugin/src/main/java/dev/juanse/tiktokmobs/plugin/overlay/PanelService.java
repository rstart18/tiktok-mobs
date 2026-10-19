package dev.juanse.tiktokmobs.plugin.overlay;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.juanse.tiktokmobs.core.GiftRegistry;
import dev.juanse.tiktokmobs.core.action.ActionKind;
import dev.juanse.tiktokmobs.core.action.ActionSpec;
import dev.juanse.tiktokmobs.plugin.TikTokMobsPlugin;
import dev.juanse.tiktokmobs.plugin.config.CatalogStore;
import dev.juanse.tiktokmobs.plugin.runtime.WorldEvents;
import org.bukkit.Bukkit;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Lo que hay detrás del panel web.
 *
 * <p>Traduce entre el mundo del plugin y un JSON que entiende la página: qué regalos han
 * entrado, qué hace cada uno ahora mismo, y qué opciones tiene para elegir.
 *
 * <p>Corre en los hilos del servidor HTTP, así que no toca nada de Bukkit salvo para encolar.
 * Lo único que necesita el hilo principal —recargar la configuración— se programa.
 */
public final class PanelService implements OverlayServer.PanelHooks {

    /**
     * Mobs que NO se queman de día.
     *
     * <p>Es la lista del panel y no todos los EntityType a propósito: si pones ZOMBIE o
     * SKELETON, de día arden y tus donantes ven humo en vez de mobs. Esa pregunta ya costó
     * un directo, así que la respuesta vive aquí y no en la cabeza de quien configura.
     */
    private static final List<String> MOBS = List.of(
            "HUSK", "SPIDER", "CAVE_SPIDER", "CREEPER", "ENDERMAN", "WITCH", "SLIME",
            "SILVERFISH", "CHICKEN", "PIG", "COW", "WOLF", "BAT", "VINDICATOR", "PILLAGER",
            "RAVAGER", "WARDEN", "BLAZE", "WITHER_SKELETON", "MAGMA_CUBE", "PHANTOM", "ALLAY");

    private static final List<String> ITEMS = List.of(
            "TOTEM_OF_UNDYING", "GOLDEN_APPLE", "ENCHANTED_GOLDEN_APPLE", "CAKE", "DIAMOND",
            "EMERALD", "IRON_INGOT", "COOKED_BEEF", "BREAD", "ENDER_PEARL", "TNT", "ARROW",
            "DIAMOND_SWORD", "DIAMOND_PICKAXE", "SHIELD", "ELYTRA", "FIREWORK_ROCKET");

    private final TikTokMobsPlugin plugin;
    private final CatalogStore store;
    private final Gson gson = new Gson();

    public PanelService(TikTokMobsPlugin plugin, CatalogStore store) {
        this.plugin = plugin;
        this.store = store;
    }

    @Override
    public String token() {
        return store.panelToken();
    }

    @Override
    public String data() {
        JsonObject raiz = new JsonObject();

        // Los regalos que han entrado en esta sesión, con su id real. Esto es lo que hace
        // util al panel: el nombre lo traduce TikTok, el id no.
        JsonArray regalos = new JsonArray();
        for (GiftRegistry.Seen visto : plugin.giftRegistry().seen()) {
            JsonObject item = new JsonObject();
            item.addProperty("id", visto.id());
            item.addProperty("nombre", visto.name());
            item.addProperty("monedas", visto.coins());
            item.addProperty("veces", visto.times());
            regalos.add(item);
        }
        raiz.add("regalos", regalos);

        JsonObject mapeos = new JsonObject();
        plugin.mappingByGiftId().forEach((id, spec) -> mapeos.add(String.valueOf(id), aJson(spec)));
        raiz.add("mapeos", mapeos);

        JsonObject opciones = new JsonObject();
        opciones.add("mobs", lista(MOBS));
        opciones.add("items", lista(ITEMS));
        opciones.add("eventos", lista(new java.util.TreeSet<>(WorldEvents.AVAILABLE)));
        opciones.add("jefes", lista(plugin.bossIds()));
        raiz.add("opciones", opciones);

        return raiz.toString();
    }

    private JsonObject aJson(ActionSpec spec) {
        JsonObject out = new JsonObject();
        out.addProperty("action", spec.kind().name().toLowerCase(java.util.Locale.ROOT));
        out.addProperty("valor", switch (spec.kind()) {
            case SPAWN, HORDE -> spec.mob();
            case GIVE -> spec.item();
            case WORLD_EVENT -> spec.worldEventId();
            case BOSS -> spec.isSuperBoss() ? spec.bossId() : spec.mob();
            case EFFECT -> spec.effect();
            case NONE -> "";
        });
        out.addProperty("count", spec.count());
        out.addProperty("perCoin", spec.perCoin());
        out.addProperty("maxCount", spec.maxCount());
        out.addProperty("label", spec.label());
        out.addProperty("message", spec.message());
        return out;
    }

    private JsonArray lista(Iterable<String> valores) {
        JsonArray array = new JsonArray();
        valores.forEach(array::add);
        return array;
    }

    // ------------------------------------------------------------------ guardar

    @Override
    public String save(String json) {
        List<Map<String, Object>> entradas = new ArrayList<>();
        int ignoradas = 0;

        try {
            JsonElement parsed = JsonParser.parseString(json);
            if (!parsed.isJsonObject()) return error("El panel mandó algo que no es un objeto.");

            JsonArray filas = parsed.getAsJsonObject().getAsJsonArray("entradas");
            if (filas == null) return error("Falta la lista de entradas.");

            for (JsonElement elemento : filas) {
                Map<String, Object> entrada = aEntrada(elemento.getAsJsonObject());
                if (entrada == null) {
                    ignoradas++;
                    continue;
                }
                entradas.add(entrada);
            }
        } catch (RuntimeException ex) {
            return error("No pude leer lo que mandó el panel: " + ex.getClass().getSimpleName());
        }

        int escritas = store.guardarMapeos(entradas);

        // La recarga toca el mundo, así que va al hilo principal. Y va DESPUÉS de responder:
        // reloadEverything levanta de nuevo este mismo servidor HTTP, y no se puede tirar el
        // servidor desde dentro de la petición que lo está usando.
        Bukkit.getScheduler().runTask(plugin, plugin::reloadEverything);

        String mensaje = escritas + " regalo(s) guardados. Recargando...";
        if (ignoradas > 0) mensaje += " (" + ignoradas + " fila(s) sin acción, ignoradas)";
        return ok(mensaje);
    }

    /** @return null si la fila no vale la pena guardar (sin id o sin acción). */
    private Map<String, Object> aEntrada(JsonObject fila) {
        int id = fila.has("id") ? fila.get("id").getAsInt() : 0;
        if (id <= 0) return null;

        String accion = texto(fila, "action");
        ActionKind kind = ActionKind.from(accion);
        if (kind == ActionKind.NONE) return null;

        String valor = texto(fila, "valor");
        if (valor.isBlank()) return null;

        Map<String, Object> entrada = new LinkedHashMap<>();
        entrada.put("id", id);
        entrada.put("action", accion);

        switch (kind) {
            case SPAWN, HORDE -> entrada.put("mob", valor);
            case GIVE -> entrada.put("item", valor);
            case WORLD_EVENT -> entrada.put("event", valor);
            case EFFECT -> entrada.put("effect", valor);
            case BOSS -> {
                // Si el valor es uno de los jefes del config va como 'boss'; si no, es un mob
                // normal con vida escalada, que es el BOSS simple de siempre.
                if (plugin.bossIds().contains(valor)) {
                    entrada.put("boss", valor);
                } else {
                    entrada.put("mob", valor);
                }
            }
            default -> {
                return null;
            }
        }

        int count = numero(fila, "count", 1);
        double perCoin = fila.has("perCoin") ? fila.get("perCoin").getAsDouble() : 0.0;
        int maxCount = numero(fila, "maxCount", 0);

        if (count > 1) entrada.put("count", count);
        if (perCoin > 0) entrada.put("per-coin", perCoin);
        if (maxCount > 0) entrada.put("max-count", maxCount);

        String label = texto(fila, "label");
        String message = texto(fila, "message");
        int monedas = numero(fila, "monedas", 0);
        if (!label.isBlank()) entrada.put("label", label);
        if (!message.isBlank()) entrada.put("message", message);
        if (monedas > 0) entrada.put("coins", monedas);

        return entrada;
    }

    private String texto(JsonObject objeto, String clave) {
        JsonElement valor = objeto.get(clave);
        return valor == null || valor.isJsonNull() ? "" : valor.getAsString().trim();
    }

    private int numero(JsonObject objeto, String clave, int fallback) {
        try {
            JsonElement valor = objeto.get(clave);
            return valor == null || valor.isJsonNull() ? fallback : valor.getAsInt();
        } catch (RuntimeException ex) {
            return fallback;
        }
    }

    // ------------------------------------------------------------------ probar

    @Override
    public String test(int giftId, int coins) {
        if (giftId <= 0) return error("Falta el id del regalo.");

        // Inyecta por la misma puerta que una donación real: si funciona esto, funciona en vivo.
        plugin.fake().emitGift("panel", giftId, "prueba-" + giftId, Math.max(1, coins), 1);
        return ok("Regalo " + giftId + " inyectado con " + Math.max(1, coins) + " monedas.");
    }

    private String ok(String mensaje) {
        JsonObject out = new JsonObject();
        out.addProperty("ok", true);
        out.addProperty("mensaje", mensaje);
        return out.toString();
    }

    private String error(String mensaje) {
        JsonObject out = new JsonObject();
        out.addProperty("ok", false);
        out.addProperty("mensaje", mensaje);
        return out.toString();
    }
}
