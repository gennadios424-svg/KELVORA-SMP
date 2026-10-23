package net.kelvora.smp;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Vector;

/**
 * Bounded, asynchronous-chunk-loading random teleport with conservative landing checks.
 * World-border centers are deliberately preserved; only the diameter is normalized.
 */
final class RtpManager implements Listener, CommandExecutor, TabCompleter {
    private static final double BORDER_SIZE = 100000.0;
    private static final int MAX_ATTEMPTS = 36;
    private static final int ATTEMPTS_PER_REQUEST = 1;
    private static final long REQUEST_TIMEOUT_TICKS = 20L * 20L;
    private final JavaPlugin plugin;
    private final Set<UUID> searching = new HashSet<>();
    private final Map<String, World> dimensions = new HashMap<>();
    private final Random random = new Random();

    RtpManager(JavaPlugin plugin) { this.plugin = plugin; }

    void register() {
        resolveWorldsAndBorders();
        Bukkit.getPluginManager().registerEvents(this, plugin);
        if (plugin.getCommand("rtp") != null) {
            plugin.getCommand("rtp").setExecutor(this);
            plugin.getCommand("rtp").setTabCompleter(this);
        }
        Bukkit.getScheduler().runTaskTimer(plugin, this::resolveWorldsAndBorders, 100L, 1200L);
    }

