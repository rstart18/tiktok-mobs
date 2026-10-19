package dev.juanse.tiktokmobs.plugin.config;

/**
 * Un live conectado al servidor. Con varios amigos streameando a la vez hay uno por cada uno.
 *
 * <p>Dos formas de que lleguen sus eventos, y la diferencia importa:
 *
 * <ul>
 *   <li><b>url puesta</b> — el plugin se conecta HACIA su TikFinity. Sirve para el tuyo
 *       ({@code ws://127.0.0.1:21213/}) o para alguien que haya montado un túnel.</li>
 *   <li><b>url vacía</b> — modo relay: su PC empuja los eventos a {@code /ingest}. No necesita
 *       abrir ningún puerto, solo salida. Es lo que usan los amigos.</li>
 * </ul>
 *
 * <p>{@code player} es opcional: si está y ese jugador está conectado, las donaciones de ese
 * live le salen a él alrededor. Si no, todo cae en el objetivo común.
 */
public record StreamSource(String id, String url, String player) {

    public StreamSource {
        id = (id == null || id.isBlank()) ? "main" : id.trim();
        url = url == null ? "" : url.trim();
        player = player == null ? "" : player.trim();
    }

    /** true si el plugin debe abrir un WebSocket hacia esta fuente. */
    public boolean dialsOut() {
        return !url.isBlank();
    }

    public boolean hasOwnPlayer() {
        return !player.isBlank();
    }
}
