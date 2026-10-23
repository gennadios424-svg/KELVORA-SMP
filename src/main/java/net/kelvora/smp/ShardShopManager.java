package net.kelvora.smp;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.model.user.User;
import net.luckperms.api.node.Node;
import net.luckperms.api.node.types.InheritanceNode;
import java.time.Duration;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.OfflinePlayer;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

final class ShardShopManager implements Listener {
    private static final String TITLE = "Kelvora | ShardShop";
    private static final String DISCORD = "https://discord.gg/gUt2uGcwe";
    private final KelvoraSMP plugin;
    private final KelvorasManager currency;
    private final NamespacedKey actionKey;
    private final File dataFile;
    private YamlConfiguration data;
    private final Set<UUID> processing = ConcurrentHashMap.newKeySet();
    private final Map<UUID, String> pendingRankPurchases = new ConcurrentHashMap<>();
    private final Map<UUID, String> openCategory = new HashMap<>();

    ShardShopManager(KelvoraSMP plugin, KelvorasManager currency) {
        this.plugin = plugin;
        this.currency = currency;
        this.actionKey = new NamespacedKey(plugin, "shardshop-action");
        this.dataFile = new File(plugin.getDataFolder(), "shardshop-data.yml");
    }

    void register() {
        data = YamlConfiguration.loadConfiguration(dataFile);
        expireAll();
        Bukkit.getPluginManager().registerEvents(this, plugin);
        if (plugin.getCommand("shardshop") != null) plugin.getCommand("shardshop").setExecutor(this::execute);
        if (plugin.getCommand("shards") != null) plugin.getCommand("shards").setExecutor(this::execute);
        if (plugin.getCommand("shardshopreload") != null) plugin.getCommand("shardshopreload").setExecutor(this::execute);
        Bukkit.getScheduler().runTaskTimer(plugin, this::expireAll, 20L, 1200L);
    }

    private boolean execute(CommandSender sender, Command command, String label, String[] args) {
        if (label.equalsIgnoreCase("shardshopreload")) {
            if (!sender.hasPermission("kelvora.shardshop.admin")) { sender.sendMessage("You do not have permission."); return true; }
            plugin.reloadConfig();
            sender.sendMessage("Kelvora cosmetic configuration reloaded. Ownership and expiration data were preserved.");
            return true;
        }
        if (!(sender instanceof Player player)) { sender.sendMessage("Use this command in-game."); return true; }
        if (label.equalsIgnoreCase("shards")) {
            player.sendMessage(Component.text("Your Kelvoras: ✦" + KelvorasManager.format(currency.balance(player)), NamedTextColor.AQUA));
            return true;
        }
        if (!player.hasPermission("kelvora.shardshop.use")) { player.sendMessage(Component.text("You do not have permission.", NamedTextColor.RED)); return true; }
        openCategories(player);
        return true;
    }

    private void openCategories(Player player) {
        openCategory.put(player.getUniqueId(), "categories");
        Inventory inv = Bukkit.createInventory(null, 27, Component.text(TITLE, NamedTextColor.DARK_GREEN));
        add(inv, 10, Material.NAME_TAG, "&aTags", List.of("Decorative labels beside your name", "Coming Soon"), "category:tags");
        add(inv, 11, Material.CLOCK, "&eTemporary Titles", List.of("Cosmetic titles", "Existing ShardShop products"), "category:titles");
        add(inv, 12, Material.EMERALD, "&aTemporary Ranks", List.of("Verdant, Emerald, Sovereign", "1, 3 or 7 days using Kelvoras"), "category:ranks");
        add(inv, 13, Material.GLOW_INK_SAC, "&bGlows", List.of("Cosmetic player glow effects", "Coming Soon"), "category:glows");
        add(inv, 15, Material.WRITABLE_BOOK, "&dChat Colours", List.of("Colour your message text", "Click to browse"), "category:chat");
        add(inv, 22, Material.AMETHYST_SHARD, "&bYour Balance", List.of("✦" + KelvorasManager.format(currency.balance(player)) + " Kelvoras"), "info");
        add(inv, 26, Material.BARRIER, "&cClose", List.of(), "close");
        player.openInventory(inv);
    }

