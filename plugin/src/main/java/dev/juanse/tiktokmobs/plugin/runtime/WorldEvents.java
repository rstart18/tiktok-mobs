package dev.juanse.tiktokmobs.plugin.runtime;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * Eventos de mundo para los tiers altos. Son más memorables que "más zombies" y mucho
 * más baratos en TPS que una horda, así que son el relleno ideal cuando el servidor va justo.
 *
 * <p>Añadir uno nuevo = un case aquí y una línea en el config.
 */
public final class WorldEvents {

    private static final Random RANDOM = new Random();

    private static final List<Material> LOOT = List.of(
            Material.DIAMOND, Material.GOLDEN_APPLE, Material.IRON_INGOT,
            Material.COOKED_BEEF, Material.ENDER_PEARL, Material.TNT,
            Material.ARROW, Material.EMERALD);

    public static final Set<String> AVAILABLE = Set.of(
            "tnt_rain", "random_loot", "night", "day", "thunder", "heal", "swarm_bats",
            "rayo", "water_drop", "ruleta",
            "nether", "suelo_lava", "ruleta_rusa", "taladro");

    /**
     * Alcance de un evento de mundo:
     *
     * <ul>
     *   <li>{@link #LOCAL}: solo afecta al objetivo del streamer (su jugador, su zona). Con
     *       varios lives en el servidor se puede ejecutar sin ensuciar al resto.</li>
     *   <li>{@link #GLOBAL}: cambia estado del mundo entero (hora, clima, bloques). Si cinco
     *       lives lanzan "suelo_lava" a la vez, es el mismo suelo. Esta fase <b>no</b> los
     *       filtra; declararlo aqui permite que el evento multi-streamer los desactive cuando
     *       llegue, en lugar de descubrir el problema en vivo.</li>
     * </ul>
     */
    public enum Scope {
        LOCAL,
        GLOBAL
    }

    private static final Map<String, Scope> SCOPES = Map.ofEntries(
            Map.entry("tnt_rain", Scope.GLOBAL),
            Map.entry("random_loot", Scope.LOCAL),
            Map.entry("night", Scope.GLOBAL),
            Map.entry("day", Scope.GLOBAL),
            Map.entry("thunder", Scope.GLOBAL),
            Map.entry("heal", Scope.LOCAL),
            Map.entry("swarm_bats", Scope.LOCAL),
            Map.entry("rayo", Scope.LOCAL),
            Map.entry("water_drop", Scope.LOCAL),
            Map.entry("ruleta", Scope.LOCAL),
            Map.entry("nether", Scope.LOCAL),
            Map.entry("suelo_lava", Scope.GLOBAL),
            Map.entry("ruleta_rusa", Scope.LOCAL),
            Map.entry("taladro", Scope.LOCAL));

    /** Alcance declarado del evento, o LOCAL si no esta en la tabla (lo mas seguro). */
    public static Scope scopeOf(String id) {
        if (id == null) return Scope.LOCAL;
        return SCOPES.getOrDefault(id.trim().toLowerCase(Locale.ROOT), Scope.LOCAL);
    }

    /**
     * Los efectos con duración viven aparte, en {@link Spectacle}, porque hay que poder
     * deshacerlos. Se cablea al arrancar el plugin.
     */
    private static volatile Spectacle spectacle;

    public static void setSpectacle(Spectacle value) {
        spectacle = value;
    }

    /**
     * Lo que puede salir en la ruleta. Mitad premio, mitad castigo: si fuera todo bueno
     * nadie la temería, y si fuera todo malo nadie la mandaría.
     */
    private record Premio(String nombre, String color, boolean bueno) {
    }

    private static final List<Premio> RULETA = List.of(
            new Premio("TOTEM", "&d", true),
            new Premio("MANZANA DORADA", "&6", true),
            new Premio("DIAMANTES", "&b", true),
            new Premio("VIDA COMPLETA", "&a", true),
            new Premio("VELOCIDAD", "&a", true),
            new Premio("LOOT", "&e", true),
            new Premio("RAYO", "&e", false),
            new Premio("CEGUERA", "&8", false),
            new Premio("FUEGO", "&c", false),
            new Premio("MURCIELAGOS", "&8", false),
            new Premio("NOCHE", "&9", false),
            new Premio("CREEPER", "&2", false));

    private WorldEvents() {
    }

