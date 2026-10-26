package net.kelvora.smp;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.model.user.User;
import net.luckperms.api.node.Node;
import net.luckperms.api.node.types.InheritanceNode;
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
import org.bukkit.event.EventPriority;
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
import java.util.concurrent.TimeUnit;

final class ShardShopManager implements Listener {
    private static final String TITLE = "Kelvora | ShardShop";
    private final KelvoraSMP plugin;
    private final KelvorasManager currency;
    private final NamespacedKey actionKey;
    private final File dataFile;
    private YamlConfiguration data;
    private final Set<UUID> processing = ConcurrentHashMap.newKeySet();
    private final Map<UUID, String> openCategory = new HashMap<>();

    ShardShopManager(KelvoraSMP plugin, KelvorasManager currency) {
        this.plugin = plugin;
        this.currency = currency;
        this.actionKey = new NamespacedKey(plugin, "shardshop-action");
        this.dataFile = new File(plugin.getDataFolder(), "shardshop-data.yml");
    }

    void register() {
        data = YamlConfiguration.loadConfiguration(dataFile);
        migrateLegacyTemporaryRanks();
        migrateCosmeticCatalogue();
        expireAll();
        Bukkit.getPluginManager().registerEvents(this, plugin);
        if (plugin.getCommand("shardshop") != null) plugin.getCommand("shardshop").setExecutor(this::execute);
        if (plugin.getCommand("shards") != null) plugin.getCommand("shards").setExecutor(this::execute);
        if (plugin.getCommand("shardshopreload") != null) plugin.getCommand("shardshopreload").setExecutor(this::execute);
        if (plugin.getCommand("glow") != null) plugin.getCommand("glow").setExecutor(this::execute);
        if (plugin.getCommand("tags") != null) plugin.getCommand("tags").setExecutor(this::execute);
        if (plugin.getCommand("chatcolour") != null) plugin.getCommand("chatcolour").setExecutor(this::execute);
        Bukkit.getScheduler().runTaskTimer(plugin, this::expireAll, 20L, 1200L);
    }

    /**
     * One-time migration: old ShardShop temporary ranks were real expiring LuckPerms groups.
     * Convert active purchases into cosmetic title ownership and remove only expiring nodes
     * matching those recorded purchases. Permanent inheritance nodes are never removed.
     */
    private synchronized void migrateLegacyTemporaryRanks() {
        // Repair old timed-rank purchases without stripping their real LuckPerms inheritance.
        // This also restores an unexpired rank if an older plugin build incorrectly removed it.
        LuckPerms lp;
        try { lp = LuckPermsProvider.get(); }
        catch (IllegalStateException ex) {
            plugin.getLogger().warning("Temporary-rank repair deferred because LuckPerms is unavailable.");
            return;
        }
        boolean migrationNeeded = !data.getBoolean("_migration.temporary-ranks-cosmetic", false);
        long now = System.currentTimeMillis();
        for (String uuidText : new ArrayList<>(data.getKeys(false))) {
            UUID uuid;
            try { uuid = UUID.fromString(uuidText); }
            catch (IllegalArgumentException ignored) { continue; }
            ConfigurationSection legacy = data.getConfigurationSection(uuidText + ".temporary-ranks");
            if (legacy == null) continue;
            try {
                User user = lp.getUserManager().getUser(uuid);
                if (user == null) user = lp.getUserManager().loadUser(uuid).join();
                boolean userRepaired = false;
                for (String rank : legacy.getKeys(false)) {
                    long expires = legacy.getLong(rank + ".expires", 0);
                    String product = legacy.getString(rank + ".last-product", "");
                    if (expires <= now) continue;
                    if (migrationNeeded && !product.isBlank()
                            && plugin.getConfig().isConfigurationSection("shardshop.catalogue.ranks." + product)) {
                        data.set(uuidText + ".owned.ranks." + product, expires);
                        data.set(uuidText + ".equipped.ranks", product);
                    }
                    String rankKey = rank.toLowerCase(Locale.ROOT);
                    if (!Set.of("verdant", "emerald", "sovereign").contains(rankKey)) continue;
                    boolean hasPermanent = user.getNodes().stream().anyMatch(node ->
                        node instanceof InheritanceNode inherited && inherited.getGroupName().equalsIgnoreCase(rankKey)
                            && node.getExpiry().equals(java.time.Instant.MAX));
                    boolean hasTimed = user.getNodes().stream().anyMatch(node ->
                        node instanceof InheritanceNode inherited && inherited.getGroupName().equalsIgnoreCase(rankKey)
                            && !node.getExpiry().equals(java.time.Instant.MAX) && node.getExpiry().isAfter(java.time.Instant.now()));
                    if (!hasPermanent && !hasTimed) {
                        user.data().add(InheritanceNode.builder(rankKey).expiry(expires - now, TimeUnit.MILLISECONDS).build());
                        userRepaired = true;
                        plugin.getLogger().info("Restored active temporary " + rankKey + " rank for " + uuidText + ".");
                    }
                }
                if (userRepaired) lp.getUserManager().saveUser(user).join();
            } catch (Exception ex) {
                plugin.getLogger().warning("Could not repair temporary rank for " + uuidText + ": " + ex.getMessage());
            }
        }
        data.set("_migration.temporary-ranks-cosmetic", true);
        saveData();
    }