    private void openItems(Player player, String category) {
        openCategory.put(player.getUniqueId(), category);
        Inventory inv = Bukkit.createInventory(null, 54, Component.text(TITLE + " | " + categoryLabel(category), NamedTextColor.DARK_GREEN));
        ConfigurationSection entries = plugin.getConfig().getConfigurationSection("shardshop.catalogue." + category);
        int slot = 10;
        if (entries != null) {
            for (String id : entries.getKeys(false)) {
                if (slot >= 44) break;
                ConfigurationSection entry = entries.getConfigurationSection(id);
                if (entry == null) continue;
                String name = entry.getString("name", id);
                String desc = entry.getString("description", "Cosmetic");
                double price = entry.getDouble("price", -1);
                long duration = entry.getLong("duration-seconds", 0);
                boolean available = entry.getBoolean("available", false) && price >= 0 && Double.isFinite(price);
                boolean owned = owns(player.getUniqueId(), category, id);
                boolean equipped = id.equals(data.getString(player.getUniqueId() + ".equipped." + category, ""));
                long expires = data.getLong(player.getUniqueId() + ".owned." + category + "." + id, 0);
                boolean expired = data.getBoolean(player.getUniqueId() + ".expired." + category + "." + id, false);
                List<String> lore = new ArrayList<>();
                lore.add(desc);
                lore.add(available ? "Price: ✦" + KelvorasManager.format(price) + " Kelvoras" : "Coming Soon");
                if (duration > 0) lore.add("Duration: " + durationLabel(duration));
                if (owned && expires > 0) lore.add("Expires: " + new Date(expires).toString());
                if (expired) lore.add("&cExpired — click to renew");
                lore.add(equipped ? "&aEquipped" : owned ? "&eOwned — click to equip" : expired ? "&eClick to renew" : available ? "&aClick to purchase" : "&cComing Soon");
                Material icon = material(entry.getString("icon", "NAME_TAG"));
                add(inv, slot++, icon, (equipped ? "&a" : "&f") + name, lore, "item:" + category + ":" + id);
            }
        }
        add(inv, 45, Material.ARROW, "&eBack", List.of("Return to categories"), "back");
        add(inv, 49, Material.AMETHYST_SHARD, "&bYour Balance", List.of("✦" + KelvorasManager.format(currency.balance(player)) + " Kelvoras"), "info");
        add(inv, 53, Material.BARRIER, "&cClose", List.of(), "close");
        player.openInventory(inv);
    }

