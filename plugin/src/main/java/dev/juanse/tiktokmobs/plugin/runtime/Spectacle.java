package dev.juanse.tiktokmobs.plugin.runtime;

import dev.juanse.tiktokmobs.plugin.boss.Fx;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/**
 * Los efectos con duración: los que cambian el mundo o al jugador y tienen que deshacerse.
 *
 * <p>Todo lo de aquí comparte una regla: <b>nada queda roto cuando termina</b>. Los bloques
 * vuelven a ser lo que eran, el kit se evapora, el jugador vuelve del Nether. Y si el servidor
 * se cae o se recarga el plugin en medio, {@link #shutdown()} deshace lo que quede pendiente,
 * porque un live no puede dejarte el mundo con el suelo de magma para siempre.
 */
public final class Spectacle {

    /** Un bloque que hay que devolver a como estaba. */
    private record Restore(Block block, BlockData data) {
    }

    private static final Random RANDOM = new Random();

    private final JavaPlugin plugin;

    private final Deque<Restore> pendientes = new ArrayDeque<>();
    private final Set<UUID> taladrando = new HashSet<>();
    /** Quién acaba de morir por la ruleta: su muerte no le cuesta el inventario. */
    private final Set<UUID> muertePorRuleta = new HashSet<>();
    private final Map<UUID, Location> vueltaDelNether = new HashMap<>();
    private final Deque<BukkitTask> tareas = new ArrayDeque<>();

