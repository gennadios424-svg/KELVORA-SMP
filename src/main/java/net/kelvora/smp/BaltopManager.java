package net.kelvora.smp;

import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.HashMap;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.Sound;
import org.bukkit.NamespacedKey;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.configuration.file.YamlConfiguration;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.model.user.User;
import java.io.File;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

final class BaltopManager implements Listener, TabCompleter {
    private static final String TITLE = "Kelvora | Money Baltop";
    private static final int PAGE_SIZE = 45;
    private final KelvoraSMP plugin;
    private final NamespacedKey actionKey;
    private final Map<UUID, Integer> openPages = new HashMap<>();
    private Object economy;
    private Class<?> economyApi;
    private List<Entry> cached = List.of();
    private long lastRefreshMillis;
    private static final DecimalFormat MONEY = new DecimalFormat("#,##0.00", DecimalFormatSymbols.getInstance(Locale.US));

    BaltopManager(KelvoraSMP plugin) {
        this.plugin = plugin;
        this.actionKey = new NamespacedKey(plugin, "baltop-action");
    }

    void register() {
        if (plugin.getCommand("baltop") == null) {
            plugin.getLogger().severe("Command /baltop is missing from plugin.yml");
            return;
        }
        plugin.getCommand("baltop").setExecutor(this::execute);
        plugin.getCommand("baltop").setTabCompleter(this);
        Bukkit.getPluginManager().registerEvents(this, plugin);
        // Refresh periodically so opening the GUI does not rescan every offline account.
        Bukkit.getScheduler().runTaskTimer(plugin, this::refreshCache, 20L, 1200L);
    }

