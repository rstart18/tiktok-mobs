package dev.juanse.tiktokmobs.plugin.overlay;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.juanse.tiktokmobs.core.DonorLedger;
import dev.juanse.tiktokmobs.core.action.ActionKind;
import dev.juanse.tiktokmobs.core.action.ResolvedAction;
import dev.juanse.tiktokmobs.core.config.MappingTable;
import dev.juanse.tiktokmobs.core.event.GiftInfo;

import java.util.List;

/**
 * Construcción del JSON que viaja por SSE al overlay.
 *
 * <p>Sin dependencias de Bukkit a propósito: así se testea sin servidor, que es donde quieres
 * tener cubierto el cálculo de tiers y el flag de alerta.
 *
 * <p>El nick llega ya saneado por el core (sin {@code < > & " '}), Gson escapa el JSON y el
 * overlay usa {@code textContent} en vez de {@code innerHTML}: tres capas para que el nombre
 * de un desconocido no pueda inyectar nada en una pantalla que está en directo.
 */
public final class OverlayPayloads {

    private OverlayPayloads() {
    }

    public static String event(ResolvedAction action,
                               String actionText,
                               int alertMinCoins,
                               boolean degraded) {
        GiftInfo gift = action.source().gift();

        JsonObject json = new JsonObject();
        json.addProperty("kind", "event");
        json.addProperty("ts", System.currentTimeMillis());
        json.addProperty("donor", action.donorLabel());
        json.addProperty("type", action.source().type().name());
        json.addProperty("gift", gift == null ? "" : gift.name());
        json.addProperty("count", gift == null ? 1 : gift.count());
        json.addProperty("coins", action.coins());
        json.addProperty("action", actionText);
        json.addProperty("actionKind", action.spec().kind().name());
        json.addProperty("tier", tierOf(action.coins()));
        json.addProperty("alert", alertMinCoins > 0 && action.coins() >= alertMinCoins);
        json.addProperty("degraded", degraded);
        return json.toString();
    }

    public static String state(int totalCoins, int likes, List<DonorLedger.Donor> top) {
        JsonArray donors = new JsonArray();
        for (DonorLedger.Donor donor : top) {
            JsonObject entry = new JsonObject();
            entry.addProperty("label", donor.label());
            entry.addProperty("coins", donor.coins());
            donors.add(entry);
        }

        JsonObject json = new JsonObject();
        json.addProperty("kind", "state");
        json.addProperty("ts", System.currentTimeMillis());
        json.addProperty("totalCoins", totalCoins);
        json.addProperty("likes", likes);
        json.add("top", donors);
        return json.toString();
    }

    /**
     * El catálogo que ven los viewers, sacado del config real: así el panel nunca miente
     * sobre lo que hace cada regalo, por mucho que cambies los mapeos a mitad de live.
     */
    public static String catalog(MappingTable table) {
        JsonArray gifts = new JsonArray();
        for (MappingTable.CatalogEntry entry : table.catalog()) {
            if (entry.spec().kind() == ActionKind.NONE) continue;

            JsonObject row = new JsonObject();
            row.addProperty("label", entry.label());
            row.addProperty("coins", entry.coins());
            row.addProperty("effect", entry.spec().describe());
            row.addProperty("tier", tierOf(entry.coins()));
            gifts.add(row);
        }

        JsonArray tiers = new JsonArray();
        for (MappingTable.Tier tier : table.tiers()) {
            if (tier.spec().kind() == ActionKind.NONE) continue;

            JsonObject row = new JsonObject();
            row.addProperty("maxCoins", tier.maxCoins());
            row.addProperty("effect", tier.spec().describe());
            row.addProperty("tier", tierOf(tier.maxCoins()));
            tiers.add(row);
        }

        JsonObject json = new JsonObject();
        json.addProperty("kind", "catalog");
        json.add("gifts", gifts);
        json.add("tiers", tiers);
        json.addProperty("goalCoins", table.donationGoalCoins());
        json.addProperty("goalEffect", table.goalEnabled() ? table.goalReward().describe() : "");
        json.addProperty("likeEvery", table.likeEvery());
        return json.toString();
    }

    /** Clasificación por valor. El overlay la usa para elegir color y tamaño del toast. */
    public static String tierOf(int coins) {
        if (coins <= 0) return "social";
        if (coins <= 9) return "common";
        if (coins <= 99) return "uncommon";
        if (coins <= 499) return "rare";
        if (coins <= 999) return "epic";
        return "legendary";
    }
}