    /** @return true si el id existía y se ejecutó. */
    public static boolean run(String id, Location at, SpawnRegistry registry, Player nearest,
                              String streamerId) {
        if (at == null || at.getWorld() == null) return false;
        World world = at.getWorld();

        switch (id == null ? "" : id.trim().toLowerCase(Locale.ROOT)) {
            case "tnt_rain" -> {
                for (int i = 0; i < 8; i++) {
                    Location drop = at.clone().add(offset(6), 12, offset(6));
                    TNTPrimed tnt = world.spawn(drop, TNTPrimed.class);
                    tnt.setFuseTicks(60 + RANDOM.nextInt(40));
                    registry.track(streamerId, tnt);
                }
            }
            case "random_loot" -> {
                for (int i = 0; i < 4; i++) {
                    Material material = LOOT.get(RANDOM.nextInt(LOOT.size()));
                    world.dropItemNaturally(at.clone().add(offset(2), 1, offset(2)),
                            new ItemStack(material, 1 + RANDOM.nextInt(2)));
                }
            }
            case "night" -> world.setTime(14_000L);
            case "day" -> world.setTime(1_000L);
            case "thunder" -> {
                world.setStorm(true);
                world.setThundering(true);
                world.strikeLightningEffect(at.clone().add(offset(4), 0, offset(4)));
            }
            case "heal" -> {
                // Curación COMPLETA, no un parche de 5 corazones: es la recompensa de los
                // likes y tiene que notarse en cámara. Si acabas inmortal, sube 'likes.every'.
                if (nearest != null) {
                    nearest.setFoodLevel(20);
                    nearest.setSaturation(10f);
                    nearest.setFireTicks(0);
                    // Nunca por encima del máximo: setHealth lanza si te pasas.
                    nearest.setHealth(maxHealthOf(nearest));
                }
            }
            case "rayo", "lightning" -> {
                // Rayos DE VERDAD: hacen dano y prenden fuego. strikeLightningEffect (el que
                // usa 'thunder') solo se ve y se oye, y en camara no se distingue de nada.
                for (int i = 0; i < 3; i++) {
                    world.strikeLightning(at.clone().add(offset(3), 0, offset(3)));
                }
            }
            case "water_drop" -> {
                if (nearest != null) dropFromSky(nearest);
            }
            case "ruleta" -> ruleta(at, registry, nearest, streamerId);

            case "nether" -> {
                if (nearest != null && spectacle != null) spectacle.nether(nearest, 60);
            }
            case "suelo_lava" -> {
                // Magma y no lava: la lava fluye, se cuela por las cuevas y prende fuego, y
                // nada de eso se deshace. El magma quema al pisarlo, se queda quieto y vuelve.
                if (nearest != null && spectacle != null) {
                    spectacle.sueloEsLava(nearest, Material.MAGMA_BLOCK, 4, 14, 10);
                }
            }
            case "ruleta_rusa" -> {
                if (nearest != null && spectacle != null) spectacle.ruletaRusa(nearest);
            }
            case "taladro" -> {
                if (nearest != null && spectacle != null) spectacle.taladro(nearest, 60);
            }
            case "swarm_bats" -> {
                for (int i = 0; i < 20; i++) {
                    Entity bat = world.spawnEntity(at.clone().add(offset(5), 2, offset(5)),
                            org.bukkit.entity.EntityType.BAT);
                    registry.track(streamerId, bat);
                }
            }
            default -> {
                return false;
            }
        }
        return true;
    }

