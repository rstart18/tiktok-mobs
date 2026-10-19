package dev.juanse.tiktokmobs.plugin.compat;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.lang.reflect.Method;
import java.util.UUID;

/**
 * Los plugins solo existen en Java Edition. Un cliente Bedrock entra a este servidor a través
 * de Geyser, y Floodgate es quien le da identidad. Nada de eso es obligatorio para que el
 * plugin funcione, así que la detección va por reflexión: si Floodgate no está, no pasa nada.
 *
 * <p>Lo que se ve igual en Bedrock: nameplates sobre los mobs, barras de jefe, barra de meta,
 * scoreboard lateral, mensajes de chat y efectos de poción. Lo que no: cualquier cosa que
 * dependa de un resource pack de Java, y el hover/click en componentes del chat.
 */
public final class BedrockCompat {

    private static final Object FLOODGATE_API;
    private static final Method IS_FLOODGATE_PLAYER;

    static {
        Object api = null;
        Method method = null;
        try {
            Class<?> type = Class.forName("org.geysermc.floodgate.api.FloodgateApi");
            api = type.getMethod("getInstance").invoke(null);
            method = type.getMethod("isFloodgatePlayer", UUID.class);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // Servidor sin Geyser/Floodgate: perfectamente normal.
        }
        FLOODGATE_API = api;
        IS_FLOODGATE_PLAYER = method;
    }

    private BedrockCompat() {
    }

    public static boolean available() {
        return FLOODGATE_API != null && IS_FLOODGATE_PLAYER != null;
    }

    public static boolean isBedrock(UUID uuid) {
        if (!available() || uuid == null) return false;
        try {
            return Boolean.TRUE.equals(IS_FLOODGATE_PLAYER.invoke(FLOODGATE_API, uuid));
        } catch (ReflectiveOperationException | RuntimeException ex) {
            return false;
        }
    }

    public static int bedrockOnline() {
        if (!available()) return 0;
        int count = 0;
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (isBedrock(player.getUniqueId())) count++;
        }
        return count;
    }

    public static String describe() {
        if (!available()) return "Floodgate no detectado (solo Java Edition)";
        return "Floodgate activo, " + bedrockOnline() + " jugador(es) Bedrock conectados";
    }
}
