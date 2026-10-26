package net.kelvora.smp;

import java.io.File;
import java.lang.reflect.Method;
import java.text.DecimalFormat;
import java.util.*;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Small economy and safe maintenance features. Vault is accessed reflectively so
 * the plugin still loads if Vault is absent; economy commands fail with a clear message.
 */
final class EconomyFeaturesManager implements Listener {
    private final JavaPlugin plugin;
    private final File settingsFile;
    private final YamlConfiguration settings;
    private Object economy;
    private final DecimalFormat money = new DecimalFormat("#,##0.##");
    private static final long CLEAN_INTERVAL_TICKS = 20L * 60L * 10L;
    private static final long ITEM_MAX_AGE_TICKS = 20L * 60L * 10L;
    private static final Map<String, Double> DEFAULT_MOB_REWARDS = Map.ofEntries(
        Map.entry("ZOMBIE", 3.0), Map.entry("SKELETON", 3.0), Map.entry("CREEPER", 4.0),
        Map.entry("SPIDER", 2.0), Map.entry("ENDERMAN", 8.0), Map.entry("BLAZE", 6.0),
        Map.entry("WITCH", 5.0), Map.entry("PHANTOM", 4.0), Map.entry("DROWNED", 3.0),
        Map.entry("PILLAGER", 4.0), Map.entry("HOGLIN", 5.0), Map.entry("PIGLIN_BRUTE", 8.0)
    );

    EconomyFeaturesManager(JavaPlugin plugin) {
        this.plugin = plugin;
        this.settingsFile = new File(plugin.getDataFolder(), "economy-player-settings.yml");
        this.settings = YamlConfiguration.loadConfiguration(settingsFile);
    }

    void register() {
        Bukkit.getPluginManager().registerEvents(this, plugin);
        connectEconomy();
        if (plugin.getCommand("pay") != null) plugin.getCommand("pay").setExecutor(this::payCommand);
        if (plugin.getCommand("mobpay") != null) plugin.getCommand("mobpay").setExecutor(this::mobPayCommand);
        if (plugin.getCommand("lagcleaner") != null) plugin.getCommand("lagcleaner").setExecutor(this::cleanCommand);
        if (plugin.getCommand("optimize") != null) plugin.getCommand("optimize").setExecutor(this::optimizeCommand);
        applyWorldDistances();
        Bukkit.getScheduler().runTaskTimer(plugin, this::cleanOldDroppedItems, CLEAN_INTERVAL_TICKS, CLEAN_INTERVAL_TICKS);
    }

    private void connectEconomy() {
        try {
            if (!Bukkit.getPluginManager().isPluginEnabled("Vault")) return;
            Class<?> type = Class.forName("net.milkbowl.vault.economy.Economy",
                true, Bukkit.getPluginManager().getPlugin("Vault").getClass().getClassLoader());
            RegisteredServiceProvider<?> registration = Bukkit.getServicesManager().getRegistration((Class) type);
            if (registration != null) economy = registration.getProvider();
        } catch (ReflectiveOperationException | LinkageError ex) {
            plugin.getLogger().warning("Vault economy unavailable: " + ex.getMessage());
        }
    }

