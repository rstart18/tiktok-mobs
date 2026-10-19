package dev.juanse.tiktokmobs.core.event;

/**
 * Un regalo de TikTok.
 *
 * <p>{@code streakable} es el campo que arruina las implementaciones ingenuas: los regalos
 * baratos (rosa, dedo-corazón) emiten un evento POR CADA repetición de la racha mientras el
 * viewer mantiene el dedo pulsado. Si actúas en todos, un combo de 50 rosas te spawnea 50
 * veces la acción. Solo el evento con {@code repeatEnd == true} cierra la racha y trae el
 * {@code count} final.
 */
public record GiftInfo(int id,
                       String name,
                       int coins,
                       int count,
                       boolean streakable,
                       boolean repeatEnd) {

    public GiftInfo {
        name = name == null ? "" : name.trim();
        coins = Math.max(0, coins);
        count = Math.max(1, count);
    }

    /** Un regalo no repetible, de una sola unidad. */
    public static GiftInfo single(int id, String name, int coins) {
        return new GiftInfo(id, name, coins, 1, false, true);
    }

    /** Valor total en monedas (diamantes) de este evento. Es la base de los tiers. */
    public int totalCoins() {
        return coins * count;
    }

    /** true si todavía estamos en medio de una racha y hay que ignorar el evento. */
    public boolean isOpenStreak() {
        return streakable && !repeatEnd;
    }
}