    private boolean execute(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Use this command in-game.");
            return true;
        }
        if (args.length > 1) {
            player.sendMessage(Component.text("Usage: /baltop [page]", NamedTextColor.RED));
            return true;
        }
        int page = 0;
        if (args.length == 1) {
            try {
                page = Integer.parseInt(args[0]) - 1;
                if (page < 0) throw new NumberFormatException();
            } catch (NumberFormatException ex) {
                player.sendMessage(Component.text("Page must be a positive number.", NamedTextColor.RED));
                return true;
            }
        }
        if (!connectEconomy()) {
            player.sendMessage(Component.text("Money economy unavailable. Check that Vault and the existing Money economy provider are enabled.", NamedTextColor.RED));
            return true;
        }
        if (cached.isEmpty() || System.currentTimeMillis() - lastRefreshMillis > 120_000L) refreshCache();
        if (cached.isEmpty()) {
            player.sendMessage(Component.text("Baltop could not read any valid Money balances yet. Try again shortly.", NamedTextColor.RED));
            return true;
        }
        open(player, page);
        return true;
    }

    private boolean connectEconomy() {
        if (economy != null) return true;
        if (!Bukkit.getPluginManager().isPluginEnabled("Vault")) return false;
        try {
            Class<?> type = Class.forName("net.milkbowl.vault.economy.Economy", true,
                Bukkit.getPluginManager().getPlugin("Vault").getClass().getClassLoader());
            RegisteredServiceProvider<?> registration = Bukkit.getServicesManager().getRegistration((Class) type);
            economyApi = type;
            economy = registration == null ? null : registration.getProvider();
        } catch (ReflectiveOperationException | LinkageError ex) {
            plugin.getLogger().warning("Baltop could not load the Vault Money economy API.");
            economy = null;
        }
        return economy != null;
    }

    private void refreshCache() {
        if (!connectEconomy()) {
            cached = List.of();
            lastRefreshMillis = System.currentTimeMillis();
            return;
        }
        List<Entry> entries = new ArrayList<>();
        for (OfflinePlayer player : Bukkit.getOfflinePlayers()) {
            UUID id = player.getUniqueId();
            String name = player.getName();
            if (id == null || name == null || name.isBlank()) continue;
            try {
                Object value = economyApi.getMethod("getBalance", OfflinePlayer.class).invoke(economy, player);
                if (!(value instanceof Number number)) continue;
                double balance = number.doubleValue();
                if (!Double.isFinite(balance) || balance < 0) continue;
                entries.add(new Entry(id, name, balance));
            } catch (ReflectiveOperationException | LinkageError ex) {
                plugin.getLogger().warning("Baltop could not read balances from the Money provider.");
                cached = List.of();
                lastRefreshMillis = System.currentTimeMillis();
                return;
            }
        }
        // Stable name/UUID tie-break keeps equal-balance ranks deterministic.
        entries.sort(Comparator.comparingDouble(Entry::balance).reversed()
            .thenComparing(Entry::name, String.CASE_INSENSITIVE_ORDER)
            .thenComparing(e -> e.id().toString()));
        cached = List.copyOf(entries);
        lastRefreshMillis = System.currentTimeMillis();
    }

    private void open(Player player, int requestedPage) {
        int pages = Math.max(1, (cached.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        int page = Math.min(requestedPage, pages - 1);
        openPages.put(player.getUniqueId(), page);
        Inventory inventory = Bukkit.createInventory(null, 54,
            Component.text(TITLE + " " + (page + 1) + "/" + pages, NamedTextColor.DARK_GREEN));
        int start = page * PAGE_SIZE;
        for (int slot = 0; slot < PAGE_SIZE && start + slot < cached.size(); slot++) {
            Entry entry = cached.get(start + slot);
            int rank = start + slot + 1;
            ItemStack head = new ItemStack(Material.PLAYER_HEAD);
            SkullMeta meta = (SkullMeta) head.getItemMeta();
            meta.setOwningPlayer(Bukkit.getOfflinePlayer(entry.id()));
            net.kyori.adventure.text.format.TextColor color = rank == 1 ? NamedTextColor.GOLD
                : rank == 2 ? NamedTextColor.GRAY
                : rank == 3 ? net.kyori.adventure.text.format.TextColor.color(205, 127, 50) : NamedTextColor.GREEN;
            meta.displayName(Component.text("#" + rank + " [" + rankLabel(entry.id()) + "] " + entry.name(), color));
            meta.lore(List.of(
                Component.text("Money: " + compact(entry.balance()), NamedTextColor.WHITE),
                Component.text("Exact: $" + MONEY.format(entry.balance()), NamedTextColor.GRAY),
                Component.text(rank <= 3 ? "Top " + rank + " balance" : "Kelvora Money leaderboard", NamedTextColor.DARK_GRAY)
            ));
            head.setItemMeta(meta);
            inventory.setItem(slot, head);
        }
        control(inventory, 45, Material.ARROW, "Previous Page", "prev", page > 0 ? "Go to page " + page : "Already on first page");
        control(inventory, 49, Material.EMERALD, "Page " + (page + 1) + " / " + pages, "info", cached.size() + " ranked players • Money only");
        control(inventory, 53, Material.ARROW, "Next Page", "next", page + 1 < pages ? "Go to page " + (page + 2) : "Already on last page");
        control(inventory, 50, Material.BARRIER, "Close", "close", "Close leaderboard");
        player.openInventory(inventory);
    }

    private void control(Inventory inventory, int slot, Material material, String label, String action, String lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(label, NamedTextColor.WHITE));
        meta.lore(List.of(Component.text(lore, NamedTextColor.GRAY)));
        meta.getPersistentDataContainer().set(actionKey, PersistentDataType.STRING, action);
        item.setItemMeta(meta);
        inventory.setItem(slot, item);
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!event.getView().title().toString().contains(TITLE)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)
            || event.getClickedInventory() != event.getView().getTopInventory()) return;
        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || !clicked.hasItemMeta()) return;
        String action = clicked.getItemMeta().getPersistentDataContainer().get(actionKey, PersistentDataType.STRING);
        if (action == null) return;
        if (action.equals("close")) {
            player.closeInventory();
            return;
        }
        if (action.equals("prev") || action.equals("next")) {
            int current = openPages.getOrDefault(player.getUniqueId(), 0);
            int next = action.equals("prev") ? current - 1 : current + 1;
            int pages = Math.max(1, (cached.size() + PAGE_SIZE - 1) / PAGE_SIZE);
            if (next >= 0 && next < pages) {
                player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.18f, 1.25f);
                open(player, next);
            }
        } else {
            player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.15f, 1.1f);
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().title().toString().contains(TITLE)) event.setCancelled(true);
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length != 1) return List.of();
        int pages = Math.max(1, (cached.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        List<String> suggestions = new ArrayList<>();
        for (int i = 1; i <= pages; i++) suggestions.add(Integer.toString(i));
        String prefix = args[0].toLowerCase(Locale.ROOT);
        return suggestions.stream().filter(s -> s.startsWith(prefix)).toList();
    }

    private String compact(double amount) {
        double abs = Math.abs(amount);
        if (abs >= 1_000_000_000) return String.format(Locale.US, "$%.2fB", amount / 1_000_000_000);
        if (abs >= 1_000_000) return String.format(Locale.US, "$%.2fM", amount / 1_000_000);
        if (abs >= 1_000) return String.format(Locale.US, "$%.2fK", amount / 1_000);
        return "$" + MONEY.format(amount);
    }


    private String rankLabel(UUID id) {
        try {
            File cacheFile = new File(plugin.getDataFolder(), "rank-cache.yml");
            YamlConfiguration cache = YamlConfiguration.loadConfiguration(cacheFile);
            String cachedRank = cache.getString(id.toString());
            if (Bukkit.getPluginManager().isPluginEnabled("LuckPerms")) {
                User user = LuckPermsProvider.get().getUserManager().getUser(id);
                if (user != null && user.getPrimaryGroup() != null) {
                    String group = user.getPrimaryGroup().toLowerCase(Locale.ROOT);
                    if (group.equals("default") || group.equals("member")) group = "i_sprout";
                    if (group.equals("mod")) group = "moderator";
                    if (group.equals("vip")) group = "verdant";
                    if (group.equals("i_sprout")) return "I. Sprout";
                    if (group.equals("verdant")) return "Verdant";
                    if (group.equals("emerald")) return "Emerald";
                    if (group.equals("sovereign")) return "Sovereign";
                    if (group.equals("ascendant")) return "Ascendant";
                    if (group.equals("helper")) return "Helper";
                    if (group.equals("moderator")) return "Moderator";
                    if (group.equals("admin")) return "Admin";
                    if (group.equals("owner")) return "Owner";
                    if (group.equals("creator")) return "Creator";
                }
            }
            if (cachedRank != null) {
                return switch (cachedRank.toLowerCase(Locale.ROOT)) {
                    case "i_sprout", "member", "default" -> "I. Sprout";
                    case "verdant", "vip" -> "Verdant";
                    case "emerald" -> "Emerald";
                    case "sovereign" -> "Sovereign";
                    case "ascendant" -> "Ascendant";
                    case "helper" -> "Helper";
                    case "moderator", "mod" -> "Moderator";
                    case "admin" -> "Admin";
                    case "owner" -> "Owner";
                    case "creator" -> "Creator";
                    default -> cachedRank;
                };
            }
        } catch (Exception ignored) { }
        return "Unknown";
    }

    private record Entry(UUID id, String name, double balance) { }
}