    private boolean payCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) { sender.sendMessage("Use /pay in-game."); return true; }
        if (args.length != 2) { player.sendMessage(ChatColor.YELLOW + "Usage: /pay <player> <amount>"); return true; }
        Player target = Bukkit.getPlayerExact(args[0]);
        if (target == null) { player.sendMessage(ChatColor.RED + "That player is not online."); return true; }
        if (target.equals(player)) { player.sendMessage(ChatColor.RED + "You cannot pay yourself."); return true; }
        double amount;
        try { amount = Double.parseDouble(args[1]); } catch (NumberFormatException ex) {
            player.sendMessage(ChatColor.RED + "Enter a valid amount."); return true;
        }
        if (!Double.isFinite(amount) || amount <= 0 || amount > 1_000_000_000_000d) {
            player.sendMessage(ChatColor.RED + "Amount must be greater than zero and within the limit."); return true;
        }
        if (economy == null) { player.sendMessage(ChatColor.RED + "Money economy is unavailable. Make sure Vault and an economy provider are enabled."); return true; }
        try {
            Method has = economy.getClass().getMethod("has", org.bukkit.OfflinePlayer.class, double.class);
            if (!(Boolean) has.invoke(economy, player, amount)) {
                player.sendMessage(ChatColor.RED + "You do not have enough Money."); return true;
            }
            Object withdrawn = economy.getClass().getMethod("withdrawPlayer", org.bukkit.OfflinePlayer.class, double.class).invoke(economy, player, amount);
            if (!transactionSuccess(withdrawn)) { player.sendMessage(ChatColor.RED + "Payment failed; your balance was not changed."); return true; }
            Object deposited = economy.getClass().getMethod("depositPlayer", org.bukkit.OfflinePlayer.class, double.class).invoke(economy, target, amount);
            if (!transactionSuccess(deposited)) {
                economy.getClass().getMethod("depositPlayer", org.bukkit.OfflinePlayer.class, double.class).invoke(economy, player, amount);
                player.sendMessage(ChatColor.RED + "Payment failed and your Money was refunded."); return true;
            }
            player.sendMessage(ChatColor.GREEN + "Paid $" + money.format(amount) + " to " + target.getName() + ".");
            target.sendMessage(ChatColor.GREEN + "You received $" + money.format(amount) + " from " + player.getName() + ".");
        } catch (ReflectiveOperationException ex) {
            plugin.getLogger().warning("Payment failed: " + ex.getMessage());
            player.sendMessage(ChatColor.RED + "Payment failed due to an economy error. No payment was confirmed.");
        }
        return true;
    }

    private boolean transactionSuccess(Object response) {
        if (response == null) return false;
        try { return (Boolean) response.getClass().getMethod("transactionSuccess").invoke(response); }
        catch (ReflectiveOperationException ex) { return false; }
    }

    private boolean mobPayCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) { sender.sendMessage("Use /mobpay in-game."); return true; }
        if (args.length == 0 || args[0].equalsIgnoreCase("status")) {
            boolean enabled = settings.getBoolean("players." + player.getUniqueId() + ".mob-payments", true);
            player.sendMessage(ChatColor.GREEN + "Mob kill payments: " + (enabled ? ChatColor.GREEN + "ON" : ChatColor.RED + "OFF")
                + ChatColor.GRAY + " — use /mobpay on or /mobpay off.");
            return true;
        }
        if (!args[0].equalsIgnoreCase("on") && !args[0].equalsIgnoreCase("off")) {
            player.sendMessage(ChatColor.YELLOW + "Usage: /mobpay [on|off|status]"); return true;
        }
        boolean enabled = args[0].equalsIgnoreCase("on");
        settings.set("players." + player.getUniqueId() + ".mob-payments", enabled);
        saveSettings();
        player.sendMessage(ChatColor.GREEN + "Mob kill payments turned " + (enabled ? "ON" : "OFF") + ".");
        return true;
    }

    @EventHandler(ignoreCancelled = true)
    public void onMobDeath(EntityDeathEvent event) {
        Player killer = event.getEntity().getKiller();
        if (killer == null || event.getEntity() instanceof Player || economy == null) return;
        String mob = event.getEntityType().name();
        double amount = plugin.getConfig().getDouble("mob-payments.rewards." + mob,
            DEFAULT_MOB_REWARDS.getOrDefault(mob, 0.0));
        if (!plugin.getConfig().getBoolean("mob-payments.enabled", true) || amount <= 0) return;
        if (!settings.getBoolean("players." + killer.getUniqueId() + ".mob-payments", true)) return;
        try {
            Object result = economy.getClass().getMethod("depositPlayer", org.bukkit.OfflinePlayer.class, double.class)
                .invoke(economy, killer, amount);
            if (transactionSuccess(result)) {
                killer.sendActionBar(net.kyori.adventure.text.Component.text("+$" + money.format(amount) + " mob reward",
                    net.kyori.adventure.text.format.NamedTextColor.GREEN));
            }
        } catch (ReflectiveOperationException ex) {
            plugin.getLogger().fine("Could not pay mob reward: " + ex.getMessage());
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onNaturalMobSpawn(CreatureSpawnEvent event) {
        if (event.getSpawnReason() != CreatureSpawnEvent.SpawnReason.NATURAL) return;
        if (!plugin.getConfig().getBoolean("settings.nearby-mob-spawn-toggle-enabled", true)) return;
        if (!(event.getEntity() instanceof Monster)) return;
        double radius = Math.max(8.0, Math.min(64.0, plugin.getConfig().getDouble("settings.nearby-mob-spawn-radius", 24.0)));
        double radiusSquared = radius * radius;
        for (Player player : event.getLocation().getWorld().getPlayers()) {
            if (player.getLocation().distanceSquared(event.getLocation()) > radiusSquared) continue;
            if (settings.getBoolean("players." + player.getUniqueId() + ".reduce-nearby-hostile-spawns", false)) {
                event.setCancelled(true);
                return;
            }
        }
    }

    private int cleanOldDroppedItems() {
        int removed = 0;
        for (World world : Bukkit.getWorlds()) {
            for (Item item : world.getEntitiesByClass(Item.class)) {
                if (item.isDead() || item.getTicksLived() < ITEM_MAX_AGE_TICKS || item.isInvulnerable()) continue;
                if (item.getItemStack().hasItemMeta()) continue;
                item.remove();
                removed++;
            }
        }
        return removed;
    }

    private boolean cleanCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.isOp() && !sender.hasPermission("kelvora.admin.optimize")) {
            sender.sendMessage(ChatColor.RED + "Only administrators can run the lag cleaner."); return true;
        }
        int removed = cleanOldDroppedItems();
        sender.sendMessage(ChatColor.GREEN + "Lag cleaner removed " + removed + " ordinary dropped item entities older than 10 minutes.");
        sender.sendMessage(ChatColor.GRAY + "Mobs (including zombies and snow golems), snowballs, boats, villagers, bosses, armor stands, XP orbs, named/custom drops and player inventories are untouched.");
        sender.sendMessage(ChatColor.GRAY + "Automatic cleanup runs every 10 minutes.");
        return true;
    }

    private boolean optimizeCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.isOp() && !sender.hasPermission("kelvora.admin.optimize")) {
            sender.sendMessage(ChatColor.RED + "Only administrators can run optimization."); return true;
        }
        applyWorldDistances();
        sender.sendMessage(ChatColor.GREEN + "Applied configured world view/simulation distances where supported.");
        sender.sendMessage(ChatColor.GRAY + "For full server optimization, also set server.properties view-distance=16 and simulation-distance=8, then restart.");
        sender.sendMessage(ChatColor.GRAY + "No chunks, entities, or player data were force-unloaded.");
        return true;
    }

    private void applyWorldDistances() {
        int view = Math.max(2, Math.min(32, plugin.getConfig().getInt("performance.view-distance", 16)));
        int simulation = Math.max(2, Math.min(view, plugin.getConfig().getInt("performance.simulation-distance", 8)));
        for (World world : Bukkit.getWorlds()) {
            try {
                world.setViewDistance(view);
                world.setSimulationDistance(simulation);
            } catch (NoSuchMethodError | UnsupportedOperationException ex) {
                plugin.getLogger().warning("This server API does not support setting per-world distances; configure server.properties instead.");
                break;
            }
        }
    }

    private void saveSettings() {
        try { settings.save(settingsFile); }
        catch (Exception ex) { plugin.getLogger().severe("Could not save economy player settings: " + ex.getMessage()); }
    }
}
