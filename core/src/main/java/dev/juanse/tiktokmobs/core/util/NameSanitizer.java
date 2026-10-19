package dev.juanse.tiktokmobs.core.util;

import java.util.regex.Pattern;

/**
 * El nick de TikTok es input de un desconocido y va a terminar en un nameplate y en el chat.
 * Sin esto te comen por tres lados:
 *
 * <ul>
 *   <li>códigos de color (§c, &amp;l) que te rompen el formato del chat;</li>
 *   <li>saltos de línea y caracteres de control que te parten el mensaje en dos;</li>
 *   <li>y lo peor: si alguna vez pasas el nick a {@code dispatchCommand}, un nick con
 *       {@code ; } o {@code /} es inyección de comandos directa.</li>
 * </ul>
 *
 * Regla del proyecto: ningún nick llega a la API de Bukkit sin pasar por {@link #clean(String)}.
 */
public final class NameSanitizer {

    /** Límite práctico: lo que se lee bien en un nameplate y en un scoreboard. */
    public static final int MAX_LENGTH = 16;

    private static final Pattern COLOR_CODES = Pattern.compile("(?i)[§&][0-9A-FK-ORX]");
    private static final Pattern CONTROL = Pattern.compile("[\\p{Cntrl}\\p{Cf}]");
    private static final Pattern DISALLOWED = Pattern.compile("[^\\p{L}\\p{N}_. -]");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    private NameSanitizer() {
    }

    public static String clean(String raw) {
        if (raw == null) return "anon";

        String s = COLOR_CODES.matcher(raw).replaceAll("");
        // los controles se sustituyen por espacio, no se borran: así "hola\nadmin" no
        // se convierte en "holaadmin" y nadie puede fabricar un nick pegando palabras.
        s = CONTROL.matcher(s).replaceAll(" ");
        s = DISALLOWED.matcher(s).replaceAll("");
        s = WHITESPACE.matcher(s).replaceAll(" ").strip();

        // Floodgate prefija los nicks de Bedrock con '.' por defecto; fuera para mostrar.
        while (s.startsWith(".")) {
            s = s.substring(1).strip();
        }

        if (s.isEmpty()) return "anon";
        if (s.length() > MAX_LENGTH) s = s.substring(0, MAX_LENGTH).strip();
        return s.isEmpty() ? "anon" : s;
    }

    /** Lo que cabe de un comentario sin partir el chat en cuatro líneas. */
    public static final int MAX_MESSAGE_LENGTH = 120;

    /**
     * Limpia el texto de un comentario del live.
     *
     * <p>Es más permisivo que {@link #clean(String)} a propósito: un comentario lleva signos,
     * interrogaciones y emojis, y filtrarlos como a un nick lo dejaría ilegible. Lo que sí se
     * va es lo que puede hacer daño: códigos de color y caracteres de control, que es como un
     * viewer te falsifica una línea de chat o te parte el mensaje en dos.
     *
     * @return el texto limpio, o cadena vacía si no quedaba nada que enseñar
     */
    public static String cleanMessage(String raw) {
        if (raw == null) return "";

        String s = COLOR_CODES.matcher(raw).replaceAll("");
        s = CONTROL.matcher(s).replaceAll(" ");
        s = WHITESPACE.matcher(s).replaceAll(" ").strip();

        if (s.length() > MAX_MESSAGE_LENGTH) {
            s = s.substring(0, MAX_MESSAGE_LENGTH).strip() + "...";
        }
        return s;
    }

    /**
     * Última línea de defensa. Si de verdad necesitas meter un nombre en un comando de consola,
     * compruébalo con esto primero; y si da false, no ejecutes el comando.
     */
    public static boolean isCommandSafe(String value) {
        if (value == null || value.isBlank()) return false;
        if (!value.equals(clean(value))) return false;
        return value.indexOf(';') < 0 && value.indexOf('/') < 0 && value.indexOf(' ') < 0;
    }
}