    /**
     * Existing servers keep their old config.yml when the JAR is replaced. Enable the
     * newly implemented cosmetic catalog once while preserving all configured prices.
     */
    private synchronized void migrateCosmeticCatalogue() {
        if (plugin.getConfig().getInt("shardshop.schema-version", 0) >= 3) return;
        for (String category : List.of("tags", "glows", "chat")) {
            ConfigurationSection entries = plugin.getConfig().getConfigurationSection("shardshop.catalogue." + category);
            if (entries == null) continue;
            for (String id : entries.getKeys(false)) {
                ConfigurationSection entry = entries.getConfigurationSection(id);
                if (entry != null && entry.contains("price") && entry.getDouble("price", -1) > 0) {
                    entry.set("available", true);
                }
            }
        }
        ConfigurationSection rankEntries = plugin.getConfig().getConfigurationSection("shardshop.catalogue.ranks");
        if (rankEntries != null) for (String id : rankEntries.getKeys(false)) {
            ConfigurationSection entry = rankEntries.getConfigurationSection(id);
            if (entry == null) continue;
            String rank = entry.getString("rank", "").trim();
            if (rank.isBlank()) continue;
            String displayRank = rank.substring(0, 1).toUpperCase(Locale.ROOT) + rank.substring(1).toLowerCase(Locale.ROOT);
            long seconds = entry.getLong("duration-seconds", 0);
            String duration = seconds % 86400 == 0 ? (seconds / 86400) + (seconds == 86400 ? " day" : " days") : durationLabel(seconds);
            entry.set("name", displayRank + " Rank — " + duration);
            entry.set("description", "Grants the actual " + displayRank + " rank and its permissions for " + duration + ".");
            entry.set("available", true);
        }
        plugin.getConfig().set("shardshop.schema-version", 3);
        plugin.saveConfig();
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
        if (label.equalsIgnoreCase("glow") || label.equalsIgnoreCase("tags") || label.equalsIgnoreCase("chatcolour")) {
            String category = label.equalsIgnoreCase("glow") ? "glows" : label.equalsIgnoreCase("tags") ? "tags" : "chat";
            if (!player.hasPermission("kelvora.shardshop.use")) { player.sendMessage(Component.text("You do not have permission.", NamedTextColor.RED)); return true; }
            openOwnedItems(player, category);
            return true;
        }
        if (!player.hasPermission("kelvora.shardshop.use")) { player.sendMessage(Component.text("You do not have permission.", NamedTextColor.RED)); return true; }
        openCategories(player);
        return true;
    }

