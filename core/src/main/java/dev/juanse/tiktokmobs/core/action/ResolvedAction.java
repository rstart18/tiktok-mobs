package dev.juanse.tiktokmobs.core.action;

import dev.juanse.tiktokmobs.core.event.GiftEvent;

/**
 * Una acción ya resuelta y lista para ejecutarse en el mundo.
 * {@code donorLabel} ya viene saneado: es seguro ponerlo en un nameplate o en el chat.
 */
public record ResolvedAction(ActionSpec spec,
                             GiftEvent source,
                             String donorLabel,
                             int coins,
                             String streamerId) {

    public ResolvedAction {
        spec = spec == null ? ActionSpec.NONE : spec;
        donorLabel = (donorLabel == null || donorLabel.isBlank()) ? "anon" : donorLabel;
        coins = Math.max(0, coins);
        streamerId = (streamerId == null || streamerId.isBlank()) ? "main" : streamerId.trim();
    }
}
