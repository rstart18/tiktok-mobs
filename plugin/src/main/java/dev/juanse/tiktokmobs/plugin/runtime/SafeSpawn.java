package dev.juanse.tiktokmobs.plugin.runtime;

import dev.juanse.tiktokmobs.core.spawn.SpawnSearch;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;

import java.util.HashSet;
import java.util.Set;

/**
 * Dónde cabe de verdad un bicho. La parte de Minecraft del buscador de sitio.
 *
 * <p>La geometría la decide {@link SpawnSearch} (core, sin Bukkit); aquí solo se mira si el
 * candidato es habitable. Está en su propia clase porque lo usan dos sitios —los mobs normales
 * y el jefe con sus esbirros— y duplicar esta lógica es cómo volvimos a spawnear dentro de la
 * piedra en la mina.
 */
public final class SafeSpawn {

    private SafeSpawn() {
    }

    public static Location find(Location center, int radius, int index, int total) {
        return find(center, radius, index, total, new HashSet<>());
    }

    /**
     * @param ocupados bloques ya usados en este lote. Dos mobs en el mismo bloque se hacen daño
     *                 por apelotonamiento, y en un túnel es fácil que todos los candidatos
     *                 colapsen en el mismo sitio.
     */
    public static Location find(Location center, int radius, int index, int total,
                                Set<Long> ocupados) {
        World world = center.getWorld();
        if (world == null) return center;

        Location primeroQueCabe = null;

        for (SpawnSearch.Candidate c : SpawnSearch.candidates(
                center.getX(), center.getY(), center.getZ(), radius, index, total)) {

            Location candidate = new Location(world, c.x(), c.y(), c.z());
            if (!fits(candidate)) continue;

            if (primeroQueCabe == null) primeroQueCabe = candidate;

            if (ocupados.add(blockKey(candidate))) return candidate;
        }

        // Todo ocupado: mejor repetir un sitio válido que spawnear dentro de la roca.
        return primeroQueCabe != null ? primeroQueCabe : center.clone();
    }

    /** Un jefe grande necesita más hueco que un pollo: pide 3 bloques de alto. */
    public static Location findTall(Location center, int radius) {
        World world = center.getWorld();
        if (world == null) return center;

        for (SpawnSearch.Candidate c : SpawnSearch.candidates(
                center.getX(), center.getY(), center.getZ(), radius, 0, 1)) {

            Location candidate = new Location(world, c.x(), c.y(), c.z());
            if (fits(candidate) && habitable(candidate.clone().add(0, 2, 0))) return candidate;
        }
        return find(center, radius, 0, 1);
    }

    public static long blockKey(Location l) {
        return (((long) l.getBlockX()) & 0x3FFFFFF) << 38
                | (((long) l.getBlockZ()) & 0x3FFFFFF) << 12
                | (((long) l.getBlockY()) & 0xFFF);
    }

    /** Hace falta hueco para el bloque de los pies y el de la cabeza. */
    public static boolean fits(Location location) {
        return habitable(location) && habitable(location.clone().add(0, 1, 0));
    }

    private static boolean habitable(Location location) {
        Block block = location.getBlock();
        Material type = block.getType();

        // Lava y fuego "se atraviesan" para Bukkit, pero spawnear ahí es matarlo.
        if (type == Material.LAVA || type == Material.FIRE || type == Material.CAMPFIRE) return false;

        // isPassable() cubre aire, hierba, agua, antorchas, carteles... mucho mejor que una
        // lista a mano, que es lo que había antes y se dejaba medio mundo fuera.
        return block.isPassable();
    }
}
