package dev.juanse.tiktokmobs.plugin.boss;

import dev.juanse.tiktokmobs.core.boss.BossTitle;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Entity;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.util.List;

/**
 * Quién pagó este jefe, guardado <b>en la propia entidad</b>.
 *
 * <p>Un {@code Map<UUID, ...>} en memoria se pierde con cada {@code /reload}, cada reinicio y
 * cada descarga de chunk, y entonces el jefe se queda sin nombre y el jugador que muere no sabe
 * de quién fue el regalo. La PersistentDataContainer viaja con la entidad y sobrevive a todo
 * eso, así que es el único sitio honesto para la atribución.
 *
 * <p>También guarda qué fases ya se dispararon: si no, un chunk que se descarga y vuelve
 * reinvoca esbirros, y el jefe se convierte en una fábrica de mobs.
 */
public final class BossPdc {

    private final NamespacedKey bossId;
    private final NamespacedKey donors;
    private final NamespacedKey coins;
    private final NamespacedKey maxHp;
    private final NamespacedKey phases;
    private final NamespacedKey minionOf;
    private final NamespacedKey streamer;

    public BossPdc(Plugin plugin) {
        this.bossId = new NamespacedKey(plugin, "boss-id");
        this.donors = new NamespacedKey(plugin, "boss-donors");
        this.coins = new NamespacedKey(plugin, "boss-coins");
        this.maxHp = new NamespacedKey(plugin, "boss-max-hp");
        this.phases = new NamespacedKey(plugin, "boss-phases");
        this.minionOf = new NamespacedKey(plugin, "boss-minion-of");
        this.streamer = new NamespacedKey(plugin, "boss-streamer");
    }

    // ------------------------------------------------------------------ jefe

    public void markBoss(Entity entity, String definitionId, List<String> donorNames,
                         int totalCoins, double hpMax, String streamerId) {
        PersistentDataContainer pdc = entity.getPersistentDataContainer();
        pdc.set(bossId, PersistentDataType.STRING, definitionId);
        pdc.set(donors, PersistentDataType.STRING, BossTitle.store(donorNames));
        pdc.set(coins, PersistentDataType.INTEGER, Math.max(0, totalCoins));
        pdc.set(maxHp, PersistentDataType.DOUBLE, hpMax);
        pdc.set(phases, PersistentDataType.INTEGER, 0);
        // El id del live que invoco al jefe viaja con la entidad: al re-adoptar un jefe
        // tras un /reload o una recarga de chunk, hace falta saber a que streamer pertenecia
        // (si no, el jefe se queda huerfano y los esbirros caen en el live equivocado).
        pdc.set(streamer, PersistentDataType.STRING,
                streamerId == null || streamerId.isBlank() ? "main" : streamerId);
    }

    public boolean isBoss(Entity entity) {
        return entity != null
                && entity.getPersistentDataContainer().has(bossId, PersistentDataType.STRING);
    }

    public String definitionId(Entity entity) {
        return read(entity, bossId, PersistentDataType.STRING, "");
    }

    public List<String> donors(Entity entity) {
        return BossTitle.parse(read(entity, donors, PersistentDataType.STRING, ""));
    }

    public void setDonors(Entity entity, List<String> donorNames) {
        entity.getPersistentDataContainer()
                .set(donors, PersistentDataType.STRING, BossTitle.store(donorNames));
    }

    public int coins(Entity entity) {
        return read(entity, coins, PersistentDataType.INTEGER, 0);
    }

    public void setCoins(Entity entity, int value) {
        entity.getPersistentDataContainer().set(coins, PersistentDataType.INTEGER, Math.max(0, value));
    }

    public double maxHp(Entity entity) {
        return read(entity, maxHp, PersistentDataType.DOUBLE, 0.0);
    }

    public void setMaxHp(Entity entity, double value) {
        entity.getPersistentDataContainer().set(maxHp, PersistentDataType.DOUBLE, value);
    }

    /** Id del live que invoco a este jefe. "main" si la entidad es de antes de C6. */
    public String streamerId(Entity entity) {
        String stored = read(entity, streamer, PersistentDataType.STRING, null);
        return stored == null || stored.isBlank() ? "main" : stored;
    }

    /** Bits de fase ya disparada: 1 = esbirros, 2 = furia. */
    public int phases(Entity entity) {
        return read(entity, phases, PersistentDataType.INTEGER, 0);
    }

    public boolean phaseFired(Entity entity, int bit) {
        return (phases(entity) & bit) != 0;
    }

    public void markPhase(Entity entity, int bit) {
        entity.getPersistentDataContainer()
                .set(phases, PersistentDataType.INTEGER, phases(entity) | bit);
    }

    // ------------------------------------------------------------------ esbirros

    /** Un esbirro lleva la misma atribución: si te mata él, el título nombra al donante igual. */
    public void markMinion(Entity entity, List<String> donorNames) {
        entity.getPersistentDataContainer()
                .set(minionOf, PersistentDataType.STRING, BossTitle.store(donorNames));
    }

    public boolean isMinion(Entity entity) {
        return entity != null
                && entity.getPersistentDataContainer().has(minionOf, PersistentDataType.STRING);
    }

    public List<String> minionDonors(Entity entity) {
        return BossTitle.parse(read(entity, minionOf, PersistentDataType.STRING, ""));
    }

    /** Los donantes detrás de esta entidad, sea el jefe o un esbirro suyo. Vacío si no es ninguno. */
    public List<String> attribution(Entity entity) {
        if (entity == null) return List.of();
        if (isBoss(entity)) return donors(entity);
        if (isMinion(entity)) return minionDonors(entity);
        return List.of();
    }

    private <T> T read(Entity entity, NamespacedKey key, PersistentDataType<?, T> type, T fallback) {
        if (entity == null) return fallback;
        T value = entity.getPersistentDataContainer().get(key, type);
        return value == null ? fallback : value;
    }
}
