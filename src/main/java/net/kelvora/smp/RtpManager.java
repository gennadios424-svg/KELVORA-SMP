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
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.NamespacedKey;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.event.player.PlayerMoveEvent;

/**
 * Bounded, asynchronous-chunk-loading random teleport with conservative landing checks.
 * World-border centers are deliberately preserved; only the diameter is normalized.
 */
final class RtpManager implements Listener, CommandExecutor, TabCompleter {
    private static final double BORDER_SIZE = 100000.0;
    private static final int MAX_ATTEMPTS = 8;
    private static final int ATTEMPTS_PER_REQUEST = 1;
    private static final String GUI_TITLE = "KELVORA // RANDOM TELEPORT";
    private static final long REQUEST_TIMEOUT_TICKS = 20L * 20L;
    private final JavaPlugin plugin;
    private final BountyCombatManager combat;
    private final NamespacedKey actionKey;
    private final Set<UUID> searching = new HashSet<>();
    private final Map<UUID, BukkitTask> countdowns = new HashMap<>();
    private final Map<UUID, Location> countdownOrigins = new HashMap<>();
    private final Map<String, World> dimensions = new HashMap<>();
    private final Random random = new Random();

    RtpManager(JavaPlugin plugin, BountyCombatManager combat) { this.plugin = plugin; this.combat = combat; this.actionKey = new NamespacedKey(plugin, "rtp-dimension"); }

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
        openMenu(player);
        return true;
    }

    private void openMenu(Player player) {
        Inventory inv = Bukkit.createInventory(null, 27, Component.text(GUI_TITLE, NamedTextColor.DARK_GREEN));
        ItemStack frame = guiItem(Material.BLACK_STAINED_GLASS_PANE, " ", List.of());
        for (int i = 0; i < inv.getSize(); i++) inv.setItem(i, frame);
        addDimension(inv, 11, "overworld", Material.GRASS_BLOCK, "Overworld", "Random surface location");
        addDimension(inv, 13, "nether", Material.NETHERRACK, "Nether", "Find a Nether surface");
        addDimension(inv, 15, "end", Material.END_STONE, "End", "Find solid End terrain");
        inv.setItem(22, guiItem(Material.BARRIER, "§cClose", List.of()));
        player.openInventory(inv);
    }

    private void addDimension(Inventory inv, int slot, String dimension, Material material, String name, String description) {
        ItemStack item = guiItem(material, "§a" + name, List.of("§7" + description, "§fClick to teleport"));
        ItemMeta meta = item.getItemMeta();
        meta.getPersistentDataContainer().set(actionKey, PersistentDataType.STRING, dimension);
        item.setItemMeta(meta);
        inv.setItem(slot, item);
    }

    private ItemStack guiItem(Material material, String name, List<String> lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(ChatColor.stripColor(name)));
        if (lore != null) meta.lore(lore.stream().map(s -> Component.text(ChatColor.stripColor(s))).toList());
        item.setItemMeta(meta);
        return item;
    }

    @EventHandler(priority = org.bukkit.event.EventPriority.HIGHEST)
    public void onGuiClick(InventoryClickEvent event) {
        if (!GUI_TITLE.equals(ChatColor.stripColor(event.getView().getTitle()))) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player) || event.getClickedInventory() != event.getView().getTopInventory()) return;
        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || !clicked.hasItemMeta()) return;
        if (clicked.getType() == Material.BARRIER) { player.closeInventory(); return; }
        String dimension = clicked.getItemMeta().getPersistentDataContainer().get(actionKey, PersistentDataType.STRING);
        if (dimension == null || !List.of("overworld", "nether", "end").contains(dimension)) return;
        player.playSound(player, Sound.UI_BUTTON_CLICK, 0.45f, 1.2f);
        player.closeInventory();
        beginRtp(player, dimension);
    }

    @EventHandler public void onGuiDrag(InventoryDragEvent event) {
        if (GUI_TITLE.equals(ChatColor.stripColor(event.getView().getTitle()))) event.setCancelled(true);
    }

    private void beginRtp(Player player, String dimension) {
        if (combat != null && combat.isInCombat(player)) {
            player.sendActionBar(Component.text("You cannot teleport while in combat!", NamedTextColor.RED));
            return;
        }
        World target = dimensions.get(dimension);
        if (target == null) {
            resolveWorldsAndBorders();
            target = dimensions.get(dimension);
        }
        if (target == null) { player.sendMessage(ChatColor.RED + "That dimension is unavailable right now."); return; }
        if (!searching.add(player.getUniqueId())) {
            player.sendMessage(ChatColor.YELLOW + "Your random teleport is already searching."); return;
        }
        Location origin = player.getLocation().clone();
        countdownOrigins.put(player.getUniqueId(), origin.clone());
        final int[] seconds = {5};
        player.sendActionBar(Component.text("RTP in 5s — Don't move!", NamedTextColor.YELLOW));
        BukkitTask countdown = Bukkit.getScheduler().runTaskTimer(plugin, new Runnable() {
            @Override public void run() {
                UUID id = player.getUniqueId();
                if (!player.isOnline() || player.isDead() || !searching.contains(id)) {
                    countdownOrigins.remove(id); cancel(); countdowns.remove(id); return;
                }
                if (combat != null && combat.isInCombat(player)) {
                    searching.remove(id); countdownOrigins.remove(id); countdowns.remove(id);
                    player.sendActionBar(Component.text("Teleport cancelled — you are in combat!", NamedTextColor.RED));
                    cancel(); return;
                }
                Location now = player.getLocation();
                if (!now.getWorld().equals(origin.getWorld()) || now.distanceSquared(origin) > 0.0001) {
                    searching.remove(id); countdownOrigins.remove(id); countdowns.remove(id);
                    player.sendActionBar(Component.text("Teleport cancelled — don't move while teleporting!", NamedTextColor.RED));
                    cancel(); return;
                }
                if (seconds[0] <= 0) {
                    countdownOrigins.remove(id); countdowns.remove(id); cancel();
                    player.sendActionBar(Component.text("Finding a location...", NamedTextColor.AQUA));
                    Bukkit.getScheduler().runTaskLater(plugin, () -> {
                        if (searching.remove(id) && player.isOnline()) player.sendActionBar(Component.text("Random teleport timed out. Try again.", NamedTextColor.RED));
                    }, REQUEST_TIMEOUT_TICKS);
                    tryCandidate(player, target, origin, dimension, 0);
                    return;
                }
                player.sendActionBar(Component.text("RTP in " + seconds[0] + "s — Don't move!", NamedTextColor.YELLOW));
                seconds[0]--;
            }
            private void cancel() { BukkitTask task = countdowns.get(player.getUniqueId()); if (task != null) task.cancel(); }
        }, 0L, 20L);
        countdowns.put(player.getUniqueId(), countdown);
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
            if (!player.isOnline() || player.isDead() || !player.getWorld().equals(origin.getWorld())) {
                searching.remove(player.getUniqueId());
                return;
            }
            player.teleportAsync(safe).whenComplete((success, teleportError) -> Bukkit.getScheduler().runTask(plugin, () -> {
                searching.remove(player.getUniqueId());
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
        // Use this dimension's own heightmap and never search down into caves.
        int floorY = world.getHighestBlockYAt(x, z, HeightMap.MOTION_BLOCKING_NO_LEAVES);
        int feetY = floorY + 1;
        if (feetY <= world.getMinHeight() + 1 || feetY + 1 >= world.getMaxHeight()) return null;
        Location feet = new Location(world, x + 0.5, feetY, z + 0.5);
        if (!insideBorderWithMargin(world.getWorldBorder(), feet)) return null;
        if (!safeFloor(world.getBlockAt(x, floorY, z))) return null;
        if (!safeBody(world.getBlockAt(x, feetY, z)) || !safeBody(world.getBlockAt(x, feetY + 1, z))) return null;
        int support = 0;
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
            if (safeFloor(world.getBlockAt(x + dx, floorY, z + dz))) support++;
        }
        return support >= 5 ? feet : null;
    }

    private boolean safeFloor(Block block) {
        Material m = block.getType();
        return m.isSolid() && m != Material.BEDROCK && m != Material.MAGMA_BLOCK
            && m != Material.CACTUS && m != Material.CAMPFIRE && m != Material.SOUL_CAMPFIRE
            && m != Material.POWDER_SNOW && m != Material.SWEET_BERRY_BUSH
            && !m.name().contains("FENCE") && !m.name().contains("WALL")
            && !m.name().contains("CARPET") && !m.name().contains("SLAB")
            && !m.name().contains("STAIRS") && !m.name().contains("DOOR")
            && !m.name().contains("TRAPDOOR") && !m.name().contains("BED");
    }

    private boolean safeBody(Block block) {
        Material m = block.getType();
        return block.isPassable() && !isLiquid(m) && !isHazard(m);
    }

    private boolean isLiquid(Material m) {
        return m == Material.WATER || m == Material.LAVA || m == Material.BUBBLE_COLUMN
            || m.name().contains("WATER") || m.name().contains("LAVA");
    }

    private boolean isHazard(Material m) {
        String n = m.name();
        return isLiquid(m) || m == Material.FIRE || m == Material.SOUL_FIRE
            || m == Material.CACTUS || m == Material.MAGMA_BLOCK
            || m == Material.CAMPFIRE || m == Material.SOUL_CAMPFIRE
            || m == Material.POWDER_SNOW || m == Material.SWEET_BERRY_BUSH
            || n.contains("FIRE") || n.contains("KELP") || n.contains("SEAGRASS")
            || n.contains("WITHER_ROSE");
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

    @EventHandler(ignoreCancelled = true)
    public void onCountdownMove(PlayerMoveEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        if (!countdownOrigins.containsKey(id) || event.getTo() == null) return;
        Location from = event.getFrom(), to = event.getTo();
        if (!from.getWorld().equals(to.getWorld()) || from.getX() != to.getX() || from.getY() != to.getY() || from.getZ() != to.getZ()) {
            searching.remove(id); countdownOrigins.remove(id);
            BukkitTask task = countdowns.remove(id); if (task != null) task.cancel();
            event.getPlayer().sendActionBar(Component.text("Teleport cancelled — don't move while teleporting!", NamedTextColor.RED));
        }
    }
    @EventHandler public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId(); searching.remove(id); countdownOrigins.remove(id);
        BukkitTask task = countdowns.remove(id); if (task != null) task.cancel();
    }
    @EventHandler public void onRespawn(PlayerRespawnEvent event) {
        UUID id = event.getPlayer().getUniqueId(); searching.remove(id); countdownOrigins.remove(id);
        BukkitTask task = countdowns.remove(id); if (task != null) task.cancel();
    }
}