    private synchronized void handleItem(Player player, String category, String id) {
        if (category.equals("ranks")) { openRankConfirmation(player, id); return; }
        ConfigurationSection entry = plugin.getConfig().getConfigurationSection("shardshop.catalogue." + category + "." + id);
        if (entry == null) return;
        UUID uuid = player.getUniqueId();
        if (!processing.add(uuid)) return;
        try {
            expire(uuid, category, id);
            boolean owned = owns(uuid, category, id);
            String equipped = data.getString(uuid + ".equipped." + category, "");
            if (owned) {
                if (id.equals(equipped)) {
                    data.set(uuid + ".equipped." + category, null);
                    saveData();
                    applyCosmetic(player, category, null, null);
                    player.sendMessage(Component.text("Cosmetic unequipped.", NamedTextColor.YELLOW));
                } else {
                    data.set(uuid + ".equipped." + category, id);
                    saveData();
                    applyCosmetic(player, category, id, entry);
                    player.sendMessage(Component.text("Equipped " + entry.getString("name", id) + ".", NamedTextColor.GREEN));
                }
                openItems(player, category);
                return;
            }
            double price = entry.getDouble("price", -1);
            if (!entry.getBoolean("available", false) || price < 0 || !Double.isFinite(price)) { player.sendMessage(Component.text("Coming Soon — this cosmetic is not available yet. No Kelvoras were charged.", NamedTextColor.YELLOW)); openItems(player, category); return; }
            double balance = currency.balance(player);
            if (!Double.isFinite(balance) || balance + 0.000001 < price) {
                player.sendMessage(Component.text("Not enough Kelvoras! Balance: ✦" + KelvorasManager.format(balance), NamedTextColor.RED)); return;
            }
            // Save ownership first; currency withdrawal is atomic and rolls back on persistence failure.
            long duration = Math.max(0, entry.getLong("duration-seconds", 0));
            long expires = duration == 0 ? 0 : System.currentTimeMillis() + duration * 1000L;
            data.set(uuid + ".owned." + category + "." + id, expires);
            data.set(uuid + ".expired." + category + "." + id, null);
            String previous = data.getString(uuid + ".equipped." + category, "");
            data.set(uuid + ".equipped." + category, id);
            if (!saveData()) {
                data.set(uuid + ".owned." + category + "." + id, null);
                data.set(uuid + ".equipped." + category, previous.isBlank() ? null : previous);
                player.sendMessage(Component.text("Purchase could not be saved; no charge was made.", NamedTextColor.RED)); return;
            }
            if (!currency.withdraw(player, price)) {
                data.set(uuid + ".owned." + category + "." + id, null);
                data.set(uuid + ".equipped." + category, previous.isBlank() ? null : previous);
                saveData();
                player.sendMessage(Component.text("Purchase failed; no charge was made.", NamedTextColor.RED)); return;
            }
            applyCosmetic(player, category, id, entry);
            player.sendMessage(Component.text("Purchased " + entry.getString("name", id) + " for ✦" + KelvorasManager.format(price) + " Kelvoras.", NamedTextColor.GREEN));
            openItems(player, category);
        } finally { processing.remove(uuid); }
    }

    private void openRankConfirmation(Player player, String id) {
        ConfigurationSection entry = plugin.getConfig().getConfigurationSection("shardshop.catalogue.ranks." + id);
        if (entry == null || !entry.getBoolean("available", false)) { player.sendMessage(Component.text("That temporary rank is unavailable.", NamedTextColor.RED)); return; }
        pendingRankPurchases.put(player.getUniqueId(), id);
        double price = entry.getDouble("price", -1);
        long duration = entry.getLong("duration-seconds", 0);
        Inventory inv = Bukkit.createInventory(null, 27, Component.text(TITLE + " | Confirm Rank", NamedTextColor.DARK_GREEN));
        add(inv, 11, material(entry.getString("icon", "EMERALD")), "&a" + entry.getString("name", id),
            List.of("Rank: " + entry.getString("rank", "verdant"), "Duration: " + durationLabel(duration),
                "Price: ✦" + KelvorasManager.format(price) + " Kelvoras", "Replaces any active temporary rank", "Permanent rank and staff permissions are preserved", "Balance: ✦" + KelvorasManager.format(currency.balance(player))), "rankconfirm");
        add(inv, 15, Material.BARRIER, "&cCancel", List.of("No Kelvoras will be charged"), "rankcancel");
        player.openInventory(inv);
    }