    private synchronized void toggleCosmetic(Player player, String category) {
        UUID uuid = player.getUniqueId();
        String path = uuid + ".equipped." + category;
        String current = activeCosmetic(uuid, category);
        if (current != null) {
            data.set(path, null);
            if (!saveData()) {
                data.set(path, current);
                player.sendMessage(Component.text("Could not save your cosmetic setting. Try again.", NamedTextColor.RED));
                return;
            }
            applyCosmetic(player, category, null, null);
            player.sendMessage(Component.text((category.equals("glows") ? "Glow" : "Tag") + " turned off.", NamedTextColor.YELLOW));
            plugin.refreshSidebar(player);
            return;
        }
        ConfigurationSection entries = plugin.getConfig().getConfigurationSection("shardshop.catalogue." + category);
        String selected = null;
        if (entries != null) {
            for (String id : entries.getKeys(false)) {
                if (owns(uuid, category, id)) { selected = id; break; }
            }
        }
        if (selected == null) {
            player.sendMessage(Component.text("You do not own an active " + (category.equals("glows") ? "glow" : "tag") + ". Visit /shardshop.", NamedTextColor.RED));
            return;
        }
        data.set(path, selected);
        if (!saveData()) {
            data.set(path, null);
            player.sendMessage(Component.text("Could not save your cosmetic setting. Try again.", NamedTextColor.RED));
            return;
        }
        ConfigurationSection entry = plugin.getConfig().getConfigurationSection("shardshop.catalogue." + category + "." + selected);
        applyCosmetic(player, category, selected, entry);
        player.sendMessage(Component.text((category.equals("glows") ? "Glow" : "Tag") + " turned on.", NamedTextColor.GREEN));
        plugin.refreshSidebar(player);
    }

    private void openCategories(Player player) {
        openCategory.put(player.getUniqueId(), "categories");
        Inventory inv = Bukkit.createInventory(null, 27, Component.text("✦  KELVORA | SHARDSHOP", NamedTextColor.DARK_GREEN));
        ItemStack frame = new ItemStack(Material.GREEN_STAINED_GLASS_PANE);
        ItemMeta frameMeta = frame.getItemMeta();
        frameMeta.displayName(Component.text(" "));
        frame.setItemMeta(frameMeta);
        for (int slot = 0; slot < 9; slot++) inv.setItem(slot, frame);
        inv.setItem(9, frame); inv.setItem(17, frame); inv.setItem(18, frame);
        for (int slot = 19; slot < 26; slot++) if (slot != 22) inv.setItem(slot, frame);
        add(inv, 10, Material.NAME_TAG, "&a&lTAGS", List.of("&7Personal labels beside your name", "", "&eBrowse tag cosmetics"), "category:tags");
        add(inv, 12, Material.EMERALD, "&2&lTEMPORARY RANKS", List.of("&7Timed rank access", "&7with matching permissions", "", "&eChoose a duration"), "category:ranks");
        add(inv, 14, Material.GLOW_INK_SAC, "&b&lGLOWS", List.of("&7Choose your player glow colour", "", "&eBrowse glow cosmetics"), "category:glows");
        add(inv, 16, Material.WRITABLE_BOOK, "&d&lCHAT COLOURS", List.of("&7Personalise your chat messages", "", "&eBrowse chat colours"), "category:chat");
        add(inv, 22, Material.AMETHYST_SHARD, "&b&lYOUR KELVORAS", List.of("&7Balance: &f✦" + KelvorasManager.format(currency.balance(player)), "&8Spend them on cosmetics"), "info");
        add(inv, 26, Material.BARRIER, "&c&lCLOSE", List.of("&7Close ShardShop"), "close");
        player.openInventory(inv);
    }

    private void openItems(Player player, String category) { openItems(player, category, false); }

    private void openOwnedItems(Player player, String category) { openItems(player, category, true); }

