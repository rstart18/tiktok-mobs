package dev.juanse.tiktokmobs.plugin.overlay;

import dev.juanse.tiktokmobs.core.DonorLedger;
import dev.juanse.tiktokmobs.core.action.ResolvedAction;

import java.util.List;

/**
 * Lo que el executor conoce del overlay: un sitio donde publicar lo que acaba de pasar.
 *
 * <p>Así el executor no sabe de JSON, ni de SSE, ni de HTTP, y {@link #DISABLED} permite tener
 * el overlay apagado sin un solo {@code if (overlay != null)} repartido por el código.
 */
public final class OverlayFeed {

    public static final OverlayFeed DISABLED = new OverlayFeed(null, 0);

    private final OverlayServer server;
    private final int alertMinCoins;

    public OverlayFeed(OverlayServer server, int alertMinCoins) {
        this.server = server;
        this.alertMinCoins = alertMinCoins;
    }

    public boolean enabled() {
        return server != null;
    }

    public void publish(ResolvedAction action, String actionText, boolean degraded) {
        if (server == null) return;
        server.push(OverlayPayloads.event(action, actionText, alertMinCoins, degraded));
    }

    /** @return el JSON publicado, para cachearlo y mandarlo a quien se conecte luego. */
    public String publishState(int totalCoins, int likes, List<DonorLedger.Donor> top) {
        String json = OverlayPayloads.state(totalCoins, likes, top);
        if (server != null) server.push(json);
        return json;
    }
}
