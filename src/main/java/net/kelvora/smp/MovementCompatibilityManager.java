package net.kelvora.smp;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerKickEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Narrow recovery for reported false "moving through blocks" kicks near chest and mud collision shapes.
 * It never grants a movement bypass: affected movement is rolled back to the last known safe position.
 */
final class MovementCompatibilityManager implements Listener {
    private final JavaPlugin plugin;
    private final Map<UUID, Location> lastSafeLocations = new HashMap<>();

    MovementCompatibilityManager(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    void register() {
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        rememberIfSafe(event.getPlayer(), event.getPlayer().getLocation());
    }

    @EventHandler
    public void onMove(PlayerMoveEvent event) {
        if (event.getTo() == null || event.getFrom().getWorld() == null) return;
        rememberIfSafe(event.getPlayer(), event.getFrom());
    }

    private void rememberIfSafe(Player player, Location location) {
        if (location.getWorld() == null || isNearProblemBlocks(location)) return;
        Block feet = location.getBlock();
        Block head = feet.getRelative(0, 1, 0);
        if (feet.isPassable() && head.isPassable()) {
            lastSafeLocations.put(player.getUniqueId(), location.clone());
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onKick(PlayerKickEvent event) {
        String reason = event.getReason();
        if (reason == null) return;
        String normalized = ChatColor.stripColor(reason).toLowerCase(Locale.ROOT);
        boolean movementFalsePositive = normalized.contains("moving through blocks")
                || normalized.contains("moved through blocks")
                || normalized.contains("phase")
                || normalized.contains("noclip")
                || normalized.contains("no-clip");
        if (!movementFalsePositive) return;

        Player player = event.getPlayer();
        if (!isNearProblemBlocks(player.getLocation())) return;
        Location safe = lastSafeLocations.get(player.getUniqueId());
        if (safe == null || safe.getWorld() == null) return;

        event.setCancelled(true);
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!player.isOnline()) return;
            player.teleport(safe);
            player.sendMessage(ChatColor.YELLOW
                    + "Movement recovery: rolled you back from a chest/mud collision false positive.");
        });
    }

    private boolean isNearProblemBlocks(Location location) {
        if (location.getWorld() == null) return false;
        Block center = location.getBlock();
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 2; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    Material type = center.getRelative(dx, dy, dz).getType();
                    if (type == Material.CHEST || type == Material.TRAPPED_CHEST || type == Material.MUD) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        lastSafeLocations.remove(event.getPlayer().getUniqueId());
    }
}