    private synchronized void purchaseTemporaryRank(Player player) {
        UUID uuid = player.getUniqueId();
        if (!processing.add(uuid)) return;
        String id = pendingRankPurchases.get(uuid);
        try {
            if (id == null) return;
            ConfigurationSection entry = plugin.getConfig().getConfigurationSection("shardshop.catalogue.ranks." + id);
            if (entry == null || !entry.getBoolean("available", false)) return;
            String rank = entry.getString("rank", "").toLowerCase(Locale.ROOT);
            if (!Set.of("verdant", "emerald", "sovereign").contains(rank)) { player.sendMessage(Component.text("Invalid rank configuration; no charge was made.", NamedTextColor.RED)); return; }
            double price = entry.getDouble("price", -1);
            long duration = entry.getLong("duration-seconds", 0);
            if (!Double.isFinite(price) || price <= 0 || !Set.of(86400L, 259200L, 604800L).contains(duration)) { player.sendMessage(Component.text("Invalid rank price or duration; no charge was made.", NamedTextColor.RED)); return; }
            if (currency.balance(player) + 0.000001 < price) { player.sendMessage(Component.text("Not enough Kelvoras. Price: ✦" + KelvorasManager.format(price), NamedTextColor.RED)); return; }
            LuckPerms lp;
            try { lp = LuckPermsProvider.get(); } catch (IllegalStateException ex) { player.sendMessage(Component.text("LuckPerms is unavailable; no charge was made.", NamedTextColor.RED)); return; }
            User user = lp.getUserManager().getUser(uuid);
            if (user == null) user = lp.getUserManager().loadUser(uuid).join();
            InheritanceNode newNode = InheritanceNode.builder(rank).expiry(Duration.ofSeconds(duration)).build();
            if (!user.data().add(newNode).wasSuccessful()) { player.sendMessage(Component.text("Could not apply temporary rank; no charge was made.", NamedTextColor.RED)); return; }
            lp.getUserManager().saveUser(user).join();
            if (!currency.withdraw(player, price)) { user.data().remove(newNode); lp.getUserManager().saveUser(user).join(); player.sendMessage(Component.text("Purchase failed; no Kelvoras were charged.", NamedTextColor.RED)); return; }
            for (Node node : new ArrayList<>(user.getNodes())) {
                if (node.equals(newNode) || !(node instanceof InheritanceNode inherited)) continue;
                if (Set.of("verdant", "emerald", "sovereign").contains(inherited.getGroupName().toLowerCase(Locale.ROOT)) && !node.getExpiry().equals(java.time.Instant.MAX)) user.data().remove(node);
            }
            lp.getUserManager().saveUser(user).join();
            long expiresAt = System.currentTimeMillis() + duration * 1000L;
            data.set(uuid + ".temporary-ranks." + rank + ".expires", expiresAt);
            data.set(uuid + ".temporary-ranks." + rank + ".last-product", id);
            saveData();
            pendingRankPurchases.remove(uuid); player.closeInventory();
            player.sendMessage(Component.text("Purchased " + entry.getString("name", rank) + " for ✦" + KelvorasManager.format(price) + " Kelvoras. Duration: " + durationLabel(duration) + ".", NamedTextColor.GREEN));
            plugin.refreshSidebar(player); player.playSound(player.getLocation(), org.bukkit.Sound.ENTITY_PLAYER_LEVELUP, 0.7f, 1.2f);
        } catch (Exception ex) { plugin.getLogger().warning("Temporary rank purchase failed: " + ex.getMessage()); player.sendMessage(Component.text("Rank purchase failed. Contact staff before retrying.", NamedTextColor.RED)); }
        finally { processing.remove(uuid); }
    }
    private void applyCosmetic(Player player, String category, String id, ConfigurationSection entry) {
        if (category.equals("glows")) {
            if (id != null) {
                // Colorized glowing uses scoreboard teams; teams are added to each viewer's current scoreboard.
                ChatColor color = ChatColor.valueOf(entry.getString("color", "GREEN").toUpperCase(Locale.ROOT));
                String glowStatePath = player.getUniqueId() + ".glow.previous-state";
                if (!data.contains(glowStatePath)) data.set(glowStatePath, player.isGlowing());
                saveData();
                player.setGlowing(true);
                for (Player viewer : Bukkit.getOnlinePlayers()) {
                    Scoreboard board = viewer.getScoreboard();
                    String teamName = "ksglow_" + player.getUniqueId().toString().substring(0, 8);
                    Team old = board.getTeam(teamName);
                    Team team = old == null ? board.registerNewTeam(teamName) : old;
                    team.color(switch (color) {
                        case GREEN -> net.kyori.adventure.text.format.NamedTextColor.GREEN;
                        case AQUA -> net.kyori.adventure.text.format.NamedTextColor.AQUA;
                        case BLUE -> net.kyori.adventure.text.format.NamedTextColor.BLUE;
                        case DARK_PURPLE -> net.kyori.adventure.text.format.NamedTextColor.DARK_PURPLE;
                        case RED -> net.kyori.adventure.text.format.NamedTextColor.RED;
                        case GOLD -> net.kyori.adventure.text.format.NamedTextColor.GOLD;
                        default -> net.kyori.adventure.text.format.NamedTextColor.WHITE;
                    });
                    team.addEntry(player.getName());
                }
            } else {
                boolean wasGlowing = data.getBoolean(player.getUniqueId() + ".glow.previous-state", false);
                if (!wasGlowing) player.setGlowing(false);
                data.set(player.getUniqueId() + ".glow.previous-state", null);
                saveData();
                removeGlowTeams(player);
            }
        }
    }

