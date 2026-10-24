package net.kelvora.smp;

import java.io.File;
import java.text.DecimalFormat;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.OfflinePlayer;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Persistent player auction house. Uses the existing Vault Money provider only.
 */
final class AuctionHouseManager implements Listener {
    private static final String TITLE = "KELVORA // AUCTION HOUSE";
    private static final String CONFIRM = "KELVORA // CONFIRM PURCHASE";
    private static final String MY_LISTINGS = "KELVORA // MY LISTINGS";
    private static final long DURATION = 24L * 60L * 60L * 1000L;
    private static final int PAGE_SIZE = 45;
    private static final DecimalFormat EXACT = new DecimalFormat("#,##0.##");
    private final JavaPlugin plugin;
    private final NamespacedKey actionKey;
    private final File dataFile;
    private final Map<String, Listing> listings = new LinkedHashMap<>();
    private final Map<UUID, List<ItemStack>> pending = new HashMap<>();
    private final Map<UUID, ViewSession> sessions = new ConcurrentHashMap<>();
    private final Set<String> inFlight = ConcurrentHashMap.newKeySet();
    private final Set<String> reservedListings = ConcurrentHashMap.newKeySet();
    private final Set<Inventory> activeInventories = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Map<UUID, String> searchQueries = new ConcurrentHashMap<>();
    private YamlConfiguration data;
    private Object economy;

    AuctionHouseManager(JavaPlugin plugin) {
        this.plugin = plugin;
        this.actionKey = new NamespacedKey(plugin, "ah-action");
        this.dataFile = new File(plugin.getDataFolder(), "auction-house.yml");
    }

    void register() {
        loadData();
        connectEconomy();
        if (plugin.getCommand("ah") != null) {
            plugin.getCommand("ah").setExecutor((sender, command, label, args) -> {
                if (!(sender instanceof Player p)) { sender.sendMessage("Use this command in-game."); return true; }
                if (!p.hasPermission("kelvora.ah")) { p.sendMessage(Component.text("You do not have permission.", NamedTextColor.RED)); return true; }
                if (args.length == 0) { searchQueries.remove(p.getUniqueId()); openBrowse(p, 0, false); return true; }
                switch (args[0].toLowerCase(Locale.ROOT)) {
                    case "sell" -> {
                        if (args.length != 2) { msg(p, NamedTextColor.RED, "Usage: /ah sell <price>"); return true; }
                        createListing(p, args[1]);
                    }
                    case "listings" -> { searchQueries.remove(p.getUniqueId()); openBrowse(p, 0, true); }
                    case "search" -> {
                        if (args.length < 2) { msg(p, NamedTextColor.YELLOW, "Usage: /ah search <item name>"); return true; }
                        String query = String.join(" ", Arrays.copyOfRange(args, 1, args.length)).trim();
                        if (query.isBlank()) { msg(p, NamedTextColor.YELLOW, "Usage: /ah search <item name>"); return true; }
                        searchQueries.put(p.getUniqueId(), query.toLowerCase(Locale.ROOT));
                        if (visibleListings(p, false).isEmpty()) msg(p, NamedTextColor.YELLOW, "No active listings match '" + query + "'.");
                        openBrowse(p, 0, false);
                    }
                    case "cancel" -> {
                        if (args.length != 2) { msg(p, NamedTextColor.RED, "Usage: /ah cancel <listing-id>"); return true; }
                        cancelListing(p, args[1]);
                    }
                    case "help" -> {
                        msg(p, NamedTextColor.GREEN, "/ah • /ah search <query> • /ah sell <price> • /ah listings • /ah cancel <listing-id>");
                    }
                    default -> msg(p, NamedTextColor.RED, "Usage: /ah [search <query>|sell <price>|listings|cancel <id>|help]");
                }
                return true;
            });
            plugin.getCommand("ah").setTabCompleter((sender, command, alias, args) -> {
                if (args.length == 1) return starts(List.of("search", "sell", "listings", "cancel", "help"), args[0]);
                if (args.length == 2 && args[0].equalsIgnoreCase("cancel") && sender instanceof Player p) {
                    List<String> ids = new ArrayList<>();
                    for (Listing listing : listings.values()) if (listing.owner.equals(p.getUniqueId())) ids.add(listing.id);
                    return starts(ids, args[1]);
                }
                return Collections.emptyList();
            });
        }
        Bukkit.getPluginManager().registerEvents(this, plugin);
        Bukkit.getScheduler().runTask(plugin, () -> {
            connectEconomy();
            if (economy == null) plugin.getLogger().warning("Auction House disabled until Vault has a Money economy provider.");
        });
        Bukkit.getScheduler().runTaskTimer(plugin, this::expireListings, 20L, 20L * 30L);
        plugin.getLogger().info("Auction House loaded " + listings.size() + " persisted listings.");
    }

