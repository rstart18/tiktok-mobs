package dev.juanse.tiktokmobs.plugin.command;

import dev.juanse.tiktokmobs.core.GiftRegistry;
import dev.juanse.tiktokmobs.core.event.EventType;
import dev.juanse.tiktokmobs.plugin.TikTokMobsPlugin;
import dev.juanse.tiktokmobs.plugin.runtime.Messages;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Consola de control en el juego. {@code /ttmobs test} es el comando que hace el proyecto
 * desarrollable: sin él tendrías que abrir un live real para probar cada cambio.
 */
public final class TikTokMobsCommand implements CommandExecutor, TabCompleter {

    private static final List<String> SUBCOMMANDS = List.of(
            "status", "reload", "connect", "disconnect", "target", "overlay", "gifts", "sources",
            "test", "testboss", "likes", "follow", "share", "raw", "clear", "reset", "coins",
            "fase", "modo");

    private final TikTokMobsPlugin plugin;

    public TikTokMobsCommand(TikTokMobsPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (args.length == 0) {
            help(sender);
            return true;
        }

        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "status" -> sender.sendMessage(Messages.color(plugin.statusReport()));
            case "reload" -> {
                plugin.reloadEverything();
                sender.sendMessage(Messages.color("&aConfig recargada. &7" + plugin.sourceDescription()));
            }
            case "connect" -> {
                plugin.openSource();
                sender.sendMessage(Messages.color("&aConectando: &7" + plugin.sourceDescription()));
            }
            case "disconnect" -> {
                plugin.closeSource();
                sender.sendMessage(Messages.color("&eFuente cerrada."));
            }
            case "raw" -> {
                boolean on = plugin.toggleRawDump();
                sender.sendMessage(Messages.color(on
                        ? "&aVolcado de JSON ACTIVADO. Dona una rosa y mira la consola: "
                        + "usa esos nombres de campo si tu TikFinity manda algo distinto."
                        : "&eVolcado de JSON desactivado."));
            }
            case "clear" -> {
                int removed = plugin.clearSpawned();
                sender.sendMessage(Messages.color("&aEliminadas &f" + removed + "&a entidades spawneadas."));
            }
            case "reset" -> {
                plugin.resetLedger();
                sender.sendMessage(Messages.color("&aContadores del live reiniciados (monedas, top, likes)."));
            }
            case "overlay" -> {
                sender.sendMessage(Messages.color("&7Overlay: &f" + plugin.overlayDescription()));
                sender.sendMessage(Messages.color("&7En OBS: Browser source, &f1080x1920&7, esa URL."));
                sender.sendMessage(Messages.color("&7Para ver el diseño sin eventos reales, añade &f?demo=1"));
            }
            case "gifts" -> gifts(sender);
            case "sources" -> sources(sender);
            case "coins" -> {
                int monedas = parseInt(args, 1, 0);
                String donante = args.length > 2 ? args[2] : "recuperado";
                int total = plugin.addCoins(donante, monedas);
                sender.sendMessage(Messages.color("&aSumadas &f" + monedas + "&a monedas a &f" + donante
                        + "&7. Total del live: &f" + total));
                sender.sendMessage(Messages.color("&7(solo el contador: no spawnea nada)"));
            }
            case "target" -> target(sender, args);
            case "fase" -> fase(sender, args);
            case "modo" -> modo(sender, args);
            case "test" -> test(sender, args);
            case "testboss" -> testBoss(sender, args);
            case "likes" -> {
                int likes = parseInt(args, 1, 100);
                plugin.fake().emitLikes(senderName(sender), likes);
                sender.sendMessage(Messages.color("&7Inyectados &f" + likes + "&7 likes."));
            }
            case "follow" -> {
                plugin.fake().emitSimple(EventType.FOLLOW, senderName(sender));
                sender.sendMessage(Messages.color("&7Inyectado un follow."));
            }
            case "share" -> {
                plugin.fake().emitSimple(EventType.SHARE, senderName(sender));
                sender.sendMessage(Messages.color("&7Inyectado un share."));
            }
            default -> help(sender);
        }
        return true;
    }

    private void sources(CommandSender sender) {
        var sources = plugin.sources();
        if (sources.isEmpty()) {
            sender.sendMessage(Messages.color("&cNo hay ninguna fuente configurada."));
            return;
        }

        sender.sendMessage(Messages.color("&6Lives conectados&7 (eventos recibidos por relay: &f"
                + plugin.ingestedEvents() + "&7)"));

        for (var source : sources) {
            String estado = source.dialsOut()
                    ? (plugin.sourceConnected(source.id()) ? "&aconectado" : "&cdesconectado")
                    : "&erelay";

            sender.sendMessage(Messages.color("&f" + source.id() + " &7| " + estado
                    + " &7| " + (source.dialsOut() ? source.url() : "espera POST en /ingest?source=" + source.id())
                    + (source.hasOwnPlayer() ? " &7| mobs a &f" + source.player() : " &7| arena comun")));
        }
    }

    private void gifts(CommandSender sender) {
        List<GiftRegistry.Seen> seen = plugin.giftRegistry().seen();

        if (seen.isEmpty()) {
            sender.sendMessage(Messages.color("&7Aún no ha entrado ningún regalo. "
                    + "En cuanto alguien done, aparecerá aquí con su id."));
            return;
        }

        sender.sendMessage(Messages.color("&6Regalos vistos en esta sesión&7 "
                + "(el id es lo que debes mapear, no el nombre)"));

        for (GiftRegistry.Seen gift : seen) {
            sender.sendMessage(Messages.color(
                    (gift.explicitlyMapped() ? "&a* " : "&e- ")
                            + "&fid " + gift.id()
                            + " &7| &f" + gift.name()
                            + " &7| &b" + gift.coins() + " monedas"
                            + " &7| x" + gift.times()
                            + (gift.explicitlyMapped() ? " &a(mapeado)" : " &7(por tramos)")));
        }

        String snippet = plugin.giftRegistry().asConfigSnippet();
        if (!snippet.isBlank()) {
            sender.sendMessage(Messages.color("&7Bloque listo para pegar en config.yml "
                    + "(está completo en la consola del servidor):"));
            plugin.getLogger().info("Bloque gift-ids sugerido:\n" + snippet);
        }
    }

    private void target(CommandSender sender, String[] args) {
        if (args.length >= 2 && args[1].equalsIgnoreCase("here")) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage(Messages.color("&cEse subcomando necesita un jugador."));
                return;
            }
            plugin.setTargetOverride(player.getLocation());
            sender.sendMessage(Messages.color("&aArena fijada donde estás. &7(/ttmobs target clear para quitarlo)"));
            return;
        }
        if (args.length >= 2 && args[1].equalsIgnoreCase("clear")) {
            plugin.setTargetOverride(null);
            sender.sendMessage(Messages.color("&eOverride de arena quitado. Vuelve al modo del config."));
            return;
        }
        sender.sendMessage(Messages.color("&7Objetivo actual: &f" + plugin.targetDescription()));
    }

    /** Cambia de catalogo en caliente, o lista los que hay. */
    private void modo(CommandSender sender, String[] args) {
        var disponibles = plugin.modosDisponibles();

        if (args.length < 2) {
            sender.sendMessage(Messages.color("&6Modo activo: &f" + plugin.modoActivo()));
            if (disponibles.isEmpty()) {
                sender.sendMessage(Messages.color(
                        "&7No hay modos extra. Anade un bloque &fmodos&7 al config.yml."));
            } else {
                sender.sendMessage(Messages.color("&7Disponibles: &fnormal&7, &f"
                        + String.join("&7, &f", disponibles)));
            }
            sender.sendMessage(Messages.color("&7/ttmobs modo <nombre>"));
            return;
        }

        String pedido = args[1];
        if (plugin.cambiarModo(pedido)) {
            sender.sendMessage(Messages.color("&aModo cambiado a &f" + plugin.modoActivo()));
            sender.sendMessage(Messages.color(
                    "&7El marcador y el caos siguen donde estaban; solo cambia el catalogo."));
        } else {
            sender.sendMessage(Messages.color("&cNo existe el modo '" + pedido + "'."));
            sender.sendMessage(Messages.color("&7Hay: &fnormal&7, &f"
                    + String.join("&7, &f", disponibles)));
        }
    }

    /**
     * El estado del formato, y el salto a mano.
     *
     * <p>El salto existe porque en directo el reloj nunca cuadra con lo que esta pasando:
     * si la ronda se pone buena la alargas, y si se muere la cortas.
     */
    private void fase(CommandSender sender, String[] args) {
        var plan = plugin.phasePlan();

        if (args.length >= 2 && args[1].equalsIgnoreCase("siguiente")) {
            if (!plan.activo()) {
                sender.sendMessage(Messages.color("&cNo hay formato configurado (bloque 'fases')."));
                return;
            }
            plugin.saltarFase();
            return;
        }

        if (!plan.activo()) {
            sender.sendMessage(Messages.color(
                    "&7Sin formato. Las donaciones pasan todas, como siempre."));
            sender.sendMessage(Messages.color(
                    "&7Para activarlo, anade un bloque &ffases&7 al config.yml."));
            return;
        }

        var actual = plan.actual();
        int restan = plan.restantes(System.currentTimeMillis());
        sender.sendMessage(Messages.color("&6Fase: &f" + actual.nombre()
                + (restan < 0 ? " &7(sin limite)" : " &7(" + restan + "s)")));
        sender.sendMessage(Messages.color("&6Donaciones: "
                + (actual.donaciones() ? "&aABIERTAS" : "&cCERRADAS")));
        sender.sendMessage(Messages.color("&6Retenidas: &f" + plugin.retenidas()
                + " &7(caen al abrir la fase)"));
        sender.sendMessage(Messages.color("&7Plan: &f" + plan.fases().size()
                + " fase(s)" + (plan.ciclico() ? ", ciclico" : ", una vuelta")));
        sender.sendMessage(Messages.color("&7/ttmobs fase siguiente &8- saltar ahora"));
    }

    private void test(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(Messages.color("&7Uso: &f/ttmobs test <regalo> [cantidad] [monedas]"));
            sender.sendMessage(Messages.color("&7Ej: &f/ttmobs test Rose 30 &7(simula una racha completa de 30)"));
            return;
        }

        String giftName = args[1];
        int count = parseInt(args, 2, 1);
        int coins = parseInt(args, 3, 1);

        if (count > 1) {
            // Racha completa: N-1 eventos abiertos + el de cierre. Debe producir UNA acción.
            plugin.fake().emitStreak(senderName(sender), 0, giftName, coins, count);
        } else {
            plugin.fake().emitGift(senderName(sender), 0, giftName, coins, 1);
        }
        sender.sendMessage(Messages.color("&7Inyectado &f" + giftName + " x" + count
                + "&7 (" + coins + " monedas c/u = " + (coins * count) + " total)."));
    }

    /** Probar el jefe sin abrir un live. Pasa por el mismo camino que una donación real. */
    private void testBoss(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(Messages.color("&7Uso: &f/ttmobs testboss <donante> [monedas] [id]"));
            sender.sendMessage(Messages.color("&7Ej: &f/ttmobs testboss Pedro 500"));
            if (!plugin.bossIds().isEmpty()) {
                sender.sendMessage(Messages.color("&7Jefes definidos: &f"
                        + String.join(", ", plugin.bossIds())));
            }
            return;
        }

        String donante = args[1];
        int monedas = parseInt(args, 2, 500);
        String id = args.length > 3 ? args[3] : "";

        String resultado = plugin.testBoss(donante, monedas, id);
        if (resultado == null) {
            sender.sendMessage(Messages.color("&cNo hay ningún jefe con ese id en el bloque "
                    + "&f'bosses:'&c del config.yml"));
            return;
        }
        sender.sendMessage(Messages.color("&a" + resultado));
    }

    private void help(CommandSender sender) {
        sender.sendMessage(Messages.color("&6TikTokMobs &7- puente TikTok Live -> Minecraft"));
        sender.sendMessage(Messages.color("&f/ttmobs status &7estado, cola, TPS, límites"));
        sender.sendMessage(Messages.color("&f/ttmobs test Rose 30 &7simula una racha de 30 rosas"));
        sender.sendMessage(Messages.color("&f/ttmobs testboss Pedro 500 &7suelta un jefe a mano"));
        sender.sendMessage(Messages.color("&f/ttmobs likes 250 &7inyecta likes"));
        sender.sendMessage(Messages.color("&f/ttmobs coins 103 &7suma monedas al marcador sin spawnear"));
        sender.sendMessage(Messages.color("&f/ttmobs sources &7lives conectados y su estado"));
        sender.sendMessage(Messages.color("&f/ttmobs gifts &7regalos vistos, con su id para mapearlos"));
        sender.sendMessage(Messages.color("&f/ttmobs overlay &7URL del overlay para OBS"));
        sender.sendMessage(Messages.color("&f/ttmobs raw &7vuelca el JSON crudo de TikFinity al log"));
        sender.sendMessage(Messages.color("&f/ttmobs target here &7fija la arena donde estás"));
        sender.sendMessage(Messages.color("&f/ttmobs clear &7elimina los mobs spawneados"));
        sender.sendMessage(Messages.color("&f/ttmobs reload &7recarga config.yml"));
    }

    private String senderName(CommandSender sender) {
        return sender instanceof Player player ? player.getName() : "consola";
    }

    private int parseInt(String[] args, int index, int fallback) {
        if (args.length <= index) return fallback;
        try {
            return Math.max(1, Integer.parseInt(args[index]));
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                      @NotNull String alias, @NotNull String[] args) {
        if (args.length == 1) {
            return partial(SUBCOMMANDS, args[0]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("target")) {
            return partial(List.of("here", "clear"), args[1]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("modo")) {
            java.util.List<String> todos = new ArrayList<>();
            todos.add("normal");
            todos.addAll(plugin.modosDisponibles());
            return partial(todos, args[1]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("fase")) {
            return partial(List.of("siguiente"), args[1]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("test")) {
            return partial(List.of("Rose", "Finger Heart", "TikTok", "Galaxy", "Lion"), args[1]);
        }
        if (args.length == 4 && args[0].equalsIgnoreCase("testboss")) {
            return partial(new ArrayList<>(plugin.bossIds()), args[3]);
        }
        return List.of();
    }

    private List<String> partial(List<String> options, String prefix) {
        String lower = prefix.toLowerCase(Locale.ROOT);
        List<String> matches = new ArrayList<>();
        for (String option : options) {
            if (option.toLowerCase(Locale.ROOT).startsWith(lower)) matches.add(option);
        }
        return matches;
    }
}
