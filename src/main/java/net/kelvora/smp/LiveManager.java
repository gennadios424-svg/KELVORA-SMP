package net.kelvora.smp;

import java.net.URI;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

/** Ephemeral live status for LuckPerms media members. */
final class LiveManager implements Listener, CommandExecutor {
    private static final Map<UUID, LiveSession> LIVE = new ConcurrentHashMap<>();
    private final JavaPlugin plugin;

    LiveManager(JavaPlugin plugin) { this.plugin = plugin; }

    void register() {
        Bukkit.getPluginManager().registerEvents(this, plugin);
        PluginCommand cmd = plugin.getCommand("live");
        if (cmd == null) { plugin.getLogger().warning("Missing /live command registration."); return; }
        cmd.setExecutor(this);
    }

    static boolean isLive(UUID id) { return LIVE.containsKey(id); }
    static String link(UUID id) { LiveSession s = LIVE.get(id); return s == null ? null : s.url; }

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player p)) { sender.sendMessage("Use this command in-game."); return true; }
        if (!p.hasPermission("kelvora.media")) {
            p.sendMessage(Component.text("Only players with the Media role can use /live. Ask staff to assign the LuckPerms media group.", NamedTextColor.RED));
            p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, .3f, .8f);
            return true;
        }
        if (args.length == 1 && args[0].equalsIgnoreCase("off")) {
            if (LIVE.remove(p.getUniqueId()) == null) { p.sendMessage(Component.text("You are not marked as live.", NamedTextColor.YELLOW)); return true; }
            LeaderboardManager.refreshLiveStatuses();
            p.sendMessage(Component.text("Your live status has been cleared.", NamedTextColor.GREEN));
            p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, .25f, 1.1f);
            Bukkit.broadcast(Component.text("🎥 " + p.getName() + " is no longer marked LIVE.", NamedTextColor.GRAY));
            return true;
        }
        if (args.length != 1) {
            p.sendMessage(Component.text("Usage: /live <Twitch/YouTube URL> or /live off", NamedTextColor.YELLOW));
            return true;
        }
        String normalized = validate(args[0]);
        if (normalized == null) {
            p.sendMessage(Component.text("Invalid link. Use a Twitch or YouTube HTTPS stream URL.", NamedTextColor.RED));
            p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, .3f, .8f);
            return true;
        }
        LIVE.put(p.getUniqueId(), new LiveSession(normalized, System.currentTimeMillis()));
        LeaderboardManager.refreshLiveStatuses();
        p.sendMessage(Component.text("Your live status is active. Use /live off when you finish.", NamedTextColor.GREEN));
        p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, .35f, 1.2f);
        Component announce = Component.text("🔴 " + p.getName() + " is LIVE! ", NamedTextColor.LIGHT_PURPLE)
            .append(Component.text("Click to watch", NamedTextColor.AQUA).clickEvent(ClickEvent.openUrl(normalized)));
        Bukkit.broadcast(announce);
        return true;
    }

    private String validate(String input) {
        try {
            URI uri = URI.create(input.trim());
            if (!"https".equalsIgnoreCase(uri.getScheme()) && !"http".equalsIgnoreCase(uri.getScheme())) return null;
            if (uri.getUserInfo() != null || uri.getHost() == null) return null;
            String host = uri.getHost().toLowerCase(Locale.ROOT);
            if (!(host.equals("twitch.tv") || host.endsWith(".twitch.tv") || host.equals("youtube.com")
                || host.endsWith(".youtube.com") || host.equals("youtu.be"))) return null;
            if (host.equals("twitch.tv") || host.endsWith(".twitch.tv")) {
                String path = uri.getPath();
                if (path == null || path.equals("/") || path.contains("/")) return null;
            } else {
                String path = uri.getPath();
                if (path == null || path.equals("/") || path.equalsIgnoreCase("/live")) return null;
            }
            return uri.toASCIIString();
        } catch (IllegalArgumentException ex) { return null; }
    }

    @EventHandler public void onQuit(PlayerQuitEvent e) {
        if (LIVE.remove(e.getPlayer().getUniqueId()) != null) LeaderboardManager.refreshLiveStatuses();
    }
    private record LiveSession(String url, long startedAt) {}
}