    public Spectacle(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    // ------------------------------------------------------------------ nether

    /**
     * Te manda al Nether un rato y te trae de vuelta donde estabas.
     *
     * <p>El Nether es roca maciza casi entero, así que no basta con teletransportar: hay que
     * buscarle un hueco y, si no lo hay, abrirlo. Soltar a alguien dentro de la piedra es
     * matarlo al instante, y eso no es el chiste.
     */
    public void nether(Player player, int segundos) {
        World nether = buscarNether();
        if (nether == null) {
            player.sendMessage(Component.text("(el Nether está desactivado en este servidor)",
                    NamedTextColor.GRAY));
            return;
        }

        Location origen = player.getLocation().clone();
        vueltaDelNether.put(player.getUniqueId(), origen);

        Location destino = huecoEnElNether(nether, origen);
        Fx.burst(origen);
        Fx.sound(origen, "minecraft:block.portal.travel", 1.0f, 1.0f);

        player.teleport(destino);
        player.setFallDistance(0f);
        titulo(player, "AL NETHER", "vuelves en " + segundos + " segundos", NamedTextColor.DARK_RED);

        programar(() -> {
            Location vuelta = vueltaDelNether.remove(player.getUniqueId());
            if (vuelta == null || !player.isOnline() || player.isDead()) return;

            // Si ya se fue del Nether por su cuenta (portal, muerte, /tp), no se le mueve.
            if (!player.getWorld().equals(nether)) return;

            player.teleport(vuelta);
            player.setFallDistance(0f);
            Fx.burst(vuelta);
            titulo(player, "DE VUELTA", "", NamedTextColor.GREEN);
        }, segundos * 20L);
    }

    private World buscarNether() {
        for (World world : Bukkit.getWorlds()) {
            if (world.getEnvironment() == World.Environment.NETHER) return world;
        }
        return null;
    }

    /** Busca un hueco de 2 de alto con suelo; si no encuentra, lo abre a martillazos. */
    private Location huecoEnElNether(World nether, Location origen) {
        int x = origen.getBlockX() / 8;
        int z = origen.getBlockZ() / 8;

        for (int y = 32; y < 100; y++) {
            Block suelo = nether.getBlockAt(x, y, z);
            Block pies = nether.getBlockAt(x, y + 1, z);
            Block cabeza = nether.getBlockAt(x, y + 2, z);

            if (!suelo.isPassable() && pies.isPassable() && cabeza.isPassable()
                    && suelo.getType() != Material.LAVA) {
                return new Location(nether, x + 0.5, y + 1, z + 0.5);
            }
        }

        // Sin hueco: se abre una burbuja y se le pone suelo. Esto SÍ modifica el Nether,
        // pero es el Nether y es un cubo de 3x3; el mundo principal no se toca.
        int y = 64;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                nether.getBlockAt(x + dx, y, z + dz).setType(Material.NETHERRACK);
                for (int dy = 1; dy <= 3; dy++) {
                    nether.getBlockAt(x + dx, y + dy, z + dz).setType(Material.AIR);
                }
            }
        }
        return new Location(nether, x + 0.5, y + 1, z + 0.5);
    }

    // ------------------------------------------------------------------ el suelo es lava

    /**
     * Convierte bloques del suelo a tu alrededor y los devuelve a como estaban.
     *
     * <p>Por defecto usa <b>magma</b> y no lava, y es deliberado: la lava <i>fluye</i>. Un
     * bloque de lava se convierte en diez, se cuela por las cuevas, prende fuego a lo que
     * toca y nada de eso se puede deshacer. Quemarle la base a alguien en directo no tiene
     * gracia al día siguiente. El magma quema igual al pisarlo, se queda quieto, y vuelve.
     */
    public void sueloEsLava(Player player, Material material, int radio, int bloques,
                            int segundos) {
        World world = player.getWorld();
        Location centro = player.getLocation();
        int convertidos = 0;

        for (int intento = 0; intento < bloques * 6 && convertidos < bloques; intento++) {
            int dx = RANDOM.nextInt(radio * 2 + 1) - radio;
            int dz = RANDOM.nextInt(radio * 2 + 1) - radio;

            Block suelo = world.getBlockAt(
                    centro.getBlockX() + dx, centro.getBlockY() - 1, centro.getBlockZ() + dz);

            if (suelo.isPassable() || suelo.getType() == Material.BEDROCK) continue;
            if (!world.getBlockAt(suelo.getX(), suelo.getY() + 1, suelo.getZ()).isPassable()) continue;

            pendientes.addLast(new Restore(suelo, suelo.getBlockData()));
            suelo.setType(material);
            convertidos++;
        }

        if (convertidos == 0) return;

        titulo(player, "EL SUELO ES LAVA", "muévete", NamedTextColor.GOLD);
        Fx.sound(centro, "minecraft:block.lava.pop", 1.0f, 0.8f);

        final int cuantos = convertidos;
        programar(() -> devolver(cuantos), segundos * 20L);
    }

    /** Devuelve los últimos N bloques tocados. */
    private void devolver(int cuantos) {
        for (int i = 0; i < cuantos; i++) {
            Restore restore = pendientes.pollLast();
            if (restore == null) return;
            restore.block().setBlockData(restore.data(), false);
        }
    }

    // ------------------------------------------------------------------ ruleta rusa

    /**
     * Una de seis.
     *
     * <p>El valor está en el suspenso, no en la muerte: por eso se anuncia la recámara antes
     * y se deja un tick de silencio. Sin eso es solo "a veces te mueres".
     *
     * <p><b>Y no te cuesta el inventario.</b> En Minecraft morir te tira todo, así que una
     * ruleta normal, a 50 bloques bajo tierra, te hace perder el pico y la partida: la primera
     * vez es un clip y la segunda cierras el live. Al quitarle el coste real se puede mandar
     * seguido, que es justo lo que queremos que pase.
     */
    public void ruletaRusa(Player player) {
        int recamara = RANDOM.nextInt(6) + 1;
        boolean muere = recamara == 1;

        titulo(player, "RULETA RUSA", "recámara " + recamara + " de 6",
                muere ? NamedTextColor.DARK_RED : NamedTextColor.YELLOW);
        Fx.sound(player.getLocation(), "minecraft:block.lever.click", 1.0f, 0.6f);

        programar(() -> {
            if (!player.isOnline() || player.isDead()) return;

            if (muere) {
                Fx.sound(player.getLocation(), "minecraft:entity.generic.explode", 1.0f, 1.4f);
                Fx.burst(player.getLocation());
                anunciar("&4&l💀 RULETA RUSA &7-> &c" + player.getName()
                        + " cayó &7(conserva sus cosas)");
                muertePorRuleta.add(player.getUniqueId());
                player.setHealth(0.0);
            } else {
                Fx.sound(player.getLocation(), "minecraft:block.lever.click", 1.0f, 1.8f);
                anunciar("&e&l🔫 RULETA RUSA &7-> &avacía. Se salvó.");
            }
        }, 30L);
    }

    /**
     * @return true si esta muerte la provocó la ruleta, y la marca como ya atendida.
     *         El listener lo usa para no tirarle el inventario.
     */
    public boolean esMuertePorRuleta(Player player) {
        return muertePorRuleta.remove(player.getUniqueId());
    }

    // ------------------------------------------------------------------ pico taladro

    /** Durante unos segundos, cada bloque que rompas se lleva los 8 de alrededor. */
    public void taladro(Player player, int segundos) {
        taladrando.add(player.getUniqueId());

        titulo(player, "PICO TALADRO", segundos + " segundos en 3x3", NamedTextColor.LIGHT_PURPLE);
        Fx.sound(player.getLocation(), "minecraft:block.beacon.power_select", 1.0f, 1.2f);

        programar(() -> {
            taladrando.remove(player.getUniqueId());
            if (player.isOnline()) {
                titulo(player, "", "se acabó el taladro", NamedTextColor.GRAY);
            }
        }, segundos * 20L);
    }

    public boolean estaTaladrando(Player player) {
        return taladrando.contains(player.getUniqueId());
    }

    /**
     * Rompe el plano 3x3 alrededor del bloque, perpendicular a hacia dónde mira el jugador.
     *
     * <p>{@code breakNaturally} no dispara BlockBreakEvent, así que esto no se llama a sí mismo.
     */
    public void romperAlrededor(Player player, Block centro) {
        Set<Material> intocables = Set.of(Material.BEDROCK, Material.BARRIER,
                Material.END_PORTAL_FRAME, Material.OBSIDIAN);

        var direccion = player.getEyeLocation().getDirection();
        double ax = Math.abs(direccion.getX());
        double ay = Math.abs(direccion.getY());
        double az = Math.abs(direccion.getZ());

        // El plano que se rompe es el perpendicular al eje por el que miras.
        List<int[]> offsets;
        if (ay >= ax && ay >= az) {
            offsets = plano(1, 0, 1);     // mirando arriba o abajo: plano horizontal
        } else if (ax >= az) {
            offsets = plano(0, 1, 1);     // mirando por X: plano YZ
        } else {
            offsets = plano(1, 1, 0);     // mirando por Z: plano XY
        }

        for (int[] o : offsets) {
            if (o[0] == 0 && o[1] == 0 && o[2] == 0) continue;

            Block block = centro.getRelative(o[0], o[1], o[2]);
            if (block.isPassable() || intocables.contains(block.getType())) continue;
            if (block.getType().getHardness() < 0) continue;

            block.breakNaturally(player.getInventory().getItemInMainHand());
        }
    }

    private List<int[]> plano(int ejeX, int ejeY, int ejeZ) {
        List<int[]> out = new java.util.ArrayList<>(9);
        for (int a = -1; a <= 1; a++) {
            for (int b = -1; b <= 1; b++) {
                int x = ejeX == 1 ? a : 0;
                int y = ejeY == 1 ? (ejeX == 1 ? b : a) : 0;
                int z = ejeZ == 1 ? b : 0;
                out.add(new int[]{x, y, z});
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ cierre

    /** Deshace todo lo pendiente. Se llama en onDisable y en cada reload. */
    public void shutdown() {
        while (!tareas.isEmpty()) {
            BukkitTask tarea = tareas.pollFirst();
            if (tarea != null) tarea.cancel();
        }

        // Los bloques vuelven SIEMPRE, aunque el plugin se esté apagando: dejarle el suelo
        // de magma para siempre por recargar el plugin sería el peor fallo del proyecto.
        Restore restore;
        while ((restore = pendientes.pollLast()) != null) {
            restore.block().setBlockData(restore.data(), false);
        }

        taladrando.clear();
        vueltaDelNether.clear();
        muertePorRuleta.clear();
    }

    private void programar(Runnable accion, long ticks) {
        tareas.addLast(Bukkit.getScheduler().runTaskLater(plugin, accion, ticks));
        // La cola no crece sin fin: las ya ejecutadas se van descartando.
        while (tareas.size() > 64) {
            BukkitTask vieja = tareas.pollFirst();
            if (vieja != null && !vieja.isCancelled()) continue;
        }
    }

    private void titulo(Player player, String principal, String sub, NamedTextColor color) {
        player.showTitle(Title.title(
                Component.text(principal, color),
                Component.text(sub, NamedTextColor.GRAY),
                Title.Times.times(Duration.ofMillis(150), Duration.ofMillis(1600),
                        Duration.ofMillis(400))));
    }

    private void anunciar(String legacy) {
        Component mensaje = Messages.color(legacy);
        for (Player player : Bukkit.getOnlinePlayers()) {
            player.sendMessage(mensaje);
        }
    }
}
