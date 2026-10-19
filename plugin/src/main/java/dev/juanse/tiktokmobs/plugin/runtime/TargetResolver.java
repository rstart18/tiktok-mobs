package dev.juanse.tiktokmobs.plugin.runtime;

import dev.juanse.tiktokmobs.plugin.config.PluginSettings;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.util.Collection;

/** Decide dónde pasan las cosas: alrededor de un jugador, del primero conectado, o en una arena fija. */
public final class TargetResolver {

    private volatile PluginSettings settings;
    private volatile Location override;
    /** streamerId -> nombre del jugador al que le salen SUS donaciones. */
    private final java.util.Map<String, String> byStreamer = new java.util.concurrent.ConcurrentHashMap<>();

    public TargetResolver(PluginSettings settings) {
        this.settings = settings;
    }

    public void setSettings(PluginSettings settings) {
        this.settings = settings;
    }

    /** /ttmobs target here: fija la arena donde estás parado, sin editar el YAML. */
    public void setOverride(Location location) {
        this.override = location == null ? null : location.clone();
    }

    public void clearOverride() {
        this.override = null;
    }

    /** Registra que las donaciones de este live salen alrededor de ese jugador. */
    public void bindStreamer(String streamerId, String playerName) {
        if (streamerId == null || streamerId.isBlank()) return;
        if (playerName == null || playerName.isBlank()) {
            byStreamer.remove(streamerId);
        } else {
            byStreamer.put(streamerId, playerName.trim());
        }
    }

    public void clearStreamers() {
        byStreamer.clear();
    }

    /**
     * Objetivo para las donaciones de un live concreto. Si esa fuente tiene jugador propio
     * y está conectado, los mobs le salen a él; si no, cae al objetivo general (arena común).
     */
    public Location resolve(String streamerId) {
        String name = byStreamer.get(streamerId);
        if (name != null) {
            Player player = Bukkit.getPlayerExact(name);
            if (player != null && player.isOnline()) return player.getLocation();
        }
        return resolve();
    }

    /** @return la ubicación objetivo, o null si no hay nadie/nada válido (entonces no se actúa). */
    public Location resolve() {
        if (override != null) return override.clone();

        return switch (settings.targetMode()) {
            case PLAYER -> namedPlayer();
            case FIRST_ONLINE -> firstOnline();
            case LOCATION -> fixedLocation();
        };
    }

    /** El jugador al que se le aplican efectos. Puede ser null en modo arena sin nadie cerca. */
    public Player resolvePlayer() {
        if (settings.targetMode() == PluginSettings.TargetMode.PLAYER) {
            Player player = Bukkit.getPlayerExact(settings.targetPlayerName());
            if (player != null && player.isOnline()) return player;
        }
        Location location = resolve();
        if (location == null) return null;

        Player nearest = null;
        double best = Double.MAX_VALUE;
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (!player.getWorld().equals(location.getWorld())) continue;
            double distance = player.getLocation().distanceSquared(location);
            if (distance < best) {
                best = distance;
                nearest = player;
            }
        }
        return nearest;
    }

    private Location namedPlayer() {
        Player player = Bukkit.getPlayerExact(settings.targetPlayerName());
        return (player != null && player.isOnline()) ? player.getLocation() : firstOnline();
    }

    private Location firstOnline() {
        Collection<? extends Player> online = Bukkit.getOnlinePlayers();
        for (Player player : online) {
            return player.getLocation();
        }
        return null;
    }

    private Location fixedLocation() {
        World world = Bukkit.getWorld(settings.targetWorld());
        if (world == null) return firstOnline();
        return new Location(world, settings.targetX(), settings.targetY(), settings.targetZ());
    }

    public String describe() {
        if (override != null) {
            return "override " + format(override);
        }
        return switch (settings.targetMode()) {
            case PLAYER -> "jugador '" + settings.targetPlayerName() + "'";
            case FIRST_ONLINE -> "primer jugador conectado";
            case LOCATION -> "arena fija " + settings.targetWorld() + " "
                    + (int) settings.targetX() + "," + (int) settings.targetY() + "," + (int) settings.targetZ();
        };
    }

    private String format(Location location) {
        return location.getWorld().getName() + " "
                + location.getBlockX() + "," + location.getBlockY() + "," + location.getBlockZ();
    }
}