    private void openItems(Player player, String category, boolean ownedOnly) {
        openCategory.put(player.getUniqueId(), category);
        Inventory inv = Bukkit.createInventory(null, 54, Component.text("✦  SHARDSHOP  |  " + categoryLabel(category).toUpperCase(Locale.ROOT), NamedTextColor.DARK_GREEN));
        ItemStack frame = new ItemStack(Material.GREEN_STAINED_GLASS_PANE);
        ItemMeta frameMeta = frame.getItemMeta();
        frameMeta.displayName(Component.text(" "));
        frame.setItemMeta(frameMeta);
        for (int slot = 0; slot < 9; slot++) inv.setItem(slot, frame);
        for (int slot : new int[]{9,17,18,26,27,35,36,44}) inv.setItem(slot, frame);
        for (int slot = 45; slot < 54; slot++) if (slot != 45 && slot != 49 && slot != 53) inv.setItem(slot, frame);

        ConfigurationSection entries = plugin.getConfig().getConfigurationSection("shardshop.catalogue." + category);
        List<Integer> gridSlots = List.of(10,11,12,13,14,15,16,19,20,21,22,23,24,25,28,29,30,31,32,33,34,37,38,39,40,41,42,43);
        int slotIndex = 0;
        int shown = 0;
        if (entries != null) {
            for (String id : entries.getKeys(false)) {
                ConfigurationSection entry = entries.getConfigurationSection(id);
                if (entry == null) continue;
                String name = entry.getString("name", id);
                String desc = entry.getString("description", "Cosmetic");
                double price = entry.getDouble("price", -1);
                long duration = entry.getLong("duration-seconds", 0);
                boolean available = entry.getBoolean("available", false) && price >= 0 && Double.isFinite(price);
                boolean owned = owns(player.getUniqueId(), category, id);
                if (ownedOnly && !owned) continue;
                boolean equipped = id.equals(data.getString(player.getUniqueId() + ".equipped." + category, ""));
                long expires = data.getLong(player.getUniqueId() + ".owned." + category + "." + id, 0);
                boolean expired = data.getBoolean(player.getUniqueId() + ".expired." + category + "." + id, false);
                List<String> lore = new ArrayList<>();
                lore.add("&7" + desc);
                lore.add("");
                lore.add(available ? "&bPrice: &f✦" + KelvorasManager.format(price) + " Kelvoras" : "&cCurrently unavailable");
                if (duration > 0) lore.add("&7Duration: &f" + durationLabel(duration));
                if (owned && expires > 0) lore.add("&7Expires: &f" + new java.text.SimpleDateFormat("dd MMM yyyy HH:mm").format(new Date(expires)));
                if (expired) lore.add("&cExpired — click to renew");
                lore.add("");
                lore.add(equipped ? "&a✔ Currently equipped" : owned ? "&eClick to equip" : expired ? "&eClick to renew" : available ? "&aClick to purchase" : "&cComing soon");
                Material icon = material(entry.getString("icon", "NAME_TAG"));
                String displayName = (equipped ? "&a&l" : owned ? "&e&l" : "&f&l") + name;
                if (category.equalsIgnoreCase("tags")) {
                    String tagColour = entry.getString("color", "");
                    if (tagColour.isBlank()) {
                        String[] palette = {"&b", "&d", "&6", "&a", "&e", "&9", "&5", "&c"};
                        tagColour = palette[Math.floorMod(id.toLowerCase(Locale.ROOT).hashCode(), palette.length)];
                    }
                    displayName = tagColour + (entry.getBoolean("bold", true) ? "&l" : "") + name;
                }
                if (category.equalsIgnoreCase("ranks")) {
                    int base = id.startsWith("verdant") ? 10 : id.startsWith("emerald") ? 12 : id.startsWith("sovereign") ? 14 : 16;
                    int row = id.endsWith("_1d") ? 0 : id.endsWith("_3d") ? 1 : 2;
                    add(inv, base + row * 9, icon, displayName + (duration > 0 ? " &8(" + durationLabel(duration) + ")" : ""), lore, "item:" + category + ":" + id);
                } else {
                    if (slotIndex >= gridSlots.size()) break;
                    add(inv, gridSlots.get(slotIndex++), icon, displayName, lore, "item:" + category + ":" + id);
                }
                shown++;
            }
        }
        if (shown == 0) add(inv, 22, Material.PAPER, "&7&lNOTHING TO SHOW", List.of("&8No cosmetics in this view yet", "&7Visit the main ShardShop to browse all items"), "info");
        if (ownedOnly && activeCosmetic(player.getUniqueId(), category) != null) {
            String disableLabel = category.equals("glows") ? "&c&lDISABLE GLOW" : category.equals("tags") ? "&c&lDISABLE TAG" : "&c&lDISABLE COSMETIC";
            add(inv, 47, Material.BARRIER, disableLabel, List.of("&7Turn off your currently equipped cosmetic"), "disable:" + category);
        }
        add(inv, 45, Material.ARROW, "&e&lBACK", List.of("&7Return to ShardShop categories"), "back");
        add(inv, 49, Material.AMETHYST_SHARD, "&b&lKELVORAS: ✦" + KelvorasManager.format(currency.balance(player)), List.of("&7Your current balance"), "info");
        add(inv, 53, Material.BARRIER, "&c&lCLOSE", List.of("&7Close ShardShop"), "close");
        player.openInventory(inv);
    }

