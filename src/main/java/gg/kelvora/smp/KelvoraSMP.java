package gg.kelvora.smp;

import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
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
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

import java.io.File;
import java.io.IOException;
import java.text.DecimalFormat;
import java.util.*;

public final class KelvoraSMP extends JavaPlugin implements Listener {
    private final Map<UUID, Double> balances = new HashMap<>();
    private final DecimalFormat money = new DecimalFormat("#,##0.##");
    private File balanceFile;
    private YamlConfiguration balanceData;
    private static final String SELL_MARKER = ChatColor.DARK_GREEN + "" + ChatColor.BOLD + "KELVORA " + ChatColor.DARK_GRAY + "» " + ChatColor.GREEN + "Sell Items";
    private static final String WORTH_MARKER = ChatColor.DARK_GREEN + "" + ChatColor.BOLD + "KELVORA " + ChatColor.DARK_GRAY + "» " + ChatColor.GREEN + "Item Worth";

    @Override public void onEnable() {
        saveDefaultConfig();
        loadBalances();
        Bukkit.getPluginManager().registerEvents(this, this);
        for (String command : List.of("sell", "worth", "bal", "pay")) {
            if (getCommand(command) != null) getCommand(command).setExecutor(this);
        }
        Bukkit.getScheduler().runTaskTimer(this, () -> {
            for (Player player : Bukkit.getOnlinePlayers()) updatePlayerDisplay(player);
        }, 20L, 40L);
        getLogger().info("Kelvora SMP 3.0.0 enabled.");
    }

    @Override public void onDisable() { saveBalances(); }

    private void loadBalances() {
        if (!getDataFolder().exists()) getDataFolder().mkdirs();
        balanceFile = new File(getDataFolder(), "balances.yml");
        balanceData = YamlConfiguration.loadConfiguration(balanceFile);
        ConfigurationSection section = balanceData.getConfigurationSection("balances");
        if (section != null) for (String key : section.getKeys(false)) {
            try { balances.put(UUID.fromString(key), Math.max(0, section.getDouble(key))); }
            catch (IllegalArgumentException ignored) { getLogger().warning("Ignoring invalid balance UUID: " + key); }
        }
    }
    private void saveBalances() {
        if (balanceData == null || balanceFile == null) return;
        for (Map.Entry<UUID, Double> entry : balances.entrySet())
            balanceData.set("balances." + entry.getKey(), entry.getValue());
        try { balanceData.save(balanceFile); }
        catch (IOException e) { getLogger().severe("Could not save balances.yml: " + e.getMessage()); }
    }
    private double balance(UUID id) { return balances.getOrDefault(id, 0.0); }
    private void setBalance(UUID id, double value) { balances.put(id, Math.max(0, value)); }

