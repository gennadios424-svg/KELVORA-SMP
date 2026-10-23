package net.kelvora.smp;

import java.io.File;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.util.Locale;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

/** Authoritative persistent storage for the separate Kelvoras currency. */
final class KelvorasManager {
    private static final double MAX_BALANCE = 1_000_000_000_000.0;
    private final JavaPlugin plugin;
    private final File file;
    private YamlConfiguration data;

    KelvorasManager(JavaPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "kelvoras.yml");
    }

    void register() {
        if (!plugin.getDataFolder().exists()) plugin.getDataFolder().mkdirs();
        data = YamlConfiguration.loadConfiguration(file);
        migrateOnlinePlayers();
        if (plugin.getCommand("kelvoras") != null) {
            plugin.getCommand("kelvoras").setExecutor((sender, command, label, args) -> {
                if (!(sender instanceof Player player)) {
                    sender.sendMessage("Use this command in-game.");
                    return true;
                }
                if (!player.hasPermission("kelvora.balance")) {
                    player.sendMessage(Component.text("You do not have permission.", NamedTextColor.RED));
                    return true;
                }
                if (args.length == 0) {
                    player.sendMessage(Component.text("Kelvoras: ✦" + format(balance(player)), NamedTextColor.AQUA));
                    player.sendMessage(Component.text("Use /kelvoras pay <player> <amount> to transfer Kelvoras.", NamedTextColor.GRAY));
                    return true;
                }
                if (args.length != 3 || !args[0].equalsIgnoreCase("pay")) {
                    player.sendMessage(Component.text("Usage: /kelvoras [pay <player> <amount>]", NamedTextColor.RED));
                    return true;
                }
                Player target = Bukkit.getPlayerExact(args[1]);
                if (target == null) {
                    player.sendMessage(Component.text("That player must be online to receive a safe Kelvoras transfer.", NamedTextColor.RED));
                    return true;
                }
                if (target.getUniqueId().equals(player.getUniqueId())) {
                    player.sendMessage(Component.text("You cannot pay yourself.", NamedTextColor.RED));
                    return true;
                }
                BigDecimal parsed;
                try {
                    parsed = new BigDecimal(args[2]).setScale(2, RoundingMode.UNNECESSARY);
                } catch (NumberFormatException | ArithmeticException ex) {
                    player.sendMessage(Component.text("Enter a valid amount with up to 2 decimal places.", NamedTextColor.RED));
                    return true;
                }
                if (parsed.signum() <= 0 || parsed.doubleValue() > MAX_BALANCE || !Double.isFinite(parsed.doubleValue())) {
                    player.sendMessage(Component.text("Amount must be greater than zero and no more than " + format(MAX_BALANCE) + ".", NamedTextColor.RED));
                    return true;
                }
                double amount = parsed.doubleValue();
                double senderBalance = balance(player);
                if (!Double.isFinite(senderBalance) || senderBalance + 0.000001 < amount) {
                    player.sendMessage(Component.text("Not enough Kelvoras. Your balance is ✦" + format(senderBalance) + ".", NamedTextColor.RED));
                    return true;
                }
                double targetBalance = balance(target);
                if (!Double.isFinite(targetBalance) || targetBalance > MAX_BALANCE - amount) {
                    player.sendMessage(Component.text("That transfer would exceed the recipient's balance limit.", NamedTextColor.RED));
                    return true;
                }
                if (!transfer(player, target, amount)) {
                    player.sendMessage(Component.text("Transfer could not be saved. No transfer was completed; contact staff if this repeats.", NamedTextColor.RED));
                    return true;
                }
                player.sendMessage(Component.text("Sent ✦" + format(amount) + " Kelvoras to " + target.getName() + ". Balance: ✦" + format(balance(player)) + ".", NamedTextColor.GREEN));
                target.sendMessage(Component.text("Received ✦" + format(amount) + " Kelvoras from " + player.getName() + ".", NamedTextColor.AQUA));
                return true;
            });
        }
    }

    synchronized double balance(OfflinePlayer player) {
        String key = "balances." + player.getUniqueId();
        if (data.contains(key)) {
            double value = data.getDouble(key, 0.0);
            return Double.isFinite(value) && value >= 0 ? value : 0.0;
        }
        Double legacy = legacyPlaceholder(player);
        if (legacy != null && Double.isFinite(legacy) && legacy >= 0 && legacy <= MAX_BALANCE) {
            data.set(key, legacy);
            saveQuietly();
            plugin.getLogger().info("Migrated an existing Kelvoras balance for " + safeName(player) + " into kelvoras.yml.");
            return legacy;
        }
        return 0.0;
    }

    private void migrateOnlinePlayers() {
        for (Player player : Bukkit.getOnlinePlayers()) balance(player);
    }

    private Double legacyPlaceholder(OfflinePlayer offline) {
        if (!(offline instanceof Player player)) return null;
        if (!Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) return null;
        String placeholder = plugin.getConfig().getString("kelvoras.placeholder", "%kelvoras_balance%");
        try {
            Object result = Class.forName("me.clip.placeholderapi.PlaceholderAPI",
                    true, Bukkit.getPluginManager().getPlugin("PlaceholderAPI").getClass().getClassLoader())
                .getMethod("setPlaceholders", Player.class, String.class).invoke(null, player, placeholder);
            if (!(result instanceof String value)) return null;
            String plain = org.bukkit.ChatColor.stripColor(value);
            if (plain == null) return null;
            plain = plain.replace(",", "").replace("✦", "").trim();
            if (plain.isBlank() || plain.equals(placeholder) || (plain.startsWith("%") && plain.endsWith("%"))) return null;
            double amount = Double.parseDouble(plain);
            return Double.isFinite(amount) && amount >= 0 ? amount : null;
        } catch (ReflectiveOperationException | LinkageError | NumberFormatException ex) {
            return null;
        }
    }

    synchronized boolean withdraw(OfflinePlayer player, double amount) {
        if (player == null || !Double.isFinite(amount) || amount <= 0 || amount > MAX_BALANCE) return false;
        double current = balance(player);
        if (!Double.isFinite(current) || current + 0.000001 < amount) return false;
        String key = "balances." + player.getUniqueId();
        data.set(key, current - amount);
        try {
            data.save(file);
            return true;
        } catch (IOException ex) {
            data.set(key, current);
            plugin.getLogger().severe("Could not persist Kelvoras purchase: " + ex.getMessage());
            return false;
        }
    }

    synchronized boolean deposit(OfflinePlayer player, double amount) {
        if (player == null || !Double.isFinite(amount) || amount <= 0 || amount > MAX_BALANCE) return false;
        double current = balance(player);
        if (!Double.isFinite(current) || current > MAX_BALANCE - amount) return false;
        String key = "balances." + player.getUniqueId();
        data.set(key, current + amount);
        try {
            data.save(file);
            return true;
        } catch (IOException ex) {
            data.set(key, current);
            plugin.getLogger().severe("Could not persist Kelvoras reward: " + ex.getMessage());
            return false;
        }
    }

    synchronized boolean transfer(Player sender, Player target, double amount) {
        if (sender == null || target == null || sender.getUniqueId().equals(target.getUniqueId())
                || !Double.isFinite(amount) || amount <= 0 || amount > MAX_BALANCE) return false;
        double from = balance(sender);
        double to = balance(target);
        if (!Double.isFinite(from) || !Double.isFinite(to) || from + 0.000001 < amount || to > MAX_BALANCE - amount) return false;
        String fromKey = "balances." + sender.getUniqueId();
        String toKey = "balances." + target.getUniqueId();
        data.set(fromKey, from - amount);
        data.set(toKey, to + amount);
        try {
            data.save(file);
            return true;
        } catch (IOException ex) {
            data.set(fromKey, from);
            data.set(toKey, to);
            plugin.getLogger().severe("Could not persist Kelvoras transfer: " + ex.getMessage());
            return false;
        }
    }

    synchronized void save() {
        saveQuietly();
    }

    private void saveQuietly() {
        try {
            data.save(file);
        } catch (IOException ex) {
            plugin.getLogger().severe("Could not save Kelvoras balances: " + ex.getMessage());
        }
    }

    private String safeName(OfflinePlayer player) {
        return player.getName() == null ? player.getUniqueId().toString() : player.getName();
    }

    static String format(double amount) {
        return new DecimalFormat("#,##0.##").format(amount);
    }
}