    private void openPurchaseConfirmation(Player player, String category, String id) {
        ConfigurationSection entry = plugin.getConfig().getConfigurationSection("shardshop.catalogue." + category + "." + id);
        if (entry == null || !entry.getBoolean("available", false)) {
            player.sendMessage(Component.text("This cosmetic is not available yet.", NamedTextColor.YELLOW));
            openItems(player, category); return;
        }
        double price = entry.getDouble("price", -1);
        if (price < 0 || !Double.isFinite(price)) { openItems(player, category); return; }
        Inventory inv = Bukkit.createInventory(null, 27, Component.text(TITLE + " | Confirm Purchase", NamedTextColor.DARK_GREEN));
        Material icon = material(entry.getString("icon", "NAME_TAG"));
        add(inv, 13, icon, "&f" + entry.getString("name", id), List.of(
            entry.getString("description", "Cosmetic"),
            "&eExact price: ✦" + KelvorasManager.format(price) + " Kelvoras",
            entry.getLong("duration-seconds", 0) > 0 ? "&eDuration: " + durationLabel(entry.getLong("duration-seconds", 0)) : "&ePermanent ownership",
            "&7No currency is taken until you confirm."
        ), "info");
        add(inv, 11, Material.EMERALD_BLOCK, "&aConfirm Purchase", List.of("&ePay ✦" + KelvorasManager.format(price) + " Kelvoras", "&7This action cannot be undone."), "confirm:" + category + ":" + id);
        add(inv, 15, Material.BARRIER, "&cCancel", List.of("Return without spending Kelvoras"), "cancel");
        player.openInventory(inv);
    }

