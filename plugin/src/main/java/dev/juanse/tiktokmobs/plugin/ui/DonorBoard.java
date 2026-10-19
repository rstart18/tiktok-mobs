package dev.juanse.tiktokmobs.plugin.ui;

import dev.juanse.tiktokmobs.core.DonorLedger;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.scoreboard.Criteria;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.ScoreboardManager;

import java.util.List;

/**
 * Top de donantes en el sidebar. Compatible con clientes Bedrock vía Geyser.
 *
 * <p>Detalle del scoreboard de Minecraft: las "entries" son cadenas únicas, así que el
 * puesto va dentro del texto. Y hay que borrar las entradas viejas a mano, porque si no
 * se quedan pegadas cuando alguien baja del top.
 */
public final class DonorBoard {

    private static final String OBJECTIVE_ID = "ttmobs_top";

    private final JavaPlugin plugin;
    private final DonorLedger ledger;
    private final int size;

    private Scoreboard board;
    private Objective objective;
    private BukkitTask task;

    public DonorBoard(JavaPlugin plugin, DonorLedger ledger, int size) {
        this.plugin = plugin;
        this.ledger = ledger;
        this.size = Math.max(1, Math.min(size, 10));
    }

    public void start() {
        stop();

        ScoreboardManager manager = Bukkit.getScoreboardManager();
        if (manager == null) {
            plugin.getLogger().warning("sin ScoreboardManager: el top de donantes queda desactivado");
            return;
        }

        board = manager.getNewScoreboard();
        objective = board.registerNewObjective(OBJECTIVE_ID, Criteria.DUMMY,
                Component.text("Top donantes", NamedTextColor.GOLD));
        objective.setDisplaySlot(DisplaySlot.SIDEBAR);

        for (Player player : Bukkit.getOnlinePlayers()) {
            player.setScoreboard(board);
        }
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::update, 40L, 40L);
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        ScoreboardManager manager = Bukkit.getScoreboardManager();
        if (manager != null) {
            for (Player player : Bukkit.getOnlinePlayers()) {
                player.setScoreboard(manager.getMainScoreboard());
            }
        }
        board = null;
        objective = null;
    }

    public void showTo(Player player) {
        if (board != null) player.setScoreboard(board);
    }

    private void update() {
        if (board == null || objective == null) return;

        // Fuera las entradas anteriores: si no, los que caen del top se quedan colgados.
        for (String entry : board.getEntries()) {
            board.resetScores(entry);
        }

        List<DonorLedger.Donor> top = ledger.top(size);
        int position = 1;
        for (DonorLedger.Donor donor : top) {
            String entry = position + ". " + donor.label();
            if (entry.length() > 40) entry = entry.substring(0, 40);
            objective.getScore(entry).setScore(donor.coins());
            position++;
        }

        if (top.isEmpty()) {
            objective.getScore("sin donaciones aún").setScore(0);
        }
    }
}
