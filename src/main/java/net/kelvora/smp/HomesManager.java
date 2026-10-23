package net.kelvora.smp;

import java.io.File;
import java.io.IOException;
import java.util.*;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.model.user.User;
import org.bukkit.*;
import org.bukkit.command.*;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

/** Persistent, rank-limited player homes with a protected paginated GUI. */
final class HomesManager implements Listener, CommandExecutor, TabCompleter {
    private static final String TITLE = "KELVORA // HOMES";
    private final JavaPlugin plugin;
    private final BountyCombatManager combat;
    private final File file;
    private final NamespacedKey actionKey;
    private final Map<UUID, Map<String, SavedHome>> homes = new HashMap<>();
    private final Set<UUID> teleporting = new HashSet<>();
    private YamlConfiguration store;
    private LuckPerms luckPerms;

    HomesManager(JavaPlugin plugin, BountyCombatManager combat) {
        this.plugin = plugin;
        this.combat = combat;
        this.file = new File(plugin.getDataFolder(), "homes.yml");
        this.actionKey = new NamespacedKey(plugin, "home-action");
    }

    void register() {
        load();
        try { luckPerms = LuckPermsProvider.get(); } catch (IllegalStateException ignored) { }
        Bukkit.getPluginManager().registerEvents(this, plugin);
        bind("sethome", this);
        bind("home", this);
        bind("homes", this);
        bind("delhome", this);
        applyReducedDebugInfo();
    }

    private void bind(String name, HomesManager handler) {
        PluginCommand cmd = plugin.getCommand(name);
        if (cmd != null) { cmd.setExecutor(handler); cmd.setTabCompleter(handler); }
    }