    private synchronized void handleItem(Player player, String category, String id) {
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
            if (category.equals("ranks")) grantTimedRank(player, entry, duration);
            applyCosmetic(player, category, id, entry);
            player.sendMessage(Component.text(category.equals("ranks")
                ? "Purchased " + entry.getString("name", id) + " for ✦" + KelvorasManager.format(price) + " Kelvoras. Your rank permissions are active for the listed duration."
                : "Purchased " + entry.getString("name", id) + " for ✦" + KelvorasManager.format(price) + " Kelvoras.", NamedTextColor.GREEN));
            openItems(player, category);
        } finally { processing.remove(uuid); }
    }

    /** Apply the actual LuckPerms rank group for the purchased duration. */
    private void grantTimedRank(Player player, ConfigurationSection entry, long durationSeconds) {
        String rank = entry.getString("rank", "").trim().toLowerCase(Locale.ROOT);
        if (rank.isBlank() || durationSeconds <= 0) return;
        try {
            LuckPerms lp = LuckPermsProvider.get();
            User user = lp.getUserManager().getUser(player.getUniqueId());
            if (user == null) user = lp.getUserManager().loadUser(player.getUniqueId()).join();
            InheritanceNode timedRank = InheritanceNode.builder(rank).expiry(durationSeconds, TimeUnit.SECONDS).build();
            user.data().add(timedRank);
            lp.getUserManager().saveUser(user).join();
            plugin.getLogger().info("Granted temporary " + rank + " group to " + player.getName() + " for " + durationSeconds + " seconds.");
            plugin.refreshSidebar(player);
        } catch (Exception ex) {
            plugin.getLogger().severe("Could not grant purchased temporary rank to " + player.getName() + ": " + ex.getMessage());
            player.sendMessage(Component.text("Your purchase was saved, but rank permissions could not be applied. Contact staff.", NamedTextColor.RED));
        }
    }

    private void applyCosmetic(Player player, String category, String id, ConfigurationSection entry) {
        if (category.equals("glows")) {
            if (id != null) {
                // Colorized glowing uses scoreboard teams; teams are added to each viewer's current scoreboard.
                ChatColor color = ChatColor.valueOf(entry.getString("color", "GREEN").toUpperCase(Locale.ROOT));
                String glowStatePath = player.getUniqueId() + ".glow.previous-state";
                if (!data.contains(glowStatePath)) {
                    data.set(glowStatePath, player.isGlowing());
                    saveData();
                }
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
                // Turning off a cosmetic glow must remove the outline itself, not only its colour team.
                player.setGlowing(false);
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
            if (team != null) {
                team.removeEntry(player.getName());
                if (team.getEntries().isEmpty()) team.unregister();
            }
        }
    }

    synchronized void refreshCosmetics(Player player) {
        String id = activeCosmetic(player.getUniqueId(), "glows");
        if (id != null) {
            ConfigurationSection entry = plugin.getConfig().getConfigurationSection("shardshop.catalogue.glows." + id);
            if (entry != null) applyCosmetic(player, "glows", id, entry);
        } else {
            String statePath = player.getUniqueId() + ".glow.previous-state";
            // Repair stale cosmetic outlines left by earlier versions/reloads.
            if (data.contains(statePath)) {
                player.setGlowing(false);
                data.set(statePath, null);
                saveData();
                removeGlowTeams(player);
            }
        }
    }

    synchronized String equippedTag(Player player) {
        String id = activeCosmetic(player.getUniqueId(), "tags");
        ConfigurationSection section = id == null ? null : plugin.getConfig().getConfigurationSection("shardshop.catalogue.tags." + id);
        return section == null ? null : safeLabel(section.getString("name", id), 12);
    }

    synchronized String equippedTagFormatted(Player player) {
        String id = activeCosmetic(player.getUniqueId(), "tags");
        if (id == null) return null;
        ConfigurationSection section = plugin.getConfig().getConfigurationSection("shardshop.catalogue.tags." + id);
        if (section == null) return null;
        String name = section.getString("name", id);
        String colour = section.getString("color", "");
        String style = section.getString("style", "");
        if (colour.isBlank()) {
            String[] palette = {"&b", "&d", "&6", "&a", "&e", "&9", "&5", "&c"};
            colour = palette[Math.floorMod(id.toLowerCase(Locale.ROOT).hashCode(), palette.length)];
        }
        if (section.getBoolean("bold", true) || style.equalsIgnoreCase("bold")) colour += "&l";
        return colour + "[" + name + "]";
    }

    synchronized String equippedTitle(Player player) {
        String id = activeCosmetic(player.getUniqueId(), "titles");
        ConfigurationSection section = id == null ? null : plugin.getConfig().getConfigurationSection("shardshop.catalogue.titles." + id);
        if (section != null) return safeLabel(section.getString("name", id), 16);
        id = activeCosmetic(player.getUniqueId(), "ranks");
        section = id == null ? null : plugin.getConfig().getConfigurationSection("shardshop.catalogue.ranks." + id);
        if (section == null) return null;
        String rank = section.getString("rank", "");
        String label = plugin.getConfig().getString("ranks." + rank + ".label", rank.isBlank() ? section.getString("name", id) : rank);
        return safeLabel(label, 16);
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
        return switch (category) { case "tags" -> "Tags"; case "titles" -> "Temporary Ranks"; case "ranks" -> "Temporary Ranks"; case "glows" -> "Glows"; default -> "Chat Colours"; };
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

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        Player source = event.getPlayer();
        String colour = chatColour(source);
        Component message = event.message();
        if (colour != null && !colour.isBlank()) {
            String legacyMessage = LegacyComponentSerializer.legacySection().serialize(message);
            String prefixColour = ChatColor.translateAlternateColorCodes('&', colour);
            message = LegacyComponentSerializer.legacySection().deserialize(prefixColour + legacyMessage);
        }
        Component chatMessage = message;
        event.renderer((player, displayName, renderedMessage, viewer) -> {
            Component prefix = plugin.chatRankPrefix(player);
            String cosmeticRank = equippedTitle(player);
            if (cosmeticRank != null && !cosmeticRank.isBlank()) {
                prefix = prefix.append(LegacyComponentSerializer.legacyAmpersand().deserialize("&7[" + cosmeticRank + "] "));
            }
            String tag = equippedTagFormatted(player);
            if (tag != null && !tag.isBlank()) prefix = prefix.append(LegacyComponentSerializer.legacyAmpersand().deserialize(" " + tag + " "));
            return prefix.append(Component.text(player.getName(), NamedTextColor.WHITE))
                    .append(Component.text(": ", NamedTextColor.GRAY)).append(chatMessage);
        });
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
        if (action.equals("close")) { player.closeInventory(); return; }
        if (action.startsWith("disable:")) {
            String category=action.substring("disable:".length());
            data.set(player.getUniqueId()+".equipped."+category,null);
            if(!saveData()) { player.sendMessage(Component.text("Could not save cosmetic setting.",NamedTextColor.RED)); return; }
            applyCosmetic(player,category,null,null);
            player.sendMessage(Component.text((category.equals("glows")?"Glow":"Tag")+" disabled.",NamedTextColor.YELLOW));
            plugin.refreshSidebar(player); openOwnedItems(player,category); return;
        }
        if (action.equals("cancel")) { String category = openCategory.getOrDefault(player.getUniqueId(), "categories"); openItems(player, category); return; }
        if (action.equals("back")) { openCategories(player); return; }
        if (action.startsWith("category:")) { openItems(player, action.substring(9)); return; }
        if (action.startsWith("confirm:")) {
            String[] parts = action.split(":", 3);
            if (parts.length == 3) handleItem(player, parts[1], parts[2]);
            return;
        }
        if (action.startsWith("item:")) {
            String[] parts = action.split(":", 3);
            if (parts.length == 3) {
                if (owns(player.getUniqueId(), parts[1], parts[2])) handleItem(player, parts[1], parts[2]);
                else { openCategory.put(player.getUniqueId(), parts[1]); openPurchaseConfirmation(player, parts[1], parts[2]); }
            }
        }
    }
    @EventHandler public void onDrag(InventoryDragEvent event) {
        if (event.getView().title().toString().contains(TITLE)) event.setCancelled(true);
    }
    @EventHandler public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        Bukkit.getScheduler().runTask(plugin, () -> {
            refreshCosmetics(player);
            plugin.refreshSidebar(player);
        });
    }
    @EventHandler public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        if (activeCosmetic(player.getUniqueId(), "glows") != null) removeGlowTeams(player);
    }
}
