package net.kelvora.smp;

import java.io.File;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.util.*;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.*;
import org.bukkit.command.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.inventory.*;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Phase 32 foundational commands. This class deliberately uses the existing
 * KelvorasManager and Vault economy provider rather than creating a second currency.
 */
final class Phase32Commands implements Listener {
    private static final long DAY = 86_400_000L;
    private static final String DAILY_TITLE = "KELVORA | DAILY REWARD";
    private static final double MAX_MONEY = 2_000_000_000_000_000.0;
    private final JavaPlugin plugin;
    private final KelvorasManager kelvoras;
    private final File claimsFile;
    private YamlConfiguration claims;
    private final Random random = new Random();
    private final Set<UUID> rolling = new HashSet<>();

    Phase32Commands(JavaPlugin plugin, KelvorasManager kelvoras) {
        this.plugin = plugin;
        this.kelvoras = kelvoras;
        this.claimsFile = new File(plugin.getDataFolder(), "daily-rewards.yml");
    }

    void register() {
        if (!plugin.getDataFolder().exists()) plugin.getDataFolder().mkdirs();
        claims = YamlConfiguration.loadConfiguration(claimsFile);
        registerCommand("kelvora", this::kelvora);
        registerCommand("news", this::news);
        registerCommand("daily", this::daily);
        Bukkit.getPluginManager().registerEvents(this, plugin);
        plugin.getConfig().addDefault("daily.min-money", 1000);
        plugin.getConfig().addDefault("daily.max-money", 150000);
        plugin.getConfig().addDefault("daily.cooldown-hours", 24);
        plugin.getConfig().addDefault("daily.reward-tiers", List.of("1000:40", "5000:25", "10000:18", "25000:10", "50000:5", "100000:1.5", "150000:0.5"));
        plugin.getConfig().options().copyDefaults(true);
        plugin.saveConfig();
    }

    private void registerCommand(String name, CommandExecutor executor) {
        PluginCommand command = plugin.getCommand(name);
        if (command == null) {
            plugin.getLogger().severe("Missing command '" + name + "' in plugin.yml.");
            return;
        }
        command.setExecutor(executor);
    }