    private Map<Material, Double> prices() {
        Map<Material, Double> result = new LinkedHashMap<>();
        ConfigurationSection section = getConfig().getConfigurationSection("prices");
        if (section != null) for (String key : section.getKeys(false)) {
            Material material = Material.matchMaterial(key);
            double price = section.getDouble(key, -1);
            if (material != null && material.isItem() && price >= 0 && Double.isFinite(price)) result.put(material, price);
        }
        return result;
    }

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String name = command.getName().toLowerCase(Locale.ROOT);
        if (name.equals("sell")) {
            if (!(sender instanceof Player player)) { sender.sendMessage("This command can only be used in-game."); return true; }
            openSell(player); return true;
        }
        if (name.equals("worth")) {
            if (!(sender instanceof Player player)) { sender.sendMessage("This command can only be used in-game."); return true; }
            openWorth(player); return true;
        }
        if (name.equals("bal")) {
            if (args.length == 0) {
                if (!(sender instanceof Player player)) { sender.sendMessage("Usage: /bal <player>"); return true; }
                sender.sendMessage(ChatColor.GREEN + "Your balance: " + ChatColor.WHITE + "$" + money.format(balance(player.getUniqueId())));
                return true;
            }
            Player target = Bukkit.getPlayerExact(args[0]);
            if (target != null) { sender.sendMessage(ChatColor.GREEN + target.getName() + "'s balance: $" + money.format(balance(target.getUniqueId()))); return true; }
            OfflinePlayer offline = Bukkit.getOfflinePlayer(args[0]);
            if (!offline.hasPlayedBefore()) { sender.sendMessage(ChatColor.RED + "That player has never joined."); return true; }
            sender.sendMessage(ChatColor.GREEN + offline.getName() + "'s balance: $" + money.format(balance(offline.getUniqueId())));
            return true;
        }
        if (name.equals("pay")) {
            if (!(sender instanceof Player player)) { sender.sendMessage("This command can only be used in-game."); return true; }
            if (args.length != 2) { player.sendMessage(ChatColor.RED + "Usage: /pay <player> <amount>"); return true; }
            Player target = Bukkit.getPlayerExact(args[0]);
            if (target == null || target.equals(player)) { player.sendMessage(ChatColor.RED + "Choose another player who is online."); return true; }
            double amount;
            try { amount = Double.parseDouble(args[1]); }
            catch (NumberFormatException e) { player.sendMessage(ChatColor.RED + "Enter a valid amount."); return true; }
            if (!Double.isFinite(amount) || amount <= 0 || amount > 1_000_000_000_000d) { player.sendMessage(ChatColor.RED + "Amount must be greater than zero."); return true; }
            if (balance(player.getUniqueId()) < amount) { player.sendMessage(ChatColor.RED + "You do not have enough money."); return true; }
            setBalance(player.getUniqueId(), balance(player.getUniqueId()) - amount);
            setBalance(target.getUniqueId(), balance(target.getUniqueId()) + amount);
            saveBalances();
            player.sendMessage(ChatColor.GREEN + "You paid " + target.getName() + " $" + money.format(amount) + ".");
            target.sendMessage(ChatColor.GREEN + "You received $" + money.format(amount) + " from " + player.getName() + ".");
            return true;
        }
        return false;
    }

    private ItemStack named(Material material, String title, List<String> lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) { meta.setDisplayName(title); meta.setLore(lore); item.setItemMeta(meta); }
        return item;
    }
    private void openSell(Player player) {
        Inventory inv = Bukkit.createInventory(null, 54, SELL_MARKER);
        Map<Material, Double> prices = prices();
        int slot = 0;
        for (Map.Entry<Material, Double> entry : prices.entrySet()) {
            int count = count(player, entry.getKey());
            if (count < 1 || slot >= 45) continue;
            ItemStack icon = new ItemStack(entry.getKey(), Math.min(64, count));
            ItemMeta meta = icon.getItemMeta();
            if (meta != null) { meta.setDisplayName(ChatColor.GREEN + prettify(entry.getKey()));
                meta.setLore(List.of(ChatColor.GRAY + "In inventory: " + count, ChatColor.GRAY + "Price each: $" + money.format(entry.getValue()), ChatColor.YELLOW + "Click to sell all of this item")); icon.setItemMeta(meta); }
            inv.setItem(slot++, icon);
        }
        inv.setItem(49, named(Material.EMERALD, ChatColor.GREEN + "Sell GUI", List.of(ChatColor.GRAY + "Click an item to sell all of that type.", ChatColor.GRAY + "Only items with configured prices can be sold.")));
        player.openInventory(inv);
    }
    private void openWorth(Player player) {
        Inventory inv = Bukkit.createInventory(null, 54, WORTH_MARKER);
        int slot = 0;
        for (Map.Entry<Material, Double> entry : prices().entrySet()) {
            if (slot >= 45) break;
            inv.setItem(slot++, named(entry.getKey(), ChatColor.GREEN + prettify(entry.getKey()),
                List.of(ChatColor.GRAY + "Sell price: $" + money.format(entry.getValue()))));
        }
        inv.setItem(49, named(Material.BOOK, ChatColor.GREEN + "Item Worth", List.of(ChatColor.GRAY + "Prices are per item.")));
        player.openInventory(inv);
    }
    private int count(Player player, Material material) {
        int total = 0;
        for (ItemStack item : player.getInventory().getStorageContents())
            if (item != null && item.getType() == material) total += item.getAmount();
        return total;
    }
    private String prettify(Material material) {
        String[] parts = material.name().toLowerCase(Locale.ROOT).split("_");
        StringJoiner joiner = new StringJoiner(" ");
        for (String part : parts) joiner.add(Character.toUpperCase(part.charAt(0)) + part.substring(1));
        return joiner.toString();
    }

    @EventHandler public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        String title = event.getView().getTitle();
        if (!title.equals(SELL_MARKER) && !title.equals(WORTH_MARKER)) return;
        event.setCancelled(true);
        if (event.getClickedInventory() == null || event.getClickedInventory() != event.getView().getTopInventory()) return;
        int slot = event.getRawSlot();
        if (slot < 0 || slot >= 45) return;
        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || clicked.getType().isAir()) return;
        if (title.equals(WORTH_MARKER)) return;
        Material material = clicked.getType();
        Double price = prices().get(material);
        if (price == null) return;
        int sold = 0;
        for (int i = 0; i < 36; i++) {
            ItemStack item = player.getInventory().getItem(i);
            if (item == null || item.getType() != material) continue;
            sold += item.getAmount();
            player.getInventory().setItem(i, null);
        }
        if (sold == 0) { player.sendMessage(ChatColor.RED + "You no longer have that item."); player.closeInventory(); return; }
        double earned = sold * price;
        setBalance(player.getUniqueId(), balance(player.getUniqueId()) + earned);
        saveBalances();
        player.sendMessage(ChatColor.GREEN + "Sold " + sold + " " + prettify(material) + " for $" + money.format(earned) + ".");
        openSell(player);
    }
    @EventHandler public void onInventoryDrag(InventoryDragEvent event) {
        String title = event.getView().getTitle();
        if (title.equals(SELL_MARKER) || title.equals(WORTH_MARKER)) event.setCancelled(true);
    }
    @EventHandler public void onJoin(PlayerJoinEvent event) { updatePlayerDisplay(event.getPlayer()); }

    private void updatePlayerDisplay(Player player) {
        String ip = getConfig().getString("server-ip", "kelvorasmp.mcsh.gg");
        player.setPlayerListHeaderFooter(ChatColor.DARK_GREEN + "" + ChatColor.BOLD + "KELVORA SMP\n" + ChatColor.GRAY + ip,
                ChatColor.GREEN + "Balance: " + ChatColor.WHITE + "$" + money.format(balance(player.getUniqueId())));
        if (!getConfig().getBoolean("sidebar.enabled", true)) return;
        Scoreboard board = Bukkit.getScoreboardManager().getNewScoreboard();
        Objective objective = board.registerNewObjective("kelvora", "dummy", ChatColor.DARK_GREEN + "" + ChatColor.BOLD + "KELVORA SMP");
        objective.setDisplaySlot(DisplaySlot.SIDEBAR);
        String[] lines = {
            ChatColor.GRAY + ip, ChatColor.DARK_GRAY + "---------------",
            ChatColor.GREEN + "Player", ChatColor.WHITE + player.getName(),
            ChatColor.GREEN + "Balance", ChatColor.WHITE + "$" + money.format(balance(player.getUniqueId())),
            ChatColor.DARK_GRAY + "---------------", ChatColor.GREEN + "play.kelvora"
        };
        int score = lines.length;
        for (int i = 0; i < lines.length; i++) {
            String entry = ChatColor.values()[i].toString() + ChatColor.values()[(i + 8) % ChatColor.values().length];
            Team team = board.registerNewTeam("line" + i);
            team.addEntry(entry);
            team.prefix(LegacyComponentSerializer.legacySection().deserialize(lines[i]));
            objective.getScore(entry).setScore(score--);
        }
        player.setScoreboard(board);
    }
}