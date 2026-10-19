package dev.juanse.tiktokmobs.plugin.source;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import dev.juanse.tiktokmobs.core.event.EventType;
import dev.juanse.tiktokmobs.core.event.GiftEvent;
import dev.juanse.tiktokmobs.core.event.GiftInfo;
import dev.juanse.tiktokmobs.core.event.TikTokUser;

import java.util.Optional;

/**
 * Parser deliberadamente tolerante.
 *
 * <p>El formato de TikFinity no está documentado campo a campo y cambia entre versiones de la
 * app. Hardcodear un solo nombre por campo es garantizarte un fallo en vivo. Así que cada
 * campo se busca por varios alias, los tipos se aceptan laxos (booleano, 0/1 o "true") y lo
 * que no se reconoce se ignora en silencio en lugar de lanzar.
 *
 * <p>Para ver el JSON real de tu instalación: {@code /ttmobs raw} y dona una rosa.
 */
public final class TikFinityParser {

    private TikFinityParser() {
    }

    public static Optional<GiftEvent> parse(String json) {
        JsonObject root;
        try {
            JsonElement parsed = JsonParser.parseString(json);
            if (!parsed.isJsonObject()) return Optional.empty();
            root = parsed.getAsJsonObject();
        } catch (RuntimeException ex) {
            return Optional.empty();
        }

        String rawEvent = string(root, "event", "type", "eventType");
        JsonObject data = (root.has("data") && root.get("data").isJsonObject())
                ? root.getAsJsonObject("data")
                : root;

        EventType type = EventType.from(rawEvent);
        if (type == EventType.UNKNOWN) {
            // Sin campo 'event' reconocible: si trae datos de regalo, lo tratamos como regalo.
            if (has(data, "giftName", "giftname", "gift_name", "giftId", "giftid")) {
                type = EventType.GIFT;
            } else {
                return Optional.empty();
            }
        }

        String messageId = string(data, "msgId", "msgid", "msg_id", "messageId", "message_id", "id");
        if (messageId.isBlank()) messageId = string(root, "msgId", "msg_id", "id");

        TikTokUser user = new TikTokUser(
                string(data, "userId", "userid", "user_id", "uid"),
                string(data, "uniqueId", "uniqueid", "unique_id", "username", "user_name"),
                string(data, "nickname", "nickName", "nick_name", "displayName", "display_name", "nick"));

        return switch (type) {
            case GIFT -> Optional.of(GiftEvent.gift(messageId, user, gift(data)));
            case LIKE -> Optional.of(GiftEvent.like(messageId, user,
                    integer(data, 1, "likeCount", "likes", "count", "totalLikeCount")));
            case COMMENT -> {
                String texto = string(data, "comment", "message", "text", "content", "chatMessage");
                // Un comentario sin texto no es nada que enseñar: ni se encola.
                yield texto.isBlank()
                        ? Optional.empty()
                        : Optional.of(new GiftEvent(messageId, System.currentTimeMillis(),
                        EventType.COMMENT, user, null, 0, texto));
            }
            default -> Optional.of(GiftEvent.simple(messageId, type, user));
        };
    }

    private static GiftInfo gift(JsonObject data) {
        int giftId = integer(data, 0, "giftId", "giftid", "gift_id");
        String giftName = string(data, "giftName", "giftname", "gift_name", "name");
        int coins = integer(data, 0, "diamondCount", "diamond_count", "coins", "giftValue", "cost");
        int count = integer(data, 1, "repeatCount", "repeat_count", "giftCount", "count", "amount");

        // giftType == 1 es la convención de TikTok-Live-Connector (y por tanto de TikFinity)
        // para los regalos repetibles: los que emiten evento en cada golpe de la racha.
        boolean streakable = integer(data, 0, "giftType", "gift_type") == 1
                || bool(data, false, "streakable", "repeatable");

        // Si el campo no viene, asumimos cierre: es la opción segura (actuar una vez)
        // frente a quedarnos esperando un repeatEnd que nunca llega.
        boolean repeatEnd = bool(data, true, "repeatEnd", "repeat_end", "giftEnd");

        // La imagen del regalo viene en el propio evento: es la URL del CDN de TikTok, la
        // misma que ve TikFinity. Así el panel y el catálogo pueden enseñar el icono de
        // verdad sin que nosotros guardemos ni repartamos ninguna imagen ajena.
        String imagen = string(data, "giftPictureUrl", "giftPicture", "giftImage", "giftIcon",
                "image", "pictureUrl", "picture", "icon");

        return new GiftInfo(giftId, giftName, coins, count, streakable, repeatEnd, imagen);
    }

    private static boolean has(JsonObject object, String... keys) {
        for (String key : keys) {
            if (object.has(key) && !object.get(key).isJsonNull()) return true;
        }
        return false;
    }

    private static String string(JsonObject object, String... keys) {
        for (String key : keys) {
            JsonElement element = object.get(key);
            if (element != null && element.isJsonPrimitive()) {
                return element.getAsString();
            }
        }
        return "";
    }

    private static int integer(JsonObject object, int fallback, String... keys) {
        for (String key : keys) {
            JsonElement element = object.get(key);
            if (element == null || !element.isJsonPrimitive()) continue;
            JsonPrimitive primitive = element.getAsJsonPrimitive();
            try {
                if (primitive.isNumber()) return primitive.getAsInt();
                if (primitive.isString()) return Integer.parseInt(primitive.getAsString().trim());
            } catch (RuntimeException ignored) {
                // alias presente pero con basura dentro: probamos el siguiente
            }
        }
        return fallback;
    }

    private static boolean bool(JsonObject object, boolean fallback, String... keys) {
        for (String key : keys) {
            JsonElement element = object.get(key);
            if (element == null || !element.isJsonPrimitive()) continue;
            JsonPrimitive primitive = element.getAsJsonPrimitive();
            if (primitive.isBoolean()) return primitive.getAsBoolean();
            if (primitive.isNumber()) return primitive.getAsInt() != 0;
            if (primitive.isString()) {
                String value = primitive.getAsString().trim().toLowerCase(java.util.Locale.ROOT);
                if (value.equals("true") || value.equals("1")) return true;
                if (value.equals("false") || value.equals("0")) return false;
            }
        }
        return fallback;
    }
}
