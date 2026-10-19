package dev.juanse.tiktokmobs.plugin.boss;

import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;

/**
 * Partículas y sonidos sin acoplarse a los nombres de Bukkit.
 *
 * <p>Los enums de {@code Particle} y {@code Sound} se han renombrado varias veces entre
 * versiones ({@code SMOKE_LARGE} → {@code LARGE_SMOKE}, {@code ENCHANTMENT_TABLE} → {@code ENCHANT},
 * {@code Sound} pasó a registro). Referenciar una constante a pelo compila hoy y rompe el jar
 * en cuanto cambias de servidor, que es exactamente el fallo que ya nos costó un directo.
 *
 * <p>Así que: partículas resueltas por nombre con alternativas, y sonidos por su clave
 * {@code minecraft:...}, que no ha cambiado nunca.
 */
public final class Fx {

    /** Resueltas una vez al cargar la clase; si ninguna existe, queda null y no se dibuja. */
    private static final Particle FLAME = resolve("FLAME");
    private static final Particle SOUL = resolve("SOUL_FIRE_FLAME", "FLAME");
    private static final Particle SMOKE = resolve("LARGE_SMOKE", "SMOKE_LARGE", "SMOKE");
    private static final Particle MAGIC = resolve("ENCHANT", "ENCHANTMENT_TABLE", "CRIT");
    private static final Particle ANGRY = resolve("ANGRY_VILLAGER", "VILLAGER_ANGRY", "CRIT");

    public static final String SOUND_SUMMON = "minecraft:entity.wither.spawn";
    public static final String SOUND_TICK = "minecraft:block.beacon.ambient";
    public static final String SOUND_ENRAGE = "minecraft:entity.ender_dragon.growl";
    public static final String SOUND_PHASE = "minecraft:entity.wither.ambient";
    public static final String SOUND_DEATH = "minecraft:entity.wither.death";
    public static final String SOUND_TELEGRAPH = "minecraft:block.note_block.pling";

    private Fx() {
    }

    private static Particle resolve(String... names) {
        for (String name : names) {
            try {
                return Particle.valueOf(name);
            } catch (IllegalArgumentException | NoSuchFieldError ignored) {
                // Esta versión no la tiene: se prueba la siguiente.
            }
        }
        return null;
    }

    /** Un anillo de partículas en el suelo: la señal de "aquí va a pasar algo". */
    public static void ring(Location at, double radius, int points) {
        World world = at.getWorld();
        if (world == null) return;

        for (int i = 0; i < points; i++) {
            double angle = (2 * Math.PI * i) / points;
            Location point = at.clone().add(Math.cos(angle) * radius, 0.2, Math.sin(angle) * radius);
            spawn(world, SOUL, point, 2, 0.0);
            spawn(world, SMOKE, point, 1, 0.05);
        }
    }

    /** Columna que sube desde el punto: el aviso de que algo está naciendo ahí. */
    public static void pillar(Location at, double height) {
        World world = at.getWorld();
        if (world == null) return;

        for (double y = 0; y < height; y += 0.5) {
            spawn(world, FLAME, at.clone().add(0, y, 0), 2, 0.08);
        }
        spawn(world, MAGIC, at.clone().add(0, height, 0), 12, 0.4);
    }

    /** Estallido para el enfurecido y los cambios de fase. */
    public static void burst(Location at) {
        World world = at.getWorld();
        if (world == null) return;

        spawn(world, ANGRY, at.clone().add(0, 1.5, 0), 20, 0.6);
        spawn(world, SOUL, at, 40, 0.8);
        spawn(world, SMOKE, at, 20, 0.5);
    }

    private static void spawn(World world, Particle particle, Location at, int count, double spread) {
        if (particle == null) return;
        try {
            world.spawnParticle(particle, at, count, spread, spread, spread, 0.0);
        } catch (RuntimeException ignored) {
            // Una partícula que esta versión no sabe dibujar no puede cortar la pelea.
        }
    }

    /** Sonido por clave: nunca depende del enum Sound. */
    public static void sound(Location at, String key, float volume, float pitch) {
        World world = at.getWorld();
        if (world == null) return;
        try {
            world.playSound(at, key, volume, pitch);
        } catch (RuntimeException ignored) {
            // Idem: sin sonido se sigue jugando.
        }
    }
}
