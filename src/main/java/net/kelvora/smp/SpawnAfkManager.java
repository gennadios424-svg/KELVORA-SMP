package net.kelvora.smp;

import java.io.File;
import java.io.IOException;
import java.util.*;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.command.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.*;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

/** Persistent spawn confirmation and configurable multi-location AFK lounge. */
final class SpawnAfkManager implements Listener {
    private static final String SPAWN_TITLE = "KELVORA // SPAWN";
    private static final String SPAWN_CONFIRM_TITLE = "Teleport to spawn?";
    private static final long SPAWN_DELAY_TICKS = 100L;
    private static final int AFK_REWARD_SECONDS = 300;
    private static final double AFK_REWARD = 3.0;
    private static final Set<UUID> MANUAL_AFK = new HashSet<>();

    private final JavaPlugin plugin;
    private final KelvorasManager kelvoras;
    private final File file;
    private final YamlConfiguration data;
    private final Map<UUID, Integer> afkSeconds = new HashMap<>();
    private final Map<UUID, Location> spawnCountdownOrigins = new HashMap<>();
    private final Map<UUID, Integer> spawnTasks = new HashMap<>();
    private final Random random = new Random();

    SpawnAfkManager(JavaPlugin plugin, KelvorasManager kelvoras) {
        this.plugin = plugin;
        this.kelvoras = kelvoras;
        this.file = new File(plugin.getDataFolder(), "spawn-afk.yml");
        this.data = YamlConfiguration.loadConfiguration(file);
    }

    void register() {
        Bukkit.getPluginManager().registerEvents(this, plugin);
        bind("setspawn", (sender, command, label, args) -> setSpawn(sender, args));
        bind("spawn", (sender, command, label, args) -> spawn(sender, args));
        bind("setafk", (sender, command, label, args) -> setAfkLocation(sender, args));
        bind("afk", (sender, command, label, args) -> afk(sender, args));
        Bukkit.getScheduler().runTaskTimer(plugin, this::payAfkPlayers, 100L, 100L);
    }

    private void bind(String name, CommandExecutor executor) {
        PluginCommand command = plugin.getCommand(name);
        if (command != null) command.setExecutor(executor);
    }

