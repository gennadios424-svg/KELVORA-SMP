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
    private final KelvoraSMP plugin;
    private final KelvorasManager currency;
    private final NamespacedKey actionKey;
    private final File dataFile;
    private YamlConfiguration data;
    private final Set<UUID> processing = ConcurrentHashMap.newKeySet();
    private final Map<UUID, String> openCategory = new HashMap<>();
    private final Set<Inventory> activeInventories = Collections.newSetFromMap(new IdentityHashMap<>());

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
        Bukkit.getScheduler().runTaskTimer(plugin, this::expireAll, 20L, 1200L);
    }

    /** Safe migration: convert old cosmetic rank purchases into real expiring LuckPerms ranks. */
    private synchronized void migrateLegacyTemporaryRanks() {
        if (data.getBoolean("_migration.temporary-ranks-authoritative-v2", false)) return;
        LuckPerms lp;
        try { lp = LuckPermsProvider.get(); }
        catch (IllegalStateException ex) { plugin.getLogger().warning("Temporary-rank migration deferred: LuckPerms unavailable."); return; }
        long now = System.currentTimeMillis();
        for (String uuidText : new ArrayList<>(data.getKeys(false))) {
            UUID uuid;
            try { uuid = UUID.fromString(uuidText); } catch (IllegalArgumentException ignored) { continue; }
            ConfigurationSection legacy = data.getConfigurationSection(uuidText + ".temporary-ranks");
            ConfigurationSection oldOwned = data.getConfigurationSection(uuidText + ".owned.ranks");
            Map<String, Long> expiries = new HashMap<>();
            Map<String, String> products = new HashMap<>();
            if (oldOwned != null) {
                for (String product : oldOwned.getKeys(false)) {
                    ConfigurationSection offer = plugin.getConfig().getConfigurationSection("shardshop.catalogue.ranks." + product);
                    if (offer == null) continue;
                    String rank = offer.getString("rank", "").toLowerCase(Locale.ROOT);
                    long expiry = oldOwned.getLong(product, 0L);
                    if (!Set.of("verdant", "emerald", "sovereign").contains(rank) || expiry <= now) continue;
                    if (expiry > expiries.getOrDefault(rank, 0L)) {
                        expiries.put(rank, expiry);
                        products.put(rank, product);
                    }
                }
            }
            if (legacy != null) {
                for (String rankKey : legacy.getKeys(false)) {
                    long expiry = legacy.getLong(rankKey + ".expires", 0L);
                    String rank = rankKey.toLowerCase(Locale.ROOT);
                    String product = legacy.getString(rankKey + ".last-product", "");
                    if (!Set.of("verdant", "emerald", "sovereign").contains(rank) || expiry <= now) continue;
                    if (expiry > expiries.getOrDefault(rank, 0L)) {
                        expiries.put(rank, expiry);
                        if (!product.isBlank()) products.put(rank, product);
                    }
                }
            }
            if (expiries.isEmpty()) continue;
            try {
                User user = lp.getUserManager().getUser(uuid);
                if (user == null) user = lp.getUserManager().loadUser(uuid).join();
                boolean changed = false;
                for (Map.Entry<String, Long> item : expiries.entrySet()) {
                    String rank = item.getKey();
                    long expiry = item.getValue();
                    String product = products.getOrDefault(rank, "");
                    data.set(uuidText + ".temporary-ranks." + rank + ".expires", expiry);
                    if (!product.isBlank() && plugin.getConfig().isConfigurationSection("shardshop.catalogue.ranks." + product)) {
                        data.set(uuidText + ".temporary-ranks." + rank + ".last-product", product);
                        data.set(uuidText + ".owned.ranks." + product, expiry);
                    }
                    boolean timedNode = user.getNodes().stream().anyMatch(node ->
                        node instanceof InheritanceNode inherited && inherited.getGroupName().equalsIgnoreCase(rank)
                            && !node.getExpiry().equals(java.time.Instant.MAX) && node.getExpiry().toEpochMilli() > now);
                    if (!timedNode) {
                        user.data().add(InheritanceNode.builder(rank)
                            .expiry(java.time.Duration.ofMillis(Math.max(1000L, expiry - now))).build());
                        changed = true;
                    }
                }
                if (changed) lp.getUserManager().saveUser(user).join();
            } catch (Exception ex) {
                plugin.getLogger().warning("Could not restore temporary rank for " + uuidText + ": " + ex.getMessage());
                return;
            }
        }
        data.set("_migration.temporary-ranks-authoritative-v2", true);
        saveData();
    }

    /**
     * Existing servers keep their old config.yml when the JAR is replaced. Enable the
     * newly implemented cosmetic catalog once while preserving all configured prices.
     */
    private synchronized void migrateCosmeticCatalogue() {
        if (plugin.getConfig().getInt("shardshop.schema-version", 0) >= 2) return;
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
        plugin.getConfig().set("shardshop.schema-version", 2);
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
        if (label.equalsIgnoreCase("glow") || label.equalsIgnoreCase("tags")) {
            if (!player.hasPermission("kelvora.shardshop.use")) { player.sendMessage(Component.text("You do not have permission.", NamedTextColor.RED)); return true; }
            openItems(player, label.equalsIgnoreCase("glow") ? "glows" : "tags");
            return true;
        }
        if (!player.hasPermission("kelvora.shardshop.use")) { player.sendMessage(Component.text("You do not have permission.", NamedTextColor.RED)); return true; }
        openCategories(player);
        return true;
    }

    private void openCategories(Player player) {
        openCategory.put(player.getUniqueId(), "categories");
        Inventory inv = Bukkit.createInventory(null, 27, Component.text(TITLE, NamedTextColor.DARK_GREEN));
        activeInventories.add(inv);
        add(inv, 10, Material.NAME_TAG, "&aTags", List.of("Decorative labels beside your name"), "category:tags");
        add(inv, 12, Material.EMERALD, "&eTemporary Ranks", List.of("Real LuckPerms ranks and permissions", "Choose a rank and duration"), "category:ranks");
        add(inv, 14, Material.GLOW_INK_SAC, "&bGlows", List.of("Cosmetic player glow colours"), "category:glows");
        add(inv, 16, Material.WRITABLE_BOOK, "&dChat Colours", List.of("Colour your message text"), "category:chat");
        add(inv, 22, Material.AMETHYST_SHARD, "&bYour Balance", List.of("✦" + KelvorasManager.format(currency.balance(player)) + " Kelvoras"), "info");
        add(inv, 26, Material.BARRIER, "&cClose", List.of(), "close");
        player.openInventory(inv);
    }

    private void openItems(Player player, String category) {
        openCategory.put(player.getUniqueId(), category);
        Inventory inv = Bukkit.createInventory(null, 54, Component.text(TITLE + " | " + categoryLabel(category), NamedTextColor.DARK_GREEN));
        activeInventories.add(inv);
        ConfigurationSection entries = plugin.getConfig().getConfigurationSection("shardshop.catalogue." + category);
        int slot = 10;
        int itemIndex = 0;
        if (entries != null) {
            for (String id : entries.getKeys(false)) {
                ConfigurationSection entry = entries.getConfigurationSection(id);
                if (entry == null) continue;
                if (category.equals("ranks")) {
                    String lower = id.toLowerCase(Locale.ROOT);
                    int column = lower.startsWith("verdant_") ? 0 : lower.startsWith("emerald_") ? 1 : 2;
                    int row = lower.endsWith("_1d") ? 0 : lower.endsWith("_3d") ? 1 : 2;
                    slot = 10 + column * 2 + row * 9;
                } else {
                    slot = 10 + itemIndex;
                    if (slot >= 44) break;
                }
                itemIndex++;
                String name = entry.getString("name", id);
                String desc = entry.getString("description", "Cosmetic");
                if (category.equals("ranks")) {
                    String rank = entry.getString("rank", "verdant");
                    String rankLabel = rank.substring(0, 1).toUpperCase(Locale.ROOT) + rank.substring(1).toLowerCase(Locale.ROOT);
                    name = rankLabel + " · " + durationLabel(entry.getLong("duration-seconds", 0));
                    desc = "Temporary " + rankLabel + " rank with LuckPerms permissions";
                }
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
        add(inv, 53, Material.BARRIER, "&cClose", List.of(), "close");
        player.openInventory(inv);
    }

    private synchronized void handleItem(Player player, String category, String id) {
        ConfigurationSection entry = plugin.getConfig().getConfigurationSection("shardshop.catalogue." + category + "." + id);
        if (entry == null) return;
        UUID uuid = player.getUniqueId();
        if (!processing.add(uuid)) return;
        try {
            if (category.equals("ranks")) {
                handleTemporaryRankPurchase(player, id, entry);
                return;
            }
            expire(uuid, category, id);
            boolean owned = owns(uuid, category, id);
            String equipped = data.getString(uuid + ".equipped." + category, "");
            if (owned) {
                if (id.equals(equipped)) {
                    data.set(uuid + ".equipped." + category, null);
                    saveData();
                    applyCosmetic(player, category, null, null);
                    plugin.refreshSidebar(player);
                    player.sendMessage(Component.text("Cosmetic unequipped.", NamedTextColor.YELLOW));
                } else {
                    data.set(uuid + ".equipped." + category, id);
                    saveData();
                    applyCosmetic(player, category, id, entry);
                    plugin.refreshSidebar(player);
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
            plugin.refreshSidebar(player);
            player.sendMessage(Component.text("Purchased and equipped " + entry.getString("name", id) + " for ✦" + KelvorasManager.format(price) + " Kelvoras.", NamedTextColor.GREEN));
            openItems(player, category);
        } finally { processing.remove(uuid); }
    }

    /**
     * Purchase-rank hierarchy used only to prevent a temporary purchase from
     * replacing a higher permanent purchase rank. Staff/media groups are
     * deliberately outside this ladder and are therefore never removed.
     *
     * Member -> Sprout -> Booster -> Verdant -> Emerald -> Sovereign -> Ascendant.
     */
    private int rankWeight(String rank) {
        return switch (rank.toLowerCase(Locale.ROOT)) {
            case "member", "default" -> 1;
            case "sprout", "i_sprout" -> 2;
            case "booster" -> 3;
            case "verdant", "vip" -> 4;
            case "emerald" -> 5;
            case "sovereign" -> 6;
            case "ascendant" -> 7;
            default -> 0;
        };
    }

    private void handleTemporaryRankPurchase(Player player, String product, ConfigurationSection entry) {
        UUID uuid = player.getUniqueId();
        String group = entry.getString("rank", "").toLowerCase(Locale.ROOT);
        long duration = entry.getLong("duration-seconds", 0L);
        double price = entry.getDouble("price", -1.0);
        if (!Set.of("verdant", "emerald", "sovereign").contains(group) || duration <= 0
                || !Double.isFinite(price) || price < 0 || !entry.getBoolean("available", false)) {
            player.sendMessage(Component.text("This rank offer is not configured correctly. No Kelvoras were charged.", NamedTextColor.RED));
            return;
        }
        LuckPerms lp;
        try { lp = LuckPermsProvider.get(); }
        catch (IllegalStateException ex) {
            player.sendMessage(Component.text("LuckPerms is unavailable. No Kelvoras were charged.", NamedTextColor.RED));
            return;
        }
        double balance = currency.balance(player);
        if (!Double.isFinite(balance) || balance + 0.000001 < price) {
            player.sendMessage(Component.text("Not enough Kelvoras! Balance: ✦" + KelvorasManager.format(balance), NamedTextColor.RED));
            return;
        }
        User user;
        try {
            user = lp.getUserManager().getUser(uuid);
            if (user == null) user = lp.getUserManager().loadUser(uuid).join();
        } catch (Exception ex) {
            player.sendMessage(Component.text("Could not load your rank data. No Kelvoras were charged.", NamedTextColor.RED));
            return;
        }
        int wantedWeight = rankWeight(group);
        for (Node node : user.getNodes()) {
            if (node instanceof InheritanceNode inherited && node.getExpiry().equals(java.time.Instant.MAX)
                    && rankWeight(inherited.getGroupName()) >= wantedWeight && rankWeight(inherited.getGroupName()) > 0) {
                player.sendMessage(Component.text("You already have this rank permanently or a higher permanent rank. No Kelvoras were charged.", NamedTextColor.YELLOW));
                return;
            }
        }
        long now = System.currentTimeMillis();
        String expiryPath = uuid + ".temporary-ranks." + group + ".expires";
        long previousExpiry = data.getLong(expiryPath, 0L);
        long expires = Math.max(now, previousExpiry) + duration * 1000L;
        if (!currency.withdraw(player, price)) {
            player.sendMessage(Component.text("Purchase failed. No Kelvoras were charged.", NamedTextColor.RED));
            return;
        }
        List<Node> oldTimedNodes = new ArrayList<>();
        try {
            for (Node node : new ArrayList<>(user.getNodes())) {
                if (node instanceof InheritanceNode inherited && inherited.getGroupName().equalsIgnoreCase(group)
                        && !node.getExpiry().equals(java.time.Instant.MAX)) {
                    oldTimedNodes.add(node);
                    user.data().remove(node);
                }
            }
            user.data().add(InheritanceNode.builder(group)
                .expiry(java.time.Duration.ofMillis(Math.max(1000L, expires - now))).build());
            lp.getUserManager().saveUser(user).join();
        } catch (Exception ex) {
            restoreTimedRankNodes(lp, uuid, group, oldTimedNodes);
            currency.deposit(player, price);
            player.sendMessage(Component.text("Rank assignment failed. Your Kelvoras were refunded.", NamedTextColor.RED));
            return;
        }
        String previousLastProduct = data.getString(uuid + ".temporary-ranks." + group + ".last-product");
        String previousEquippedRank = data.getString(uuid + ".equipped.ranks");
        Map<String, Object> previousOwned = new HashMap<>();
        Map<String, Object> previousExpired = new HashMap<>();
        data.set(expiryPath, expires);
        data.set(uuid + ".temporary-ranks." + group + ".last-product", product);
        ConfigurationSection rankOffers = plugin.getConfig().getConfigurationSection("shardshop.catalogue.ranks");
        if (rankOffers != null) {
            for (String offerId : rankOffers.getKeys(false)) {
                ConfigurationSection offer = rankOffers.getConfigurationSection(offerId);
                if (offer != null && group.equalsIgnoreCase(offer.getString("rank", ""))) {
                    String ownedPath = uuid + ".owned.ranks." + offerId;
                    String expiredPath = uuid + ".expired.ranks." + offerId;
                    previousOwned.put(offerId, data.get(ownedPath));
                    previousExpired.put(offerId, data.get(expiredPath));
                    data.set(ownedPath, expires);
                    data.set(expiredPath, null);
                }
            }
        }
        data.set(uuid + ".equipped.ranks", null);
        if (!saveData()) {
            data.set(expiryPath, previousExpiry == 0L ? null : previousExpiry);
            data.set(uuid + ".temporary-ranks." + group + ".last-product", previousLastProduct);
            if (rankOffers != null) {
                for (String offerId : rankOffers.getKeys(false)) {
                    if (previousOwned.containsKey(offerId)) data.set(uuid + ".owned.ranks." + offerId, previousOwned.get(offerId));
                    if (previousExpired.containsKey(offerId)) data.set(uuid + ".expired.ranks." + offerId, previousExpired.get(offerId));
                }
            }
            data.set(uuid + ".equipped.ranks", previousEquippedRank);
            restoreTimedRankNodes(lp, uuid, group, oldTimedNodes);
            currency.deposit(player, price);
            player.sendMessage(Component.text("Could not save the rank expiry. Your Kelvoras were refunded.", NamedTextColor.RED));
            return;
        }
        plugin.refreshSidebar(player);
        String label = group.substring(0, 1).toUpperCase(Locale.ROOT) + group.substring(1);
        player.sendMessage(Component.text("Purchased " + label + " for " + durationLabel(duration)
            + ". Rank permissions applied. Expires " + new Date(expires) + ". Paid ✦" + KelvorasManager.format(price) + ".", NamedTextColor.GREEN));
        openItems(player, "ranks");
    }

    private void restoreTimedRankNodes(LuckPerms lp, UUID uuid, String group, List<Node> oldNodes) {
        try {
            User rollback = lp.getUserManager().getUser(uuid);
            if (rollback == null) rollback = lp.getUserManager().loadUser(uuid).join();
            for (Node node : new ArrayList<>(rollback.getNodes())) {
                if (node instanceof InheritanceNode inherited && inherited.getGroupName().equalsIgnoreCase(group)
                        && !node.getExpiry().equals(java.time.Instant.MAX)) rollback.data().remove(node);
            }
            for (Node node : oldNodes) rollback.data().add(node);
            lp.getUserManager().saveUser(rollback).join();
        } catch (Exception ex) {
            plugin.getLogger().severe("Could not restore previous temporary rank for " + uuid + ": " + ex.getMessage());
        }
    }

    private void expireTemporaryRank(UUID uuid, String group) {
        try {
            LuckPerms lp = LuckPermsProvider.get();
            User user = lp.getUserManager().getUser(uuid);
            if (user == null) user = lp.getUserManager().loadUser(uuid).join();
            boolean changed = false;
            for (Node node : new ArrayList<>(user.getNodes())) {
                if (node instanceof InheritanceNode inherited && inherited.getGroupName().equalsIgnoreCase(group)
                        && !node.getExpiry().equals(java.time.Instant.MAX)) {
                    user.data().remove(node);
                    changed = true;
                }
            }
            if (changed) lp.getUserManager().saveUser(user).join();
            Player online = Bukkit.getPlayer(uuid);
            if (online != null) plugin.refreshSidebar(online);
        } catch (Exception ex) {
            plugin.getLogger().warning("Could not expire temporary rank " + group + " for " + uuid + ": " + ex.getMessage());
        }
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
        if (section != null) return safeLabel(section.getString("name", id), 16);
        return null;
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
            ConfigurationSection temporaryRanks = data.getConfigurationSection(uuidText + ".temporary-ranks");
            if (temporaryRanks != null) {
                for (String rank : temporaryRanks.getKeys(false)) {
                    long rankExpiry = temporaryRanks.getLong(rank + ".expires", 0L);
                    if (rankExpiry > 0 && rankExpiry <= System.currentTimeMillis()) {
                        expireTemporaryRank(uuid, rank);
                        data.set(uuidText + ".temporary-ranks." + rank + ".expires", null);
                        data.set(uuidText + ".temporary-ranks." + rank + ".last-product", null);
                        changed = true;
                    }
                }
            }
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
        if (seconds % 86400 == 0) {
            long days = seconds / 86400;
            return days + (days == 1 ? " Day" : " Days");
        }
        if (seconds % 3600 == 0) {
            long hours = seconds / 3600;
            return hours + (hours == 1 ? " Hour" : " Hours");
        }
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
        if (!activeInventories.contains(event.getView().getTopInventory())) return;
        String title = event.getView().title().toString();
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player) || event.getClickedInventory() != event.getView().getTopInventory()) return;
        ItemStack item = event.getCurrentItem();
        if (item == null || !item.hasItemMeta()) return;
        String action = item.getItemMeta().getPersistentDataContainer().get(actionKey, PersistentDataType.STRING);
        if (action == null) return;
        if (action.equals("close")) { player.closeInventory(); return; }
        if (action.equals("back")) { openCategories(player); return; }
        if (action.startsWith("category:")) { openItems(player, action.substring(9)); return; }
        if (action.startsWith("item:")) {
            String[] parts = action.split(":", 3);
            if (parts.length == 3) handleItem(player, parts[1], parts[2]);
        }
    }
    @EventHandler public void onDrag(InventoryDragEvent event) {
        if (activeInventories.contains(event.getView().getTopInventory())) event.setCancelled(true);
    }
    @EventHandler public void onClose(org.bukkit.event.inventory.InventoryCloseEvent event) { activeInventories.remove(event.getInventory()); }
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