    private List<String> starts(List<String> values, String prefix) {
        String p = prefix.toLowerCase(Locale.ROOT);
        return values.stream().filter(s -> s.toLowerCase(Locale.ROOT).startsWith(p)).toList();
    }

    private void connectEconomy() {
        economy = null;
        if (!Bukkit.getPluginManager().isPluginEnabled("Vault")) return;
        try {
            Class<?> type = Class.forName("net.milkbowl.vault.economy.Economy", true,
                    Bukkit.getPluginManager().getPlugin("Vault").getClass().getClassLoader());
            RegisteredServiceProvider<?> registration = Bukkit.getServicesManager().getRegistration((Class) type);
            if (registration != null) economy = registration.getProvider();
        } catch (ReflectiveOperationException | LinkageError ex) {
            plugin.getLogger().warning("Auction House cannot access the Vault economy API.");
        }
    }

    private double balance(OfflinePlayer player) {
        try {
            Object result = economy.getClass().getMethod("getBalance", OfflinePlayer.class).invoke(economy, player);
            return result instanceof Number n ? n.doubleValue() : Double.NaN;
        } catch (ReflectiveOperationException | LinkageError ex) { return Double.NaN; }
    }

    private boolean withdraw(OfflinePlayer player, double amount) {
        try {
            Object response = economy.getClass().getMethod("withdrawPlayer", OfflinePlayer.class, double.class).invoke(economy, player, amount);
            return response != null && (boolean) response.getClass().getMethod("transactionSuccess").invoke(response);
        } catch (ReflectiveOperationException | LinkageError ex) { return false; }
    }

    private boolean deposit(OfflinePlayer player, double amount) {
        try {
            Object response = economy.getClass().getMethod("depositPlayer", OfflinePlayer.class, double.class).invoke(economy, player, amount);
            return response != null && (boolean) response.getClass().getMethod("transactionSuccess").invoke(response);
        } catch (ReflectiveOperationException | LinkageError ex) { return false; }
    }

    private void loadData() {
        if (!plugin.getDataFolder().exists()) plugin.getDataFolder().mkdirs();
        data = YamlConfiguration.loadConfiguration(dataFile);
        listings.clear();
        ConfigurationSection ls = data.getConfigurationSection("listings");
        if (ls != null) for (String id : ls.getKeys(false)) {
            try {
                ConfigurationSection s = ls.getConfigurationSection(id);
                if (s == null) continue;
                UUID owner = UUID.fromString(s.getString("owner", ""));
                ItemStack item = s.getItemStack("item");
                double price = s.getDouble("price", -1);
                long expiry = s.getLong("expiry", 0);
                if (item == null || item.getType().isAir() || !Double.isFinite(price) || price <= 0 || expiry <= 0) continue;
                listings.put(id, new Listing(id, owner, s.getString("seller", "Unknown"), item, price,
                        s.getLong("created", System.currentTimeMillis()), expiry));
            } catch (IllegalArgumentException ignored) { }
        }
        pending.clear();
        ConfigurationSection ps = data.getConfigurationSection("pending");
        if (ps != null) for (String rawUuid : ps.getKeys(false)) {
            try {
                UUID uuid = UUID.fromString(rawUuid);
                List<ItemStack> items = new ArrayList<>();
                for (Object value : ps.getList(rawUuid, Collections.emptyList())) {
                    if (value instanceof ItemStack item && !item.getType().isAir()) items.add(item.clone());
                }
                if (!items.isEmpty()) pending.put(uuid, items);
            } catch (IllegalArgumentException ignored) { }
        }
    }