    private void applyReducedDebugInfo() {
        for (World world : Bukkit.getWorlds()) {
            try { world.setGameRule(GameRule.REDUCED_DEBUG_INFO, true); }
            catch (IllegalArgumentException | NoSuchFieldError ignored) {
                plugin.getLogger().warning("Could not apply reducedDebugInfo to world " + world.getName());
            }
        }
        Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            for (World world : Bukkit.getWorlds()) {
                try { if (!Boolean.TRUE.equals(world.getGameRuleValue(GameRule.REDUCED_DEBUG_INFO))) world.setGameRule(GameRule.REDUCED_DEBUG_INFO, true); }
                catch (IllegalArgumentException | NoSuchFieldError ignored) { }
            }
        }, 1200L, 1200L);
    }

    private void load() {
        if (!plugin.getDataFolder().exists()) plugin.getDataFolder().mkdirs();
        store = YamlConfiguration.loadConfiguration(file);
        homes.clear();
        ConfigurationSection users = store.getConfigurationSection("players");
        if (users == null) return;
        for (String uuidText : users.getKeys(false)) {
            UUID uuid;
            try { uuid = UUID.fromString(uuidText); } catch (IllegalArgumentException ex) { continue; }
            ConfigurationSection userSection = users.getConfigurationSection(uuidText);
            if (userSection == null) continue;
            Map<String, SavedHome> playerHomes = new LinkedHashMap<>();
            for (String key : userSection.getKeys(false)) {
                ConfigurationSection h = userSection.getConfigurationSection(key);
                if (h == null) continue;
                String worldName = h.getString("world", "");
                UUID worldId;
                try { worldId = UUID.fromString(h.getString("world-uuid", "")); }
                catch (IllegalArgumentException ex) {
                    World known = Bukkit.getWorld(worldName);
                    if (known == null) continue;
                    worldId = known.getUID();
                }
                playerHomes.put(key.toLowerCase(Locale.ROOT), new SavedHome(
                    h.getString("name", key), worldId, worldName,
                    h.getDouble("x"), h.getDouble("y"), h.getDouble("z"),
                    (float)h.getDouble("yaw"), (float)h.getDouble("pitch")));
            }
            homes.put(uuid, playerHomes);
        }
    }

    private boolean save() {
        YamlConfiguration next = new YamlConfiguration();
        for (Map.Entry<UUID, Map<String, SavedHome>> user : homes.entrySet()) {
            for (Map.Entry<String, SavedHome> entry : user.getValue().entrySet()) {
                SavedHome h = entry.getValue();
                String path = "players." + user.getKey() + "." + entry.getKey() + ".";
                next.set(path + "name", h.name);
                next.set(path + "world", h.worldName);
                next.set(path + "world-uuid", h.worldId.toString());
                next.set(path + "x", h.x); next.set(path + "y", h.y); next.set(path + "z", h.z);
                next.set(path + "yaw", h.yaw); next.set(path + "pitch", h.pitch);
            }
        }
        try { next.save(file); store = next; return true; }
        catch (IOException ex) { plugin.getLogger().severe("Could not save homes.yml: " + ex.getMessage()); return false; }
    }

    private int homeLimit(Player player) {
        int limit = Math.max(0, plugin.getConfig().getInt("homes.default-limit", 3));
        ConfigurationSection limits = plugin.getConfig().getConfigurationSection("homes.rank-limits");
        if (limits != null) {
            String primary = primaryGroup(player);
            for (String group : limits.getKeys(false)) {
                if (primary.equalsIgnoreCase(group) || player.hasPermission("kelvora.homes.limit." + group.toLowerCase(Locale.ROOT))) {
                    limit = Math.max(limit, limits.getInt(group, limit));
                }
            }
        }
        if (player.hasPermission("kelvora.homes.limit.unlimited")) limit = Integer.MAX_VALUE;
        return limit;
    }

    private String primaryGroup(Player player) {
        if (luckPerms != null) {
            User user = luckPerms.getUserManager().getUser(player.getUniqueId());
            if (user != null && user.getPrimaryGroup() != null) return user.getPrimaryGroup().toLowerCase(Locale.ROOT);
        }
        return "default";
    }

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player p)) { sender.sendMessage("Use this command in-game."); return true; }
        String cmd = command.getName().toLowerCase(Locale.ROOT);
        if (cmd.equals("homes") || (cmd.equals("home") && args.length == 0)) { openHomes(p, 0); return true; }
        if (cmd.equals("sethome")) {
            if (!p.hasPermission("kelvora.homes.use")) { fail(p, "You do not have permission."); return true; }
            if (args.length != 1 || !validName(args[0])) { fail(p, "Usage: /sethome <name> (1–16 letters, numbers, _ or -)."); return true; }
            String key = args[0].toLowerCase(Locale.ROOT);
            Map<String, SavedHome> own = homes.computeIfAbsent(p.getUniqueId(), k -> new LinkedHashMap<>());
            if (!own.containsKey(key) && own.size() >= homeLimit(p)) { fail(p, "You have reached your home limit (" + own.size() + "/" + homeLimitText(p) + ")."); return true; }
            Location loc = p.getLocation();
            World w = loc.getWorld();
            if (w == null || !w.getWorldBorder().isInside(loc) || loc.getY() < w.getMinHeight() || loc.getY() >= w.getMaxHeight()) { fail(p, "You cannot set a home at this location."); return true; }
            SavedHome previous = own.put(key, new SavedHome(args[0], w.getUID(), w.getName(), loc.getX(), loc.getY(), loc.getZ(), loc.getYaw(), loc.getPitch()));
            if (!save()) { if (previous == null) own.remove(key); else own.put(key, previous); fail(p, "Home could not be saved; your previous data was preserved."); return true; }
            p.sendMessage(Component.text("Home '" + args[0] + "' saved. (" + own.size() + "/" + homeLimitText(p) + ")", NamedTextColor.GREEN));
            return true;
        }
        if (cmd.equals("delhome")) {
            if (!p.hasPermission("kelvora.homes.use")) { fail(p, "You do not have permission."); return true; }
            if (args.length != 1) { fail(p, "Usage: /delhome <name>"); return true; }
            Map<String, SavedHome> own = homes.get(p.getUniqueId());
            String key = args[0].toLowerCase(Locale.ROOT);
            if (own == null || !own.containsKey(key)) { fail(p, "That home does not exist."); return true; }
            SavedHome removed = own.remove(key);
            if (!save()) { own.put(key, removed); fail(p, "Could not save the deletion."); return true; }
            p.sendMessage(Component.text("Home '" + removed.name + "' deleted.", NamedTextColor.GREEN));
            return true;
        }
        if (cmd.equals("home")) {
            if (!p.hasPermission("kelvora.homes.use")) { fail(p, "You do not have permission."); return true; }
            if (args.length != 1) { fail(p, "Usage: /home <name>"); return true; }
            SavedHome h = homes.getOrDefault(p.getUniqueId(), Map.of()).get(args[0].toLowerCase(Locale.ROOT));
            if (h == null) { fail(p, "That home does not exist. Use /homes to view your homes."); return true; }
            teleport(p, h); return true;
        }
        return true;
    }

    private void teleport(Player p, SavedHome h) {
        if (combat != null && combat.isInCombat(p)) { fail(p, "You cannot teleport to a home during combat."); return; }
        if (!teleporting.add(p.getUniqueId())) { fail(p, "A teleport is already in progress."); return; }
        World world = Bukkit.getWorld(h.worldId);
        if (world == null) world = Bukkit.getWorld(h.worldName);
        if (world == null) { teleporting.remove(p.getUniqueId()); fail(p, "That home's world is unavailable. Your home has been kept."); return; }
        Location target = new Location(world, h.x, h.y, h.z, h.yaw, h.pitch);
        p.closeInventory();
        p.teleportAsync(target).whenComplete((success, error) -> Bukkit.getScheduler().runTask(plugin, () -> {
            teleporting.remove(p.getUniqueId());
            if (!p.isOnline()) return;
            if (error != null || !Boolean.TRUE.equals(success)) { fail(p, "Teleport failed. You have been left where you were."); return; }
            p.playSound(p.getLocation(), Sound.ENTITY_ENDERMAN_TELEPORT, 0.45f, 1.25f);
            p.sendMessage(Component.text("Teleported home '" + h.name + "'.", NamedTextColor.GREEN));
        }));
    }

    private void openHomes(Player p, int page) {
        if (!p.hasPermission("kelvora.homes.use")) { fail(p, "You do not have permission."); return; }
        Inventory inv = Bukkit.createInventory(null, 54, Component.text(TITLE, NamedTextColor.DARK_GREEN));
        ItemStack pane = item(Material.GRAY_STAINED_GLASS_PANE, " ", null);
        for (int i = 45; i < 54; i++) inv.setItem(i, pane);
        Map<String, SavedHome> own = homes.getOrDefault(p.getUniqueId(), Map.of());
        List<SavedHome> entries = new ArrayList<>(own.values());
        entries.sort(Comparator.comparing(h -> h.name.toLowerCase(Locale.ROOT)));
        int pages = Math.max(1, (entries.size() + 44) / 45);
        int safePage = Math.max(0, Math.min(page, pages - 1));
        int start = safePage * 45;
        for (int i = start; i < Math.min(start + 45, entries.size()); i++) {
            SavedHome h = entries.get(i);
            ItemStack icon = item(Material.COMPASS, "§a" + h.name,
                List.of("§7World: §f" + h.worldName, "§7Click to teleport", "§cShift-right-click to delete"));
            ItemMeta meta = icon.getItemMeta();
            meta.getPersistentDataContainer().set(actionKey, PersistentDataType.STRING, "home:" + h.name.toLowerCase(Locale.ROOT));
            icon.setItemMeta(meta);
            inv.setItem(i - start, icon);
        }
        inv.setItem(48, item(Material.ARROW, "§ePrevious page", List.of()));
        ItemStack count = item(Material.PAPER, "§aHomes: " + entries.size() + "/" + homeLimitText(p), List.of("§7Page " + (safePage + 1) + "/" + pages, "§7Saved homes persist after restarts."));
        count.getItemMeta().getPersistentDataContainer().set(actionKey, PersistentDataType.STRING, "page:" + safePage);
        inv.setItem(49, count);
        inv.setItem(50, item(Material.ARROW, "§eNext page", List.of()));
        inv.setItem(53, item(Material.BARRIER, "§cClose", List.of()));
        if (entries.isEmpty()) inv.setItem(22, item(Material.BOOK, "§7No homes saved", List.of("§fUse /sethome <name> to save one.")));
        p.openInventory(inv);
    }

    private ItemStack item(Material material, String name, List<String> lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(ChatColor.stripColor(name)));
        if (lore != null) meta.lore(lore.stream().map(s -> Component.text(ChatColor.stripColor(s))).toList());
        item.setItemMeta(meta);
        return item;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInventoryClick(InventoryClickEvent e) {
        if (!TITLE.equals(ChatColor.stripColor(e.getView().getTitle()))) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p) || e.getClickedInventory() != e.getView().getTopInventory()) return;
        if (e.getRawSlot() < 0 || e.getRawSlot() >= e.getView().getTopInventory().getSize()) return;
        ItemStack clicked = e.getCurrentItem();
        if (clicked == null || !clicked.hasItemMeta()) return;
        if (clicked.getType() == Material.BARRIER) { p.closeInventory(); return; }
        if (clicked.getType() == Material.ARROW) {
            int page = currentPage(e.getView().getTopInventory()) + (e.getRawSlot() == 48 ? -1 : 1);
            openHomes(p, page); p.playSound(p, Sound.UI_BUTTON_CLICK, 0.45f, 1.2f); return;
        }
        String action = clicked.getItemMeta().getPersistentDataContainer().get(actionKey, PersistentDataType.STRING);
        if (action == null || !action.startsWith("home:")) return;
        String key = action.substring(5);
        SavedHome h = homes.getOrDefault(p.getUniqueId(), Map.of()).get(key);
        if (h == null) { fail(p, "That home no longer exists."); openHomes(p, 0); return; }
        if (e.isShiftClick() && e.isRightClick()) {
            Map<String, SavedHome> own = homes.get(p.getUniqueId());
            own.remove(key);
            if (!save()) { own.put(key, h); fail(p, "Could not save the deletion."); return; }
            p.playSound(p, Sound.UI_BUTTON_CLICK, 0.45f, 0.8f);
            p.sendMessage(Component.text("Home '" + h.name + "' deleted.", NamedTextColor.GREEN));
            openHomes(p, 0); return;
        }
        p.playSound(p, Sound.UI_BUTTON_CLICK, 0.45f, 1.2f);
        teleport(p, h);
    }

    private int currentPage(Inventory inv) {
        ItemStack paper = inv.getItem(49);
        if (paper == null || !paper.hasItemMeta()) return 0;
        String value = paper.getItemMeta().getPersistentDataContainer().get(actionKey, PersistentDataType.STRING);
        if (value == null || !value.startsWith("page:")) return 0;
        try { return Integer.parseInt(value.substring(5)); } catch (NumberFormatException ignored) { return 0; }
    }

    @EventHandler public void onInventoryDrag(InventoryDragEvent e) {
        if (TITLE.equals(ChatColor.stripColor(e.getView().getTitle()))) e.setCancelled(true);
    }
    @EventHandler public void onQuit(PlayerQuitEvent e) { teleporting.remove(e.getPlayer().getUniqueId()); }

    @Override public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!(sender instanceof Player p) || args.length != 1) return Collections.emptyList();
        if (command.getName().equalsIgnoreCase("home") || command.getName().equalsIgnoreCase("delhome")) {
            String prefix = args[0].toLowerCase(Locale.ROOT);
            return homes.getOrDefault(p.getUniqueId(), Map.of()).values().stream().map(h -> h.name)
                .filter(n -> n.toLowerCase(Locale.ROOT).startsWith(prefix)).toList();
        }
        return Collections.emptyList();
    }

    private boolean validName(String name) { return name != null && name.matches("[A-Za-z0-9_-]{1,16}"); }
    private String homeLimitText(Player p) { return homeLimit(p) == Integer.MAX_VALUE ? "∞" : String.valueOf(homeLimit(p)); }
    private void fail(Player p, String message) { p.sendMessage(Component.text(message, NamedTextColor.RED)); }
    private record SavedHome(String name, UUID worldId, String worldName, double x, double y, double z, float yaw, float pitch) { }
}