    private void resolveWorldsAndBorders() {
        for (String dimension : List.of("overworld", "nether", "end")) {
            String configured = plugin.getConfig().getString("rtp.worlds." + dimension, "").trim();
            World world = configured.isEmpty() ? null : Bukkit.getWorld(configured);
            if (world == null && !configured.isEmpty()) {
                plugin.getLogger().warning("RTP " + dimension + " world '" + configured + "' is not loaded.");
            }
            if (world == null && configured.isEmpty()) {
                World.Environment wanted = switch (dimension) {
                    case "overworld" -> World.Environment.NORMAL;
                    case "nether" -> World.Environment.NETHER;
                    default -> World.Environment.THE_END;
                };
                for (World candidate : Bukkit.getWorlds()) {
                    if (candidate.getEnvironment() == wanted) { world = candidate; break; }
                }
            }
            if (world != null) {
                dimensions.put(dimension, world);
                WorldBorder border = world.getWorldBorder();
                if (Math.abs(border.getSize() - BORDER_SIZE) > 0.01) {
                    border.setSize(BORDER_SIZE);
                    plugin.getLogger().info("RTP border set to 100000 blocks for " + world.getName()
                        + "; preserved center " + border.getCenter().getBlockX() + ", " + border.getCenter().getBlockZ() + ".");
                }
            } else {
                dimensions.remove(dimension);
            }
        }
    }

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) { sender.sendMessage("Use this command in-game."); return true; }
        if (!player.hasPermission("kelvora.rtp")) { player.sendMessage(ChatColor.RED + "You do not have permission."); return true; }
        if (args.length > 1) { player.sendMessage(ChatColor.RED + "Usage: /rtp [overworld|nether|end]"); return true; }
        String dimension = args.length == 0 ? "overworld" : args[0].toLowerCase(Locale.ROOT);
        if (!List.of("overworld", "nether", "end").contains(dimension)) {
            player.sendMessage(ChatColor.RED + "Usage: /rtp [overworld|nether|end]"); return true;
        }
        World target = dimensions.get(dimension);
        if (target == null || !target.getWorldBorder().isInside(new Location(target, target.getWorldBorder().getCenter().getX(), target.getMinHeight() + 1, target.getWorldBorder().getCenter().getZ()))) {
            resolveWorldsAndBorders();
            target = dimensions.get(dimension);
        }
        if (target == null) {
            player.sendMessage(ChatColor.RED + "That dimension is unavailable right now."); return true;
        }
        if (!searching.add(player.getUniqueId())) {
            player.sendMessage(ChatColor.YELLOW + "Your random teleport is already searching."); return true;
        }
        World destinationWorld = target;
        Location origin = player.getLocation().clone();
        player.sendMessage(ChatColor.GRAY + "Searching for a safe location...");
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (searching.contains(player.getUniqueId()) && (!player.isOnline() || player.isDead())) searching.remove(player.getUniqueId());
        }, REQUEST_TIMEOUT_TICKS);
        tryCandidate(player, destinationWorld, origin, dimension, 0);
        return true;
    }

    private void tryCandidate(Player player, World world, Location origin, String dimension, int attempt) {
        if (!searching.contains(player.getUniqueId())) return;
        if (!player.isOnline() || player.isDead() || !player.getWorld().equals(origin.getWorld())) {
            searching.remove(player.getUniqueId()); return;
        }
        if (attempt >= MAX_ATTEMPTS) {
            searching.remove(player.getUniqueId());
            player.sendMessage(ChatColor.RED + "No safe location found. Please try again.");
            return;
        }
        WorldBorder border = world.getWorldBorder();
        Location center = border.getCenter();
        double half = Math.min(BORDER_SIZE / 2.0, border.getSize() / 2.0) - 32.0;
        if (half <= 0) {
            searching.remove(player.getUniqueId());
            player.sendMessage(ChatColor.RED + "Random teleport is unavailable in that dimension."); return;
        }
        int x = (int) Math.floor(center.getX() + (random.nextDouble() * 2.0 - 1.0) * half);
        int z = (int) Math.floor(center.getZ() + (random.nextDouble() * 2.0 - 1.0) * half);
        CompletableFuture<Chunk> future;
        try {
            future = world.getChunkAtAsync(x >> 4, z >> 4, true);
        } catch (Throwable ex) {
            scheduleRetry(player, world, origin, dimension, attempt + ATTEMPTS_PER_REQUEST); return;
        }
        future.whenComplete((chunk, error) -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (!searching.contains(player.getUniqueId())) return;
            if (error != null || chunk == null || !chunk.isLoaded()) {
                scheduleRetry(player, world, origin, dimension, attempt + ATTEMPTS_PER_REQUEST); return;
            }
            Location safe = findSafeLocation(world, x, z);
            if (safe == null || !insideBorderWithMargin(border, safe)) {
                scheduleRetry(player, world, origin, dimension, attempt + ATTEMPTS_PER_REQUEST); return;
            }
            searching.remove(player.getUniqueId());
            if (!player.isOnline() || player.isDead() || !player.getWorld().equals(origin.getWorld())) return;
            player.teleportAsync(safe).whenComplete((success, teleportError) -> Bukkit.getScheduler().runTask(plugin, () -> {
                if (!player.isOnline()) return;
                if (teleportError != null || !Boolean.TRUE.equals(success)) {
                    player.sendMessage(ChatColor.RED + "Teleport failed. You have been left where you were."); return;
                }
                player.playSound(player.getLocation(), Sound.ENTITY_ENDERMAN_TELEPORT, 0.45f, 1.35f);
                player.sendMessage(ChatColor.GREEN + "Teleported safely to the " + dimension + ".");
            }));
        }));
    }

    private void scheduleRetry(Player player, World world, Location origin, String dimension, int attempt) {
        Bukkit.getScheduler().runTaskLater(plugin, () -> tryCandidate(player, world, origin, dimension, attempt), 1L);
    }

    private Location findSafeLocation(World world, int x, int z) {
        int highest = world.getHighestBlockYAt(x, z);
        int min = Math.max(world.getMinHeight() + 2, highest - 10);
        for (int y = Math.min(highest + 1, world.getMaxHeight() - 2); y >= min; y--) {
            Location feet = new Location(world, x + 0.5, y, z + 0.5);
            if (safeLanding(feet)) return feet;
        }
        return null;
    }

    private boolean safeLanding(Location feet) {
        World world = feet.getWorld();
        int x = feet.getBlockX(), y = feet.getBlockY(), z = feet.getBlockZ();
        if (y <= world.getMinHeight() + 1 || y + 1 >= world.getMaxHeight()) return false;
        WorldBorder border = world.getWorldBorder();
        if (!insideBorderWithMargin(border, feet)) return false;
        // Require a 3x3 support platform and two blocks of clear, non-liquid body space.
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
            Block floor = world.getBlockAt(x + dx, y - 1, z + dz);
            Block body = world.getBlockAt(x + dx, y, z + dz);
            Block head = world.getBlockAt(x + dx, y + 1, z + dz);
            if (!safeFloor(floor) || !safeBody(body) || !safeBody(head)) return false;
        }
        // Reject hazards in the immediate landing perimeter, including fire and damaging blocks.
        for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) {
            for (int dy = -1; dy <= 2; dy++) {
                Material material = world.getBlockAt(x + dx, y + dy, z + dz).getType();
                if (isHazard(material)) return false;
            }
        }
        return true;
    }

    private boolean safeFloor(Block block) {
        Material m = block.getType();
        if (!m.isSolid() || m == Material.BEDROCK || m == Material.MAGMA_BLOCK
                || m == Material.CACTUS || m == Material.CAMPFIRE || m == Material.SOUL_CAMPFIRE
                || m == Material.FIRE || m == Material.SOUL_FIRE || m == Material.POWDER_SNOW
                || m == Material.HONEY_BLOCK || m == Material.SWEET_BERRY_BUSH) return false;
        return !m.name().contains("LEAVES") && !m.name().contains("FENCE") && !m.name().contains("WALL")
                && !m.name().contains("CARPET") && !m.name().contains("SLAB") && !m.name().contains("STAIRS")
                && !m.name().contains("DOOR") && !m.name().contains("TRAPDOOR") && !m.name().contains("BED");
    }

    private boolean safeBody(Block block) {
        Material m = block.getType();
        return block.isPassable() && !m.isLiquid() && !isHazard(m);
    }

    private boolean isHazard(Material m) {
        String n = m.name();
        return m.isLiquid() || m == Material.FIRE || m == Material.SOUL_FIRE || m == Material.CACTUS
                || m == Material.MAGMA_BLOCK || m == Material.CAMPFIRE || m == Material.SOUL_CAMPFIRE
                || m == Material.POWDER_SNOW || m == Material.SWEET_BERRY_BUSH
                || n.contains("LAVA") || n.contains("WATER") || n.contains("FIRE") || n.contains("KELP")
                || n.contains("SEAGRASS") || n.contains("BUBBLE_COLUMN") || n.contains("WITHER_ROSE");
    }

    private boolean insideBorderWithMargin(WorldBorder border, Location loc) {
        Location c = border.getCenter();
        double margin = 16.0;
        return Math.abs(loc.getX() - c.getX()) <= border.getSize() / 2.0 - margin
            && Math.abs(loc.getZ() - c.getZ()) <= border.getSize() / 2.0 - margin;
    }

    @Override public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length != 1) return Collections.emptyList();
        String prefix = args[0].toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (String d : List.of("overworld", "nether", "end")) if (d.startsWith(prefix)) out.add(d);
        return out;
    }

    @EventHandler public void onQuit(PlayerQuitEvent event) { searching.remove(event.getPlayer().getUniqueId()); }
    @EventHandler public void onWorldChange(PlayerChangedWorldEvent event) { searching.remove(event.getPlayer().getUniqueId()); }
    @EventHandler public void onRespawn(PlayerRespawnEvent event) { searching.remove(event.getPlayer().getUniqueId()); }
}