    private boolean setSpawn(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) { sender.sendMessage("Use this command in-game."); return true; }
        if (!player.isOp() && !player.hasPermission("kelvora.spawn.admin")) { player.sendMessage(Component.text("You do not have permission.", NamedTextColor.RED)); return true; }
        if (args.length != 0) { player.sendMessage(Component.text("Usage: /setspawn", NamedTextColor.RED)); return true; }
        data.set("spawn", player.getLocation().clone());
        save();
        player.sendMessage(Component.text("Kelvora spawn set at your current location.", NamedTextColor.GREEN));
        player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.8f, 1.3f);
        return true;
    }

    private boolean spawn(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) { sender.sendMessage("Use this command in-game."); return true; }
        if (args.length != 0) { player.sendMessage(Component.text("Usage: /spawn", NamedTextColor.RED)); return true; }
        Location destination = data.getLocation("spawn");
        if (!valid(destination)) { player.sendMessage(Component.text("Spawn has not been set yet. Ask staff to use /setspawn.", NamedTextColor.RED)); return true; }
        Inventory gui = Bukkit.createInventory(null, 27, Component.text(SPAWN_TITLE, NamedTextColor.DARK_GREEN));
        ItemStack filler = item(Material.GRAY_STAINED_GLASS_PANE, " ", NamedTextColor.DARK_GRAY);
        for (int i = 0; i < 27; i++) gui.setItem(i, filler);
        gui.setItem(11, item(Material.LIME_WOOL, "YES — teleport to spawn", NamedTextColor.GREEN));
        gui.setItem(15, item(Material.RED_WOOL, "NO — stay here", NamedTextColor.RED));
        gui.setItem(13, item(Material.COMPASS, "Teleport to spawn?", NamedTextColor.WHITE));
        player.openInventory(gui);
        return true;
    }

    private boolean setAfkLocation(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) { sender.sendMessage("Use this command in-game."); return true; }
        if (!player.isOp() && !player.hasPermission("kelvora.afk.admin")) { player.sendMessage(Component.text("You do not have permission.", NamedTextColor.RED)); return true; }
        List<Location> locations = afkLocations();
        if (args.length == 0 || args[0].equalsIgnoreCase("add")) {
            locations.add(player.getLocation().clone());
            writeAfkLocations(locations);
            player.sendMessage(Component.text("AFK location #" + locations.size() + " added. /afk sends players to a random saved location.", NamedTextColor.GREEN));
            return true;
        }
        if (args[0].equalsIgnoreCase("list") && args.length == 1) {
            player.sendMessage(Component.text("Saved AFK locations: " + locations.size(), NamedTextColor.AQUA));
            for (int i = 0; i < locations.size(); i++) {
                Location loc = locations.get(i);
                player.sendMessage(Component.text((i + 1) + ". " + loc.getWorld().getName() + " " + loc.getBlockX() + ", " + loc.getBlockY() + ", " + loc.getBlockZ(), NamedTextColor.GRAY));
            }
            return true;
        }
        if (args[0].equalsIgnoreCase("clear") && args.length == 1) {
            writeAfkLocations(new ArrayList<>());
            player.sendMessage(Component.text("All AFK locations cleared.", NamedTextColor.YELLOW));
            return true;
        }
        if (args[0].equalsIgnoreCase("remove") && args.length == 2) {
            try {
                int index = Integer.parseInt(args[1]) - 1;
                if (index < 0 || index >= locations.size()) throw new NumberFormatException();
                locations.remove(index);
                writeAfkLocations(locations);
                player.sendMessage(Component.text("Removed AFK location #" + (index + 1) + ".", NamedTextColor.GREEN));
            } catch (NumberFormatException ex) {
                player.sendMessage(Component.text("Usage: /setafk remove <number>", NamedTextColor.RED));
            }
            return true;
        }
        player.sendMessage(Component.text("Usage: /setafk [add|list|remove <number>|clear]", NamedTextColor.RED));
        return true;
    }

    private boolean afk(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) { sender.sendMessage("Use this command in-game."); return true; }
        if (args.length != 0) { player.sendMessage(Component.text("Usage: /afk", NamedTextColor.RED)); return true; }
        List<Location> locations = afkLocations();
        locations.removeIf(loc -> !valid(loc));
        if (locations.isEmpty()) { player.sendMessage(Component.text("No AFK locations are configured. Ask staff to use /setafk.", NamedTextColor.RED)); return true; }
        Location destination = locations.get(random.nextInt(locations.size())).clone();
        cancelSpawnCountdown(player, false);
        if (!player.teleport(destination)) { player.sendMessage(Component.text("Teleport failed. Try again or contact staff.", NamedTextColor.RED)); return true; }
        setAfk(player, true);
        player.sendMessage(Component.text("You are now AFK. Earn ✦3 Kelvoras every 5 minutes. Moving will end AFK mode.", NamedTextColor.GREEN));
        player.playSound(player.getLocation(), Sound.ENTITY_ENDERMAN_TELEPORT, 0.8f, 1.1f);
        return true;
    }

    private void payAfkPlayers() {
        for (UUID id : new HashSet<>(MANUAL_AFK)) {
            Player player = Bukkit.getPlayer(id);
            if (player == null || !player.isOnline()) { setAfk(id, false); continue; }
            int elapsed = afkSeconds.getOrDefault(id, 0) + 5;
            if (elapsed >= AFK_REWARD_SECONDS) {
                elapsed = 0;
                if (kelvoras.deposit(player, AFK_REWARD)) {
                    player.sendActionBar(Component.text("✦ +3 Kelvoras • AFK reward", NamedTextColor.AQUA));
                    player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.5f, 1.5f);
                } else {
                    plugin.getLogger().warning("Could not save AFK Kelvoras reward for " + player.getName());
                }
            }
            afkSeconds.put(id, elapsed);
        }
    }

    private void setAfk(Player player, boolean value) {
        setAfk(player.getUniqueId(), value);
        if (value) {
            player.customName(Component.text("[AFK] ", NamedTextColor.GRAY).append(Component.text(player.getName(), NamedTextColor.WHITE)));
            player.setCustomNameVisible(true);
        } else {
            player.customName(null);
            player.setCustomNameVisible(false);
        }
        player.setPlayerListName(value ? "§8[§7§lAFK§8] §f" + player.getName() : player.getName());
        if (plugin instanceof KelvoraSMP main) main.refreshPresentation(player);
    }

    private void setAfk(UUID id, boolean value) {
        if (value) {
            MANUAL_AFK.add(id);
            afkSeconds.putIfAbsent(id, 0);
        } else {
            MANUAL_AFK.remove(id);
            afkSeconds.remove(id);
        }
    }

    static boolean isAfk(Player player) { return player != null && MANUAL_AFK.contains(player.getUniqueId()); }

    @EventHandler public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (!event.getView().title().equals(Component.text(SPAWN_TITLE, NamedTextColor.DARK_GREEN))) return;
        event.setCancelled(true);
        if (event.getClickedInventory() == null || event.getClickedInventory() != event.getView().getTopInventory()) return;
        ItemStack clicked = event.getCurrentItem();
        if (clicked == null) return;
        if (clicked.getType() == Material.RED_WOOL) {
            player.closeInventory();
            player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.7f, 0.8f);
        } else if (clicked.getType() == Material.LIME_WOOL) {
            player.closeInventory();
            beginSpawnCountdown(player);
        }
    }

    @EventHandler public void onInventoryDrag(InventoryDragEvent event) {
        if (event.getView().title().equals(Component.text(SPAWN_TITLE, NamedTextColor.DARK_GREEN))) event.setCancelled(true);
    }

    private void beginSpawnCountdown(Player player) {
        Location origin = player.getLocation().clone();
        spawnCountdownOrigins.put(player.getUniqueId(), origin);
        player.sendMessage(Component.text("Teleporting to spawn in 5 seconds. Don't move.", NamedTextColor.GREEN));
        player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 0.7f, 1.0f);
        int task = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            UUID id = player.getUniqueId();
            spawnTasks.remove(id);
            Location start = spawnCountdownOrigins.remove(id);
            Location destination = data.getLocation("spawn");
            if (!player.isOnline() || start == null || !valid(destination)) return;
            if (!sameBlock(start, player.getLocation())) {
                player.sendMessage(Component.text("Teleport cancelled because you moved.", NamedTextColor.RED));
                return;
            }
            player.teleport(destination);
            player.sendMessage(Component.text("Teleported to spawn.", NamedTextColor.GREEN));
            player.playSound(destination, Sound.ENTITY_ENDERMAN_TELEPORT, 0.8f, 1.0f);
        }, SPAWN_DELAY_TICKS).getTaskId();
        spawnTasks.put(player.getUniqueId(), task);
    }

    @EventHandler public void onMove(PlayerMoveEvent event) {
        if (event.getTo() == null || sameBlock(event.getFrom(), event.getTo())) return;
        Player player = event.getPlayer();
        if (spawnTasks.containsKey(player.getUniqueId())) {
            cancelSpawnCountdown(player, true);
        }
        if (MANUAL_AFK.contains(player.getUniqueId())) {
            setAfk(player, false);
            player.sendMessage(Component.text("AFK mode ended because you moved.", NamedTextColor.YELLOW));
        }
    }

    @EventHandler public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        cancelSpawnCountdown(event.getPlayer(), false);
        setAfk(id, false);
    }

    @EventHandler public void onDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        if (MANUAL_AFK.contains(player.getUniqueId())) setAfk(player, false);
    }

    private void cancelSpawnCountdown(Player player, boolean message) {
        UUID id = player.getUniqueId();
        Integer task = spawnTasks.remove(id);
        if (task != null) Bukkit.getScheduler().cancelTask(task);
        spawnCountdownOrigins.remove(id);
        if (message && task != null) player.sendMessage(Component.text("Spawn teleport cancelled because you moved.", NamedTextColor.RED));
    }

    private List<Location> afkLocations() {
        List<Location> locations = new ArrayList<>();
        for (Object value : data.getList("afk-locations", Collections.emptyList())) {
            if (value instanceof Location loc && valid(loc)) locations.add(loc.clone());
        }
        return locations;
    }

    private void writeAfkLocations(List<Location> locations) {
        data.set("afk-locations", locations);
        save();
    }

    private void save() {
        try { data.save(file); }
        catch (IOException ex) { plugin.getLogger().severe("Could not save spawn-afk.yml: " + ex.getMessage()); }
    }

    private static boolean valid(Location location) {
        return location != null && location.getWorld() != null && Double.isFinite(location.getX()) && Double.isFinite(location.getY()) && Double.isFinite(location.getZ());
    }

    private static boolean sameBlock(Location a, Location b) {
        return a.getWorld() != null && b.getWorld() != null && a.getWorld().equals(b.getWorld())
            && a.getBlockX() == b.getBlockX() && a.getBlockY() == b.getBlockY() && a.getBlockZ() == b.getBlockZ();
    }

    private ItemStack item(Material material, String name, NamedTextColor color) {
        ItemStack stack = new ItemStack(material);
        ItemMeta meta = stack.getItemMeta();
        meta.displayName(Component.text(name, color));
        stack.setItemMeta(meta);
        return stack;
    }
}
