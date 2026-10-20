package dev.juanse.tiktokmobs.plugin.runtime;

import org.bukkit.NamespacedKey;
import org.bukkit.entity.Entity;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

/**
 * La marca que dice "a este lo invocó una donación".
 *
 * <p>Existe para poder tratar distinto lo que invoca el plugin de lo que ya había en el
 * mundo. El caso concreto es la explosión: un creeper de donación no puede comerse el
 * terreno —en un live de dos horas el mapa queda picado y no hay deshacer—, pero el creeper
 * que te encuentras minando sí debe explotar como siempre. Cambiarle las reglas al survival
 * entero para arreglar las donaciones sería curar la gripe amputando.
 *
 * <p>Va en la {@code PersistentDataContainer} y no en un {@code Set<UUID>} en memoria porque
 * un creeper puede quedarse quieto en un chunk, descargarse y volver media hora después: la
 * marca tiene que viajar con la entidad y sobrevivir a reinicios.
 */
public final class SpawnMark {

    private final NamespacedKey key;

    public SpawnMark(Plugin plugin) {
        this.key = new NamespacedKey(plugin, "invocado-por-donacion");
    }

    public void marcar(Entity entity) {
        if (entity == null) return;
        entity.getPersistentDataContainer().set(key, PersistentDataType.BYTE, (byte) 1);
    }

    public boolean esDeDonacion(Entity entity) {
        return entity != null
                && entity.getPersistentDataContainer().has(key, PersistentDataType.BYTE);
    }
}