    private void removeGlowTeams(Player player) {
        String teamName = "ksglow_" + player.getUniqueId().toString().substring(0, 8);
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            Team team = viewer.getScoreboard().getTeam(teamName);
            if (team != null) team.removeEntry(player.getName());
        }
    }

    synchronized String equippedTag(Player player) {
        String id = activeCosmetic(player.getUniqueId(), "tags");
        ConfigurationSection section = id == null ? null : plugin.getConfig().getConfigurationSection("shardshop.catalogue.tags." + id);
        return section == null ? null : safeLabel(section.getString("name", id), 12);
    }

    synchronized String equippedTitle(Player player) {
        String id = activeCosmetic(player.getUniqueId(), "titles");
        ConfigurationSection section = id == null ? null : plugin.getConfig().getConfigurationSection("shardshop.catalogue.titles." + id);
        return section == null ? null : safeLabel(section.getString("name", id), 16);
    }

    private String safeLabel(String value, int maxLength) {
        String clean = ChatColor.stripColor(ChatColor.translateAlternateColorCodes('&', value == null ? "" : value));
        if (clean == null) return "";
        clean = clean.replaceAll("[^A-Za-z0-9 _-]", "").trim();
        return clean.length() > maxLength ? clean.substring(0, maxLength) : clean;
    }

    synchronized String chatColour(Player player) {
        String id = activeCosmetic(player.getUniqueId(), "chat");
        if (id == null) return null;
        ConfigurationSection section = plugin.getConfig().getConfigurationSection("shardshop.catalogue.chat." + id);
        return section == null ? null : section.getString("legacy-color", null);
    }

    private synchronized String activeCosmetic(UUID uuid, String category) {
        String id = data.getString(uuid + ".equipped." + category);
        if (id == null || id.isBlank() || !owns(uuid, category, id)) return null;
        return id;
    }

    private synchronized boolean owns(UUID uuid, String category, String id) {
        String path = uuid + ".owned." + category + "." + id;
        if (!data.contains(path)) return false;
        long expires = data.getLong(path, 0);
        return expires == 0 || expires > System.currentTimeMillis();
    }

    private void expire(UUID uuid, String category, String id) {
        String path = uuid + ".owned." + category + "." + id;
        long expires = data.getLong(path, 0);
        if (expires > 0 && expires <= System.currentTimeMillis()) {
            data.set(path, null);
            data.set(uuid + ".expired." + category + "." + id, true);
            if (id.equals(data.getString(uuid + ".equipped." + category))) data.set(uuid + ".equipped." + category, null);
            saveData();
        }
    }

    private synchronized void expireAll() {
        if (data == null) return;
        boolean changed = false;
        for (String uuidText : data.getKeys(false)) {
            UUID uuid;
            try { uuid = UUID.fromString(uuidText); } catch (IllegalArgumentException ex) { continue; }
            ConfigurationSection owned = data.getConfigurationSection(uuidText + ".owned");
            if (owned == null) continue;
            for (String category : owned.getKeys(false)) {
                ConfigurationSection cosmetics = owned.getConfigurationSection(category);
                if (cosmetics == null) continue;
                for (String id : cosmetics.getKeys(false)) {
                    long expires = cosmetics.getLong(id, 0);
                    if (expires > 0 && expires <= System.currentTimeMillis()) {
                        data.set(uuidText + ".owned." + category + "." + id, null);
                        data.set(uuidText + ".expired." + category + "." + id, true);
                        if (id.equals(data.getString(uuidText + ".equipped." + category))) {
                            data.set(uuidText + ".equipped." + category, null);
                            if (category.equals("glows")) {
                                Player online = Bukkit.getPlayer(uuid);
                                if (online != null) {
                                    boolean wasGlowing = data.getBoolean(uuidText + ".glow.previous-state", false);
                                    if (!wasGlowing) online.setGlowing(false);
                                    data.set(uuidText + ".glow.previous-state", null);
                                    removeGlowTeams(online);
                                }
                            }
                        }
                        changed = true;
                    }
                }
            }
        }
        if (changed) saveData();
    }

    private String categoryLabel(String category) {
        return switch (category) { case "tags" -> "Tags"; case "titles" -> "Temporary Titles"; case "ranks" -> "Temporary Ranks"; case "glows" -> "Glows"; default -> "Chat Colours"; };
    }
    private String durationLabel(long seconds) {
        if (seconds % 86400 == 0) return (seconds / 86400) + " day(s)";
        if (seconds % 3600 == 0) return (seconds / 3600) + " hour(s)";
        return seconds + " seconds";
    }
    private Material material(String name) {
        Material value = Material.matchMaterial(name);
        return value == null || !value.isItem() ? Material.NAME_TAG : value;
    }
    private void add(Inventory inv, int slot, Material material, String name, List<String> lore, String action) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(LegacyComponentSerializer.legacyAmpersand().deserialize(name));
        List<Component> lines = new ArrayList<>();
        for (String line : lore) lines.add(LegacyComponentSerializer.legacyAmpersand().deserialize("&7" + line));
        meta.lore(lines);
        meta.getPersistentDataContainer().set(actionKey, PersistentDataType.STRING, action);
        item.setItemMeta(meta);
        inv.setItem(slot, item);
    }
    private synchronized boolean saveData() {
        try { if (!plugin.getDataFolder().exists()) plugin.getDataFolder().mkdirs(); data.save(dataFile); return true; }
        catch (IOException ex) { plugin.getLogger().severe("Could not save ShardShop ownership: " + ex.getMessage()); return false; }
    }

    @EventHandler public void onClick(InventoryClickEvent event) {
        String title = event.getView().title().toString();
        if (!title.contains(TITLE)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player) || event.getClickedInventory() != event.getView().getTopInventory()) return;
        ItemStack item = event.getCurrentItem();
        if (item == null || !item.hasItemMeta()) return;
        String action = item.getItemMeta().getPersistentDataContainer().get(actionKey, PersistentDataType.STRING);
        if (action == null) return;
        if (action.equals("close")) { pendingRankPurchases.remove(player.getUniqueId()); player.closeInventory(); return; }
        if (action.equals("back")) { pendingRankPurchases.remove(player.getUniqueId()); openCategories(player); return; }
        if (action.equals("rankcancel")) { pendingRankPurchases.remove(player.getUniqueId()); openItems(player, "ranks"); return; }
        if (action.equals("rankconfirm")) { purchaseTemporaryRank(player); return; }
        if (action.startsWith("category:")) { openItems(player, action.substring(9)); return; }
        if (action.startsWith("item:")) {
            String[] parts = action.split(":", 3);
            if (parts.length == 3) handleItem(player, parts[1], parts[2]);
        }
    }
    @EventHandler public void onDrag(InventoryDragEvent event) {
        if (event.getView().title().toString().contains(TITLE)) event.setCancelled(true);
    }
    @EventHandler public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        Bukkit.getScheduler().runTask(plugin, () -> {
            for (String category : List.of("glows")) {
                String id = activeCosmetic(player.getUniqueId(), category);
                if (id != null) {
                    ConfigurationSection entry = plugin.getConfig().getConfigurationSection("shardshop.catalogue." + category + "." + id);
                    if (entry != null) applyCosmetic(player, category, id, entry);
                }
            }
        });
    }
    @EventHandler public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        if (activeCosmetic(player.getUniqueId(), "glows") != null) removeGlowTeams(player);
    }
}
