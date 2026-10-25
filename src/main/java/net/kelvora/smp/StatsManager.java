package net.kelvora.smp;

import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.*;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.*;
import org.bukkit.command.*;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

final class StatsManager implements Listener, CommandExecutor, TabCompleter {
    private static final String TITLE_PREFIX = "KELVORA | STATS: ";
    private final JavaPlugin plugin;
    private final KelvorasManager kelvoras;
    private final DecimalFormat fmt = new DecimalFormat("#,##0.##", DecimalFormatSymbols.getInstance(Locale.US));

    StatsManager(JavaPlugin plugin, KelvorasManager k) { this.plugin = plugin; this.kelvoras = k; }

    void register(JavaPlugin plugin) {
        if (plugin.getCommand("stats") != null) {
            plugin.getCommand("stats").setExecutor(this);
            plugin.getCommand("stats").setTabCompleter(this);
        }
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length > 1) { sender.sendMessage(Component.text("Usage: /stats [player]", NamedTextColor.RED)); return true; }
        OfflinePlayer target;
        if (args.length == 0) {
            if (!(sender instanceof Player player)) { sender.sendMessage("Usage: /stats <player>"); return true; }
            target = player;
        } else {
            target = Bukkit.getOfflinePlayer(args[0]);
            if (!target.isOnline() && !target.hasPlayedBefore()) {
                sender.sendMessage(Component.text("No saved data found for that player.", NamedTextColor.RED)); return true;
            }
        }
        if (!(sender instanceof Player viewer)) {
            sender.sendMessage("Stats GUI can only be opened in-game."); return true;
        }
        open(viewer, target);
        return true;
    }

    private void open(Player viewer, OfflinePlayer target) {
        String name = target.getName() == null ? "Unknown" : target.getName();
        Inventory inv = Bukkit.createInventory(null, 27, TITLE_PREFIX + name);
        ItemStack pane = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta paneMeta = pane.getItemMeta();
        paneMeta.displayName(Component.text(" "));
        pane.setItemMeta(paneMeta);
        for (int i = 0; i < inv.getSize(); i++) inv.setItem(i, pane.clone());
        put(inv, 4, Material.PLAYER_HEAD, "✦ " + name, List.of("Rank: " + rank(target), "Public player statistics"), NamedTextColor.GREEN);
        long minutes = Math.max(0, stat(target, Statistic.PLAY_ONE_MINUTE)) / 1200;
        put(inv, 10, Material.IRON_SWORD, "Kills", List.of(fmt.format(stat(target, Statistic.PLAYER_KILLS))), NamedTextColor.RED);
        put(inv, 11, Material.SKELETON_SKULL, "Deaths", List.of(fmt.format(stat(target, Statistic.DEATHS))), NamedTextColor.DARK_RED);
        put(inv, 12, Material.CLOCK, "Playtime", List.of((minutes / 1440) + "d " + ((minutes % 1440) / 60) + "h " + (minutes % 60) + "m"), NamedTextColor.AQUA);
        double money = money(target);
        put(inv, 13, Material.GOLD_INGOT, "Money", List.of(Double.isFinite(money) ? "$" + fmt.format(money) : "Economy unavailable"), NamedTextColor.GOLD);
        put(inv, 14, Material.EMERALD, "Kelvoras", List.of("✦" + fmt.format(kelvoras.balance(target))), NamedTextColor.GREEN);
        put(inv, 15, Material.IRON_PICKAXE, "Blocks Mined", List.of(fmt.format(blocks(target))), NamedTextColor.YELLOW);
        put(inv, 16, Material.BONE, "Mobs Killed", List.of(fmt.format(mobs(target))), NamedTextColor.GRAY);
        put(inv, 22, Material.BARRIER, "Close", List.of("Click to close"), NamedTextColor.RED);
        viewer.openInventory(inv);
    }

    private void put(Inventory inv, int slot, Material type, String name, List<String> lore, NamedTextColor color) {
        ItemStack item = new ItemStack(type);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(name, color));
        meta.lore(lore.stream().map(line -> Component.text(line, NamedTextColor.GRAY)).toList());
        item.setItemMeta(meta);
        inv.setItem(slot, item);
    }

    @EventHandler(priority = EventPriority.HIGHEST) public void click(InventoryClickEvent event) {
        if (event.getView().getTitle() == null || !event.getView().getTitle().startsWith(TITLE_PREFIX)) return;
        event.setCancelled(true);
        if (event.getClickedInventory() == event.getView().getTopInventory() && event.getRawSlot() == 22) event.getWhoClicked().closeInventory();
    }

    @EventHandler(priority = EventPriority.HIGHEST) public void drag(InventoryDragEvent event) {
        if (event.getView().getTitle() != null && event.getView().getTitle().startsWith(TITLE_PREFIX)) event.setCancelled(true);
    }

    private int stat(OfflinePlayer player, Statistic statistic) {
        try { return Math.max(0, player.getStatistic(statistic)); } catch (Exception ex) { return 0; }
    }
    private long blocks(OfflinePlayer player) {
        long total = 0;
        for (Material material : Material.values()) if (material.isBlock()) try { total += Math.max(0, player.getStatistic(Statistic.MINE_BLOCK, material)); } catch (Exception ignored) {}
        return total;
    }
    private long mobs(OfflinePlayer player) {
        long total = 0;
        for (EntityType type : EntityType.values()) try { total += Math.max(0, player.getStatistic(Statistic.KILL_ENTITY, type)); } catch (Exception ignored) {}
        return total;
    }
    private double money(OfflinePlayer player) {
        try {
            Class<?> type = Class.forName("net.milkbowl.vault.economy.Economy");
            var registration = Bukkit.getServicesManager().getRegistration((Class) type);
            if (registration == null) return Double.NaN;
            Object value = registration.getProvider().getClass().getMethod("getBalance", OfflinePlayer.class).invoke(registration.getProvider(), player);
            return value instanceof Number n ? n.doubleValue() : Double.NaN;
        } catch (Exception ex) { return Double.NaN; }
    }
    private String rank(OfflinePlayer player) {
        try {
            Class<?> provider = Class.forName("net.luckperms.api.LuckPermsProvider");
            Object lp = provider.getMethod("get").invoke(null);
            Object manager = lp.getClass().getMethod("getUserManager").invoke(lp);
            Object user = manager.getClass().getMethod("getUser", UUID.class).invoke(manager, player.getUniqueId());
            if (user != null) {
                String group = String.valueOf(user.getClass().getMethod("getPrimaryGroup").invoke(user));
                return group.substring(0, 1).toUpperCase(Locale.ROOT) + group.substring(1);
            }
        } catch (Exception ignored) {}
        return "Sprout";
    }
    @Override public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) return Bukkit.getOnlinePlayers().stream().map(Player::getName).filter(n -> n.toLowerCase(Locale.ROOT).startsWith(args[0].toLowerCase(Locale.ROOT))).toList();
        return List.of();
    }
}