    /**
     * Ruleta: sale una cosa al azar, buena o mala.
     *
     * <p>Lo que la hace funcionar no es el premio, es el <b>anuncio</b>: se dice en grande qué
     * salió. Sin eso es un evento aleatorio cualquiera y el que donó no sabe qué compró.
     */
    private static void ruleta(Location at, SpawnRegistry registry, Player nearest, String streamerId) {
        Premio premio = RULETA.get(RANDOM.nextInt(RULETA.size()));
        World world = at.getWorld();

        switch (premio.nombre()) {
            case "TOTEM" -> dar(nearest, Material.TOTEM_OF_UNDYING, 1);
            case "MANZANA DORADA" -> dar(nearest, Material.GOLDEN_APPLE, 1);
            case "DIAMANTES" -> dar(nearest, Material.DIAMOND, 3);
            case "VIDA COMPLETA" -> run("heal", at, registry, nearest, streamerId);
            case "VELOCIDAD" -> {
                if (nearest != null) {
                    PotionEffectType speed = PotionEffectType.getByName("SPEED");
                    if (speed != null) {
                        nearest.addPotionEffect(new PotionEffect(speed, 20 * 30, 1, true, true));
                    }
                }
            }
            case "LOOT" -> run("random_loot", at, registry, nearest, streamerId);
            case "RAYO" -> run("rayo", at, registry, nearest, streamerId);
            case "CEGUERA" -> {
                if (nearest != null) {
                    PotionEffectType blind = PotionEffectType.getByName("BLINDNESS");
                    if (blind != null) {
                        nearest.addPotionEffect(new PotionEffect(blind, 20 * 8, 0, true, true));
                    }
                }
            }
            case "FUEGO" -> {
                if (nearest != null) nearest.setFireTicks(20 * 5);
            }
            case "MURCIELAGOS" -> run("swarm_bats", at, registry, nearest, streamerId);
            case "NOCHE" -> run("night", at, registry, nearest, streamerId);
            case "CREEPER" -> {
                Entity creeper = world.spawnEntity(
                        at.clone().add(offset(3), 0, offset(3)),
                        org.bukkit.entity.EntityType.CREEPER);
                registry.track(streamerId, creeper);
            }
            default -> {
                // Un premio en la lista sin case aquí: mejor no hacer nada que romper el tick.
            }
        }

        anunciar("&5&l? RANDOM &7-> " + premio.color() + "&l" + premio.nombre()
                + (premio.bueno() ? " &a(premio)" : " &c(castigo)"));
    }

    private static void dar(Player player, Material material, int cantidad) {
        if (player == null) return;
        ItemStack stack = new ItemStack(material, cantidad);
        player.getInventory().addItem(stack).values()
                .forEach(sobra -> player.getWorld().dropItemNaturally(player.getLocation(), sobra));
    }

    /** La ruleta se anuncia sola: el executor no sabe qué número salió. */
    private static void anunciar(String legacy) {
        var mensaje = net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
                .legacyAmpersand().deserialize(legacy);
        for (Player player : org.bukkit.Bukkit.getOnlinePlayers()) {
            player.sendMessage(mensaje);
        }
    }

    /** Cuántos bloques te sube el water drop. Suficiente para que se vea la caída en cámara. */
    private static final int DROP_HEIGHT = 60;

    /**
     * Te sube al cielo y te pone un balde de agua en la mano. El MLG de toda la vida.
     *
     * <p>El balde va a una casilla <b>vacía</b> de la barra si la hay: meterlo encima de lo que
     * llevases en la mano te haría perder la pico en mitad de una mina, y eso no es contenido,
     * es un bug. Si la barra está llena, se intenta el inventario y, como último recurso, se
     * deja a los pies — pero entonces se avisa, porque sin balde esto es una sentencia.
     */
    private static void dropFromSky(Player player) {
        World world = player.getWorld();
        Location up = player.getLocation().clone();

        // Nunca por encima del techo del mundo: ahí el teleport falla o te deja atascado.
        double maxY = world.getMaxHeight() - 5.0;
        up.setY(Math.min(up.getY() + DROP_HEIGHT, maxY));

        boolean conBalde = giveWaterBucket(player);

        player.teleport(up);
        player.setFallDistance(0f);
        world.playSound(up, "minecraft:entity.generic.splash", 1.0f, 1.0f);

        if (!conBalde) {
            player.sendMessage("§c¡Sin sitio para el balde! Lo tienes en el inventario o en el suelo.");
        }
    }

    /** @return true si el balde acabó en la mano. */
    private static boolean giveWaterBucket(Player player) {
        PlayerInventory inventory = player.getInventory();
        ItemStack bucket = new ItemStack(Material.WATER_BUCKET);

        for (int slot = 0; slot < 9; slot++) {
            ItemStack current = inventory.getItem(slot);
            if (current == null || current.getType() == Material.AIR) {
                inventory.setItem(slot, bucket);
                inventory.setHeldItemSlot(slot);
                return true;
            }
        }

        // Barra llena: al inventario, y si tampoco cabe, al suelo. No se pierde nunca.
        inventory.addItem(bucket).values()
                .forEach(sobra -> player.getWorld().dropItemNaturally(player.getLocation(), sobra));
        return false;
    }

    /**
     * Evita la API de atributos a propósito: su enum se renombró entre 1.21.x y tirar de
     * {@code Attribute.GENERIC_MAX_HEALTH} rompe el jar en cuanto cambias de versión.
     * Para curar basta con no pasarse del valor de referencia de un jugador.
     */
    private static double maxHealthOf(Player player) {
        return 20.0;
    }

    private static double offset(int spread) {
        return RANDOM.nextDouble() * spread * 2 - spread;
    }
}