    private synchronized boolean saveData() {
        YamlConfiguration out = new YamlConfiguration();
        for (Listing listing : listings.values()) {
            String path = "listings." + listing.id;
            out.set(path + ".owner", listing.owner.toString());
            out.set(path + ".seller", listing.sellerName);
            out.set(path + ".item", listing.item);
            out.set(path + ".price", listing.price);
            out.set(path + ".created", listing.createdAt);
            out.set(path + ".expiry", listing.expiresAt);
        }
        for (Map.Entry<UUID, List<ItemStack>> entry : pending.entrySet()) {
            out.set("pending." + entry.getKey(), entry.getValue());
        }
        try {
            out.save(dataFile);
            data = out;
            return true;
        } catch (Exception ex) {
            plugin.getLogger().severe("Could not save Auction House data: " + ex.getMessage());
            return false;
        }
    }

    private void createListing(Player p, String rawPrice) {
        if (economy == null) connectEconomy();
        if (economy == null) { msg(p, NamedTextColor.RED, "Money is unavailable right now. Try again later."); return; }
        final double price;
        try {
            price = Phase32Commands.parseAmount(rawPrice);
            if (!Double.isFinite(price) || price <= 0 || price > 1_000_000_000_000d) throw new IllegalArgumentException();
        } catch (IllegalArgumentException ex) { msg(p, NamedTextColor.RED, "Enter a valid price greater than $0. Examples: 1000, 10k, 1.5m, 2b."); return; }
        ItemStack hand = p.getInventory().getItemInMainHand();
        if (hand.getType().isAir() || !listable(hand)) { msg(p, NamedTextColor.RED, "That item cannot be listed."); return; }
        ItemStack listed = hand.clone();
        String id = UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);
        Listing listing = new Listing(id, p.getUniqueId(), p.getName(), listed, price, System.currentTimeMillis(), System.currentTimeMillis() + DURATION);
        listings.put(id, listing);
        if (!saveData()) {
            listings.remove(id);
            msg(p, NamedTextColor.RED, "Could not save the listing. Your item was not taken.");
            return;
        }
        // Remove the exact stack only after its listing has been written to persistent storage.
        p.getInventory().setItemInMainHand(null);
        p.updateInventory();
        msg(p, NamedTextColor.GREEN, "Listed " + itemName(listed) + " for $" + exact(price) + " • ID " + id + " • 24h.");
        p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 0.2f, 1.2f);
    }

    private boolean listable(ItemStack item) {
        Material m = item.getType();
        if (!m.isItem() || m.isAir() || m == Material.BARRIER || m == Material.STRUCTURE_VOID ||
                m == Material.COMMAND_BLOCK || m == Material.CHAIN_COMMAND_BLOCK || m == Material.REPEATING_COMMAND_BLOCK ||
                m == Material.COMMAND_BLOCK_MINECART || m == Material.DEBUG_STICK || m == Material.KNOWLEDGE_BOOK ||
                m == Material.STRUCTURE_BLOCK || m == Material.JIGSAW || m.name().endsWith("_SPAWN_EGG")) return false;
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            var pdc = meta.getPersistentDataContainer();
            NamespacedKey toolIdKey = new NamespacedKey(plugin, "kelvora-tool-id");
            NamespacedKey expiryKey = new NamespacedKey(plugin, "kelvora-tool-expires");
            String toolId = pdc.get(toolIdKey, PersistentDataType.STRING);
            if (toolId != null) {
                long expiresAt = pdc.getOrDefault(expiryKey, PersistentDataType.LONG, 0L);
                if (expiresAt <= System.currentTimeMillis()) return false;
                Set<String> allowedToolKeys = Set.of("kelvora-tool-id", "kelvora-tool-created", "kelvora-tool-expires",
                    "kelvora-gainer-progress", "kelvora-gainer-target");
                for (NamespacedKey key : pdc.getKeys()) {
                    if (key.getNamespace().equalsIgnoreCase(plugin.getName().toLowerCase(Locale.ROOT))
                            && !allowedToolKeys.contains(key.getKey())) return false;
                }
                return true;
            }
            for (NamespacedKey key : pdc.getKeys()) {
                if (key.getNamespace().equalsIgnoreCase(plugin.getName().toLowerCase(Locale.ROOT))) return false;
            }
        }
        return true;
    }

    private void openBrowse(Player p, int requestedPage, boolean ownOnly) {
        List<Listing> visible = visibleListings(p, ownOnly);
        int pages = Math.max(1, (visible.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        int page = Math.max(0, Math.min(requestedPage, pages - 1));
        Inventory inv = Bukkit.createInventory(null, 54, Component.text(ownOnly ? MY_LISTINGS : TITLE, NamedTextColor.DARK_GREEN));
        activeInventories.add(inv);
        ViewSession session = new ViewSession(ownOnly, page, visible.stream().map(l -> l.id).toList());
        sessions.put(p.getUniqueId(), session);
        for (int i = 0; i < PAGE_SIZE; i++) {
            int index = page * PAGE_SIZE + i;
            if (index >= visible.size()) break;
            Listing l = visible.get(index);
            inv.setItem(i, listingDisplay(l, p, ownOnly));
        }
        put(inv, 45, Material.ARROW, "Previous Page", "previous", page > 0 ? "Page " + page : "First page");
        put(inv, 47, Material.SUNFLOWER, "Refresh", "refresh", " ");
        put(inv, 49, Material.PAPER, "Page " + (page + 1) + "/" + pages, "noop", visible.size() + " listings");
        put(inv, 51, Material.SPECTRAL_ARROW, "Next Page", "next", page + 1 < pages ? "Page " + (page + 2) : "Last page");
        put(inv, 53, Material.BARRIER, "Close", "close", " ");
        p.openInventory(inv);

    }

    private List<Listing> visibleListings(Player p, boolean ownOnly) {
        long now = System.currentTimeMillis();
        String query = searchQueries.getOrDefault(p.getUniqueId(), "");
        return listings.values().stream().filter(l -> l.expiresAt > now)
                .filter(l -> query.isBlank() || matchesSearch(l, query))
                .filter(l -> !reservedListings.contains(l.id))
                .filter(l -> !ownOnly || l.owner.equals(p.getUniqueId()))
                .sorted(Comparator.comparingLong(l -> l.createdAt))
                .toList();
    }

    private boolean matchesSearch(Listing listing, String query) {
        String q = query.toLowerCase(Locale.ROOT);
        if (listing.item.getType().name().toLowerCase(Locale.ROOT).contains(q)) return true;
        ItemMeta meta = listing.item.getItemMeta();
        if (meta != null && meta.hasDisplayName() && plain(meta.getDisplayName()).toLowerCase(Locale.ROOT).contains(q)) return true;
        String label = listing.item.getType().translationKey().replace("item.minecraft.", "").replace("block.minecraft.", "").replace('_', ' ');
        return label.toLowerCase(Locale.ROOT).contains(q);
    }

    private ItemStack listingDisplay(Listing l, Player viewer, boolean ownOnly) {
        ItemStack icon = l.item.clone();
        ItemMeta meta = icon.getItemMeta();
        if (meta != null) {
            List<Component> lore = new ArrayList<>();
            lore.add(Component.text("$" + compact(l.price), NamedTextColor.GREEN));
            lore.add(Component.text("Seller: " + l.sellerName, NamedTextColor.GRAY));
            lore.add(Component.text("Quantity: " + l.item.getAmount(), NamedTextColor.GRAY));
            if (l.owner.equals(viewer.getUniqueId())) lore.add(Component.text("Your listing · Click to manage", NamedTextColor.YELLOW));
            else lore.add(Component.text("Click to Buy", NamedTextColor.YELLOW));
            meta.lore(lore);
            icon.setItemMeta(meta);
        }
        return icon;
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player p)) return;
        String title = plain(e.getView().getTitle());
        if (!activeInventories.contains(e.getView().getTopInventory())) return;
        e.setCancelled(true);
        if (e.getClickedInventory() == null || e.getClickedInventory() != e.getView().getTopInventory()) return;
        ItemStack clicked = e.getCurrentItem();
        if (clicked == null || clicked.getType().isAir()) return;
        String action = clicked.getItemMeta() == null ? null : clicked.getItemMeta().getPersistentDataContainer().get(actionKey, PersistentDataType.STRING);
        if (action == null) {
            if (e.getRawSlot() >= 0 && e.getRawSlot() < PAGE_SIZE) {
                ViewSession session = sessions.get(p.getUniqueId());
                if (session == null || e.getRawSlot() >= session.ids.size()) return;
                String id = session.ids.get(session.page * PAGE_SIZE + e.getRawSlot());
                Listing l = listings.get(id);
                if (l != null && l.expiresAt > System.currentTimeMillis()) {
                    p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, .18f, 1.15f);
                    openConfirm(p, l, session.ownOnly);
                }
            }
            return;
        }
        if (title.equals(CONFIRM)) {
            String[] parts = action.split(":", 2);
            if (parts.length != 2) return;
            Listing listing = listings.get(parts[1]);
            if (listing == null) { msg(p, NamedTextColor.RED, "That listing is no longer available."); openBrowse(p, 0, false); return; }
            if (parts[0].equals("buy")) purchase(p, listing);
            else if (parts[0].equals("cancel")) { cancelListing(p, listing.id); p.closeInventory(); }
            else if (parts[0].equals("back")) {
                ViewSession previous = sessions.get(p.getUniqueId());
                openBrowse(p, previous == null ? 0 : previous.page, previous != null && previous.ownOnly);
            }
            return;
        }
        ViewSession session = sessions.get(p.getUniqueId());
        boolean own = title.equals(MY_LISTINGS);
        int page = session == null ? 0 : session.page;
        switch (action) {
            case "previous" -> { p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, .18f, 1.15f); openBrowse(p, page - 1, own); }
            case "next" -> { p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, .18f, 1.1f); openBrowse(p, page + 1, own); }
            case "refresh" -> { expireListings(); openBrowse(p, page, own); p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, .18f, 1.2f); }
            case "close" -> p.closeInventory();
            default -> { }
        }
    }

    @EventHandler
    public void onInventoryClose(org.bukkit.event.inventory.InventoryCloseEvent e) { activeInventories.remove(e.getInventory()); }

    @EventHandler
    public void onInventoryDrag(InventoryDragEvent e) {
        String title = plain(e.getView().getTitle());
        if (activeInventories.contains(e.getView().getTopInventory())) e.setCancelled(true);
    }

    private void openConfirm(Player p, Listing l, boolean ownOnly) {
        if (l.expiresAt <= System.currentTimeMillis()) { expireListings(); msg(p, NamedTextColor.RED, "That listing has expired."); return; }
        Inventory inv = Bukkit.createInventory(null, 27, Component.text(CONFIRM, NamedTextColor.DARK_GREEN));
        activeInventories.add(inv);
        boolean ownListing = ownOnly && l.owner.equals(p.getUniqueId());
        inv.setItem(4, listingDisplay(l, p, ownOnly));
        if (ownListing) {
            put(inv, 11, Material.NAME_TAG, "Manage Listing", "noop", "Your active listing");
            put(inv, 15, Material.BARRIER, "Cancel Listing", "cancel:" + l.id, "Return the listed item");
        } else {
            put(inv, 11, Material.LIME_DYE, "Buy", "buy:" + l.id, "$" + exact(l.price));
            put(inv, 15, Material.BARRIER, "Back", "back:" + l.id, "Return to listings");
        }
        p.openInventory(inv);

    }

    private void purchase(Player buyer, Listing listing) {
        String id = listing.id;
        if (!inFlight.add(id)) { msg(buyer, NamedTextColor.RED, "This listing is being purchased. Try again."); return; }
        try {
            Listing current = listings.get(id);
            if (current == null || current.expiresAt <= System.currentTimeMillis() || reservedListings.contains(id)) {
                msg(buyer, NamedTextColor.RED, "That listing is no longer available.");
                expireListings();
                openBrowse(buyer, 0, false);
                return;
            }
            if (!listable(current.item)) {
                listings.remove(id);
                queueDelivery(current.owner, List.of(current.item.clone()));
                saveData();
                msg(buyer, NamedTextColor.RED, "This special tool expired or became invalid while listed; it was returned to the seller.");
                openBrowse(buyer, 0, false);
                return;
            }
            if (current.owner.equals(buyer.getUniqueId())) { msg(buyer, NamedTextColor.RED, "You cannot buy your own listing."); return; }
            if (economy == null) connectEconomy();
            if (economy == null) { msg(buyer, NamedTextColor.RED, "Money is unavailable right now."); return; }
            double funds = balance(buyer);
            if (!Double.isFinite(funds) || funds < current.price) {
                msg(buyer, NamedTextColor.RED, "Not enough Money. Price: $" + exact(current.price));
                buyer.playSound(buyer.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, .18f, .8f);
                return;
            }
            if (!canFit(buyer, current.item)) {
                msg(buyer, NamedTextColor.RED, "Your inventory is full.");
                buyer.playSound(buyer.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, .18f, .8f);
                return;
            }
            // Persist reservation before money moves so another buyer cannot claim this listing.
            reservedListings.add(id);
            saveData();
            if (!withdraw(buyer, current.price)) {
                reservedListings.remove(id);
                msg(buyer, NamedTextColor.RED, "Payment failed. You have not received the item.");
                buyer.playSound(buyer.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, .18f, .8f);
                return;
            }
            OfflinePlayer seller = Bukkit.getOfflinePlayer(current.owner);
            if (!deposit(seller, current.price)) {
                boolean refunded = deposit(buyer, current.price);
                reservedListings.remove(id);
                saveData();
                msg(buyer, NamedTextColor.RED, refunded ? "Seller payment failed; your Money was refunded." : "Transaction needs staff review. Contact staff immediately.");
                return;
            }
            // Seller is paid through Vault even when offline. Remove listing only after both economy calls succeed.
            listings.remove(id);
            reservedListings.remove(id);
            saveData();
            HashMap<Integer, ItemStack> leftover = buyer.getInventory().addItem(current.item.clone());
            if (!leftover.isEmpty()) {
                // Capacity was checked, but recover safely if an inventory implementation behaves unexpectedly.
                queueDelivery(buyer.getUniqueId(), new ArrayList<>(leftover.values()));
            }
            msg(buyer, NamedTextColor.GREEN, "Purchased " + itemName(current.item) + " for $" + exact(current.price) + ".");
            buyer.playSound(buyer.getLocation(), Sound.UI_TOAST_IN, .25f, 1.15f);
            Player onlineSeller = Bukkit.getPlayer(current.owner);
            if (onlineSeller != null) {
                msg(onlineSeller, NamedTextColor.GREEN, buyer.getName() + " bought " + itemName(current.item) + " for $" + exact(current.price) + ".");
                onlineSeller.playSound(onlineSeller.getLocation(), Sound.UI_BUTTON_CLICK, .2f, 1.2f);
            }
            buyer.closeInventory();
        } finally {
            inFlight.remove(id);
        }
    }

    private boolean canFit(Player player, ItemStack item) {
        int remaining = item.getAmount();
        for (ItemStack slot : player.getInventory().getStorageContents()) {
            if (slot == null || slot.getType().isAir()) remaining -= Math.min(remaining, item.getMaxStackSize());
            else if (slot.isSimilar(item)) remaining -= Math.max(0, slot.getMaxStackSize() - slot.getAmount());
            if (remaining <= 0) return true;
        }
        return false;
    }

    private void cancelListing(Player p, String id) {
        Listing l = listings.get(id.toUpperCase(Locale.ROOT));
        if (l == null) { msg(p, NamedTextColor.RED, "Listing not found. Use /ah listings to view your active listings."); return; }
        if (!l.owner.equals(p.getUniqueId())) { msg(p, NamedTextColor.RED, "You can only cancel your own listings."); return; }
        if (inFlight.contains(l.id) || !reservedListings.add(l.id)) { msg(p, NamedTextColor.RED, "That listing is currently being processed."); return; }
        try {
            listings.remove(l.id);
            queueDelivery(l.owner, List.of(l.item.clone()));
            saveData();
            deliverPending(p);
            msg(p, NamedTextColor.GREEN, "Listing " + l.id + " cancelled; item returned.");
            p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, .2f, 1.1f);
        } finally { reservedListings.remove(l.id); }
    }

    private void expireListings() {
        long now = System.currentTimeMillis();
        List<Listing> expired = listings.values().stream().filter(l -> l.expiresAt <= now && !inFlight.contains(l.id)).toList();
        if (expired.isEmpty()) return;
        for (Listing l : expired) {
            listings.remove(l.id);
            queueDelivery(l.owner, List.of(l.item.clone()));
            Player owner = Bukkit.getPlayer(l.owner);
            if (owner != null) {
                deliverPending(owner);
                msg(owner, NamedTextColor.YELLOW, "Your auction " + l.id + " expired; the item has been returned.");
            }
        }
        saveData();
    }

    private void queueDelivery(UUID uuid, List<ItemStack> items) {
        List<ItemStack> target = pending.computeIfAbsent(uuid, ignored -> new ArrayList<>());
        for (ItemStack item : items) if (item != null && !item.getType().isAir()) target.add(item.clone());
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Bukkit.getScheduler().runTask(plugin, () -> deliverPending(event.getPlayer()));
    }

    private void deliverPending(Player player) {
        List<ItemStack> items = pending.get(player.getUniqueId());
        if (items == null || items.isEmpty()) return;
        List<ItemStack> remaining = new ArrayList<>();
        for (ItemStack item : items) {
            Map<Integer, ItemStack> leftovers = player.getInventory().addItem(item.clone());
            remaining.addAll(leftovers.values());
        }
        if (remaining.isEmpty()) pending.remove(player.getUniqueId());
        else pending.put(player.getUniqueId(), remaining);
        saveData();
        if (remaining.isEmpty()) msg(player, NamedTextColor.GREEN, "Returned auction items have been delivered.");
        else msg(player, NamedTextColor.YELLOW, "Some returned auction items are pending because your inventory is full.");
    }

    private void put(Inventory inv, int slot, Material material, String name, String action, String lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(name, material == Material.BARRIER ? NamedTextColor.RED : NamedTextColor.GREEN));
        if (lore != null && !lore.isBlank() && !lore.equals(" ")) meta.lore(List.of(Component.text(lore, NamedTextColor.GRAY)));
        meta.getPersistentDataContainer().set(actionKey, PersistentDataType.STRING, action);
        item.setItemMeta(meta);
        inv.setItem(slot, item);
    }

    private String remaining(long expiry) {
        long millis = Math.max(0, expiry - System.currentTimeMillis());
        long hours = millis / 3_600_000L;
        long minutes = (millis % 3_600_000L) / 60_000L;
        return hours + "h " + minutes + "m";
    }

    private String roman(int level) {
        if (level <= 0) return Integer.toString(level);
        String[] numerals = {"X", "IX", "V", "IV", "I"};
        int[] values = {10, 9, 5, 4, 1};
        StringBuilder result = new StringBuilder();
        int remaining = level;
        for (int i = 0; i < values.length; i++) while (remaining >= values[i]) {
            result.append(numerals[i]); remaining -= values[i];
        }
        return result.toString();
    }

    private String compact(double value) {
        if (value >= 1_000_000_000) return trim(value / 1_000_000_000) + "B";
        if (value >= 1_000_000) return trim(value / 1_000_000) + "M";
        if (value >= 1_000) return trim(value / 1_000) + "K";
        return exact(value);
    }

    private String trim(double value) { return new DecimalFormat("#,##0.##").format(value); }
    private String exact(double value) { return EXACT.format(value); }
    private String itemName(ItemStack item) {
        ItemMeta meta = item.getItemMeta();
        if (meta != null && meta.hasDisplayName()) return plain(meta.getDisplayName());
        String[] parts = item.getType().name().toLowerCase(Locale.ROOT).split("_");
        StringJoiner out = new StringJoiner(" ");
        for (String part : parts) out.add(part.substring(0, 1).toUpperCase(Locale.ROOT) + part.substring(1));
        return out.toString();
    }
    private String plain(String input) { return input.replaceAll("§[0-9A-FK-ORa-fk-or]", "").replaceAll("<[^>]*>", ""); }
    private void msg(Player p, NamedTextColor color, String text) { p.sendMessage(Component.text(text, color)); }

    private record Listing(String id, UUID owner, String sellerName, ItemStack item, double price, long createdAt, long expiresAt) {}
    private record ViewSession(boolean ownOnly, int page, List<String> ids) {}
}