    private boolean kelvora(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof org.bukkit.command.ConsoleCommandSender) && !(sender instanceof Player p && p.isOp())) {
            sender.sendMessage(Component.text("Only server operators can administer Kelvoras.", NamedTextColor.RED));
            return true;
        }
        if (args.length != 3 || !args[0].equalsIgnoreCase("give")) {
            sender.sendMessage(Component.text("Usage: /kelvora give <player> <amount>", NamedTextColor.YELLOW));
            return true;
        }
        OfflinePlayer target = Bukkit.getOfflinePlayer(args[1]);
        if (!target.isOnline() && !target.hasPlayedBefore()) {
            sender.sendMessage(Component.text("No saved player data found for " + args[1] + ".", NamedTextColor.RED));
            return true;
        }
        double amount;
        try {
            amount = parseAmount(args[2]);
        } catch (IllegalArgumentException ex) {
            sender.sendMessage(Component.text(ex.getMessage(), NamedTextColor.RED));
            return true;
        }
        if (!Double.isFinite(amount) || amount <= 0 || amount > 1_000_000_000_000.0) {
            sender.sendMessage(Component.text("Amount must be greater than zero and at most 1T Kelvoras.", NamedTextColor.RED));
            return true;
        }
        if (!kelvoras.deposit(target, amount)) {
            sender.sendMessage(Component.text("Could not safely save the grant. No Kelvoras were granted.", NamedTextColor.RED));
            return true;
        }
        String actor = sender instanceof Player p ? p.getName() : "CONSOLE";
        plugin.getLogger().info("[KELVORAS ADMIN] operator=" + actor + " target=" + (target.getName() == null ? args[1] : target.getName()) + " uuid=" + target.getUniqueId() + " amount=" + amount + " timestamp=" + System.currentTimeMillis());
        sender.sendMessage(Component.text("Granted ✦" + KelvorasManager.format(amount) + " to " + (target.getName() == null ? args[1] : target.getName()) + ". New balance: ✦" + KelvorasManager.format(kelvoras.balance(target)), NamedTextColor.GREEN));
        if (target.isOnline() && target.getPlayer() != null) target.getPlayer().sendMessage(Component.text("You received ✦" + KelvorasManager.format(amount) + " Kelvoras from server staff.", NamedTextColor.AQUA));
        return true;
    }

    private boolean news(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof org.bukkit.command.ConsoleCommandSender) && !(sender instanceof Player p && p.isOp())) {
            sender.sendMessage(Component.text("Only server operators can send news announcements.", NamedTextColor.RED));
            return true;
        }
        String message = String.join(" ", args).trim();
        if (message.isEmpty()) {
            sender.sendMessage(Component.text("Usage: /news <message>", NamedTextColor.YELLOW));
            return true;
        }
        message = message.replaceAll("[\\u0000-\\u001F\\u007F]", " ").replaceAll("§[0-9A-FK-ORa-fk-or]", "").replaceAll("&[0-9A-FK-ORa-fk-or]", "");
        if (message.length() > 180) {
            sender.sendMessage(Component.text("News is too long. Keep announcements to 180 characters or fewer.", NamedTextColor.RED));
            return true;
        }
        final String announcement = message;
        Component headline = Component.text("🌿 KELVORA NEWS", NamedTextColor.GREEN);
        Component subtitle = Component.text(announcement, NamedTextColor.WHITE);
        Title.Times times = Title.Times.times(java.time.Duration.ofMillis(500), java.time.Duration.ofSeconds(5), java.time.Duration.ofMillis(1000));
        Title title = Title.title(headline, subtitle, times);
        for (Player player : Bukkit.getOnlinePlayers()) {
            player.showTitle(title);
            player.sendMessage(Component.text("✦ NEWS: ", NamedTextColor.GREEN).append(Component.text(announcement, NamedTextColor.WHITE)));
            player.playSound(player.getLocation(), org.bukkit.Sound.BLOCK_NOTE_BLOCK_CHIME, 0.8f, 1.2f);
        }
        String actor = sender instanceof Player p ? p.getName() : "CONSOLE";
        plugin.getLogger().info("[NEWS] operator=" + actor + " message=" + announcement);
        return true;
    }

    private boolean daily(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("This command can only be used in-game.");
            return true;
        }
        if (args.length != 0) {
            player.sendMessage(Component.text("Usage: /daily", NamedTextColor.YELLOW));
            return true;
        }
        long now = System.currentTimeMillis();
        long last = claims.getLong("claims." + player.getUniqueId(), 0L);
        long cooldown = Math.max(1, plugin.getConfig().getLong("daily.cooldown-hours", 24)) * 3_600_000L;
        long left = last + cooldown - now;
        Inventory inv = Bukkit.createInventory(null, 27, DAILY_TITLE);
        ItemStack pane = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta paneMeta = pane.getItemMeta();
        paneMeta.displayName(Component.text(" ", NamedTextColor.GRAY));
        pane.setItemMeta(paneMeta);
        for (int i = 0; i < 27; i++) inv.setItem(i, pane.clone());
        set(inv, 11, Material.CHEST, "§aDaily Money Chest", List.of("§7Free random Money reward", "§7Possible reward: §f$1,000 - $150,000", left > 0 ? "§cAvailable in " + formatDuration(left) : "§eClick to claim your daily reward"), "claim");
        set(inv, 13, Material.CHEST, "§2Kelvora Daily", List.of("§7Random Money reward only", "§7No Kelvoras or keys are granted", left > 0 ? "§cAvailable in " + formatDuration(left) : "§aOne free claim"), "claim");
        set(inv, 15, Material.PAPER, "§fUse /gamble too!", List.of("§7Informational only", "§7Daily rewards and gamble are separate"), "info");
        player.openInventory(inv);
        return true;
    }

    private void set(Inventory inv, int slot, Material type, String name, List<String> lore, String action) {
        ItemStack item = new ItemStack(type);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(name.replaceAll("§[0-9A-FK-ORa-fk-or]", "")));
        meta.lore(lore.stream().map(line -> Component.text(line.replaceAll("§[0-9A-FK-ORa-fk-or]", ""), NamedTextColor.GRAY)).toList());
        meta.getPersistentDataContainer().set(new org.bukkit.NamespacedKey(plugin, "phase32_daily_action"), org.bukkit.persistence.PersistentDataType.STRING, action);
        item.setItemMeta(meta);
        inv.setItem(slot, item);
    }

    @EventHandler
    public void onDailyClick(InventoryClickEvent event) {
        if (!DAILY_TITLE.equals(event.getView().getTitle())) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player) || event.getClickedInventory() != event.getView().getTopInventory()) return;
        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || clicked.getType() != Material.CHEST) return;
        UUID id = player.getUniqueId();
        if (!rolling.add(id)) return;
        try {
            long now = System.currentTimeMillis();
            String path = "claims." + id;
            long last = claims.getLong(path, 0L);
            long cooldown = Math.max(1, plugin.getConfig().getLong("daily.cooldown-hours", 24)) * 3_600_000L;
            if (last + cooldown > now) {
                player.sendMessage(Component.text("Your daily reward is ready in " + formatDuration(last + cooldown - now) + ".", NamedTextColor.RED));
                player.closeInventory();
                return;
            }
            Double reward = rollReward();
            if (reward == null || !payMoney(player, reward)) {
                player.sendMessage(Component.text("The reward could not be paid safely. Your claim was not consumed.", NamedTextColor.RED));
                return;
            }
            claims.set(path, now);
            try {
                claims.save(claimsFile);
            } catch (IOException ex) {
                claims.set(path, last);
                boolean reversed = withdrawMoney(player, reward);
                try { claims.save(claimsFile); } catch (IOException ignored) {}
                plugin.getLogger().severe("Could not persist daily claim for " + player.getUniqueId() + "; payout reversal=" + reversed + ": " + ex.getMessage());
                player.sendMessage(Component.text(reversed
                    ? "The reward could not be recorded, so the Money payment was reversed. Your claim is still available."
                    : "CRITICAL: The daily claim could not be saved and the payout could not be reversed. Contact staff before claiming again.", NamedTextColor.RED));
                return;
            }
            player.closeInventory();
            player.sendMessage(Component.text("Daily reward: +$" + new DecimalFormat("#,##0.##").format(reward) + " Money!", NamedTextColor.GREEN));
            player.playSound(player.getLocation(), org.bukkit.Sound.ENTITY_PLAYER_LEVELUP, 0.8f, 1.1f);
        } finally {
            rolling.remove(id);
        }
    }

    @EventHandler
    public void onDailyDrag(InventoryDragEvent event) {
        if (DAILY_TITLE.equals(event.getView().getTitle())) event.setCancelled(true);
    }

    private Double rollReward() {
        List<String> tiers = plugin.getConfig().getStringList("daily.reward-tiers");
        double total = 0;
        List<double[]> parsed = new ArrayList<>();
        for (String tier : tiers) {
            String[] parts = tier.split(":", 2);
            if (parts.length != 2) continue;
            try {
                double value = parseAmount(parts[0]);
                double weight = Double.parseDouble(parts[1]);
                if (!Double.isFinite(value) || value < 1000 || value > 150000 || !Double.isFinite(weight) || weight <= 0) continue;
                parsed.add(new double[]{value, weight});
                total += weight;
            } catch (IllegalArgumentException ignored) { }
        }
        if (parsed.isEmpty() || !Double.isFinite(total) || total <= 0) return null;
        double pick = random.nextDouble() * total;
        for (double[] tier : parsed) {
            pick -= tier[1];
            if (pick <= 0) return tier[0];
        }
        return parsed.get(parsed.size() - 1)[0];
    }

    private boolean withdrawMoney(Player player, double amount) {
        try {
            Class<?> type = Class.forName("net.milkbowl.vault.economy.Economy");
            var registration = Bukkit.getServicesManager().getRegistration((Class) type);
            if (registration == null) return false;
            Object economy = registration.getProvider();
            Object response = economy.getClass().getMethod("withdrawPlayer", OfflinePlayer.class, double.class).invoke(economy, player, amount);
            Object success = response.getClass().getMethod("transactionSuccess").invoke(response);
            return Boolean.TRUE.equals(success);
        } catch (ReflectiveOperationException | LinkageError ex) {
            plugin.getLogger().severe("Daily Money payout reversal failed: " + ex.getMessage());
            return false;
        }
    }

    private boolean payMoney(Player player, double amount) {
        try {
            Class<?> type = Class.forName("net.milkbowl.vault.economy.Economy");
            var registration = Bukkit.getServicesManager().getRegistration((Class) type);
            if (registration == null) return false;
            Object economy = registration.getProvider();
            Object response = economy.getClass().getMethod("depositPlayer", OfflinePlayer.class, double.class).invoke(economy, player, amount);
            Object success = response.getClass().getMethod("transactionSuccess").invoke(response);
            return Boolean.TRUE.equals(success);
        } catch (ReflectiveOperationException | LinkageError ex) {
            plugin.getLogger().warning("Daily Money payout failed safely: " + ex.getMessage());
            return false;
        }
    }

    static double parseAmount(String input) {
        if (input == null || input.isBlank()) throw new IllegalArgumentException("Enter a valid amount.");
        String value = input.trim().toLowerCase(Locale.ROOT).replace(",", "");
        BigDecimal multiplier = BigDecimal.ONE;
        char last = value.charAt(value.length() - 1);
        if (last == 'k' || last == 'm' || last == 'b') {
            value = value.substring(0, value.length() - 1);
            multiplier = switch (last) {
                case 'k' -> BigDecimal.valueOf(1_000L);
                case 'm' -> BigDecimal.valueOf(1_000_000L);
                default -> BigDecimal.valueOf(1_000_000_000L);
            };
        }
        try {
            BigDecimal result = new BigDecimal(value).multiply(multiplier);
            if (result.signum() <= 0 || result.compareTo(BigDecimal.valueOf(MAX_MONEY)) > 0) throw new IllegalArgumentException("Amount must be positive and within the supported limit.");
            double d = result.doubleValue();
            if (!Double.isFinite(d)) throw new IllegalArgumentException("Amount is too large.");
            return d;
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("Invalid amount. Examples: 1000, 10k, 1.5m, 2b.");
        }
    }

    private String formatDuration(long millis) {
        long totalMinutes = Math.max(0, millis) / 60_000L;
        long hours = totalMinutes / 60;
        long minutes = totalMinutes % 60;
        return hours + "h " + minutes + "m";
    }
}
