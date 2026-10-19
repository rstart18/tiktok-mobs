package dev.juanse.tiktokmobs.plugin.runtime;

import dev.juanse.tiktokmobs.core.action.ResolvedAction;
import dev.juanse.tiktokmobs.core.event.GiftInfo;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

/**
 * Plantillas de mensaje con marcadores. El %donor% ya viene saneado por el core,
 * así que un nick con códigos de color no puede inyectar formato en el chat.
 */
public final class Messages {

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();

    private Messages() {
    }

    public static Component color(String legacy) {
        return LEGACY.deserialize(legacy == null ? "" : legacy);
    }

    public static Component render(String template, ResolvedAction action) {
        GiftInfo gift = action.source().gift();
        String text = template
                .replace("%donor%", action.donorLabel())
                .replace("%gift%", gift == null ? "" : gift.name())
                .replace("%count%", gift == null ? "1" : String.valueOf(gift.count()))
                .replace("%coins%", String.valueOf(action.coins()))
                .replace("%mob%", action.spec().mob().toLowerCase(java.util.Locale.ROOT));
        return LEGACY.deserialize(text);
    }

    /** Aviso por defecto cuando el mapeo no trae 'message'. */
    public static Component defaultAnnounce(ResolvedAction action) {
        GiftInfo gift = action.source().gift();
        String what = gift == null
                ? action.source().type().name().toLowerCase(java.util.Locale.ROOT)
                : gift.name() + (gift.count() > 1 ? " x" + gift.count() : "");

        return LEGACY.deserialize("&6[TikTok] &f" + action.donorLabel()
                + " &7-> &e" + what + " &7-> &a" + describeAction(action));
    }

    /** Texto corto de lo que pasó en el mundo. Lo reutiliza el overlay. */
    public static String describeAction(ResolvedAction action) {
        return switch (action.spec().kind()) {
            case SPAWN, HORDE -> action.spec().count() + "x " + action.spec().mob().toLowerCase(java.util.Locale.ROOT);
            case BOSS -> "jefe " + action.spec().mob().toLowerCase(java.util.Locale.ROOT);
            case EFFECT -> action.spec().effect().toLowerCase(java.util.Locale.ROOT);
            case WORLD_EVENT -> action.spec().worldEventId();
            case GIVE -> action.spec().count() + "x "
                    + action.spec().item().toLowerCase(java.util.Locale.ROOT).replace('_', ' ');
            case NONE -> "nada";
        };
    }
}
