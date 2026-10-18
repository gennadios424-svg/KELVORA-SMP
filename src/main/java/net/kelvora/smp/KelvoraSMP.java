/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  me.clip.placeholderapi.PlaceholderAPI
 *  net.kyori.adventure.text.Component
 *  net.luckperms.api.LuckPerms
 *  net.luckperms.api.LuckPermsProvider
 *  net.luckperms.api.model.user.User
 *  net.milkbowl.vault.economy.Economy
 *  org.bukkit.Bukkit
 *  org.bukkit.ChatColor
 *  org.bukkit.OfflinePlayer
 *  org.bukkit.configuration.ConfigurationSection
 *  org.bukkit.entity.Player
 *  org.bukkit.event.EventHandler
 *  org.bukkit.event.Listener
 *  org.bukkit.event.player.PlayerChangedWorldEvent
 *  org.bukkit.event.player.PlayerJoinEvent
 *  org.bukkit.event.player.PlayerQuitEvent
 *  org.bukkit.plugin.Plugin
 *  org.bukkit.plugin.RegisteredServiceProvider
 *  org.bukkit.plugin.java.JavaPlugin
 *  org.bukkit.scoreboard.DisplaySlot
 *  org.bukkit.scoreboard.Objective
 *  org.bukkit.scoreboard.Scoreboard
 *  org.bukkit.scoreboard.Team
 */
package net.kelvora.smp;

import java.text.DecimalFormat;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import net.kelvora.smp.WorthManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.model.user.User;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

public final class KelvoraSMP
extends JavaPlugin
implements Listener {
    private Object economy;
    private LuckPerms luckPerms;
    private boolean placeholderApi;
    private int refreshTask;
    private final Map<UUID, Scoreboard> playerBoards = new HashMap<UUID, Scoreboard>();

    public void onEnable() {
        this.saveDefaultConfig();
        this.getConfig().options().copyDefaults(true);
        this.saveConfig();
        this.setupIntegrations();
        new WorthManager(this).register();
        new ShopSellManager(this).register();
        if (this.getCommand("bal") != null) {
            this.getCommand("bal").setExecutor((sender, command, label, args) -> {
                if (!(sender instanceof Player player)) { sender.sendMessage("Use this command in-game."); return true; }
                if (args.length != 0) { player.sendMessage(Component.text("Usage: /bal", NamedTextColor.RED)); return true; }
                if (this.economy == null) this.economy = this.resolveEconomyProvider();
                double amount = this.economyBalance(player);
                String money = Double.isFinite(amount) ? "$" + this.formatExactMoney(amount) : "Not connected";
                player.sendMessage(Component.text("Your Balance", NamedTextColor.GREEN));
                player.sendMessage(Component.text("Money: ", NamedTextColor.WHITE).append(Component.text(money, Double.isFinite(amount) ? NamedTextColor.GREEN : NamedTextColor.RED)));
                String kelvoras = this.getKelvoras(player);
                player.sendMessage(Component.text("Kelvoras: ", NamedTextColor.WHITE).append(Component.text(kelvoras, "Not connected".equalsIgnoreCase(kelvoras) ? NamedTextColor.RED : NamedTextColor.AQUA)));
                return true;
            });
        }
        Bukkit.getPluginManager().registerEvents((Listener)this, (Plugin)this);
        this.refreshTask = Bukkit.getScheduler().scheduleSyncRepeatingTask((Plugin)this, () -> {
            for (Player player : Bukkit.getOnlinePlayers()) {
                this.updatePlayer(player);
            }
        }, 1L, Math.max(20L, this.getConfig().getLong("refresh-ticks", 40L)));
        for (Player player : Bukkit.getOnlinePlayers()) {
            this.updatePlayer(player);
        }
        this.getLogger().info("Kelvora SMP presentation enabled.");
    }

    public void onDisable() {
        if (this.refreshTask != 0) {
            Bukkit.getScheduler().cancelTask(this.refreshTask);
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (this.playerBoards.containsKey(player.getUniqueId()) && player.getScoreboard() == this.playerBoards.get(player.getUniqueId())) {
                player.setScoreboard(Bukkit.getScoreboardManager().getMainScoreboard());
            }
            player.setPlayerListName(player.getName());
            player.sendPlayerListHeaderAndFooter(Component.empty(), Component.empty());
        }
        this.playerBoards.clear();
    }

    private Object resolveEconomyProvider() {
        if (!Bukkit.getPluginManager().isPluginEnabled("Vault")) return null;
        try {
            Class<?> economyType = Class.forName("net.milkbowl.vault.economy.Economy", true,
                Bukkit.getPluginManager().getPlugin("Vault").getClass().getClassLoader());
            RegisteredServiceProvider<?> registration = this.getServer().getServicesManager().getRegistration((Class) economyType);
            return registration == null ? null : registration.getProvider();
        } catch (ReflectiveOperationException | LinkageError ex) {
            this.getLogger().warning("Vault economy API unavailable; money will show Not connected.");
            return null;
        }
    }

    private double economyBalance(OfflinePlayer player) {
        if (this.economy == null) return Double.NaN;
        try {
            Object value = this.economy.getClass().getMethod("getBalance", OfflinePlayer.class).invoke(this.economy, player);
            return value instanceof Number ? ((Number) value).doubleValue() : Double.NaN;
        } catch (ReflectiveOperationException | LinkageError ex) {
            return Double.NaN;
        }
    }

    private void setupIntegrations() {
        RegisteredServiceProvider registration;
        if (Bukkit.getPluginManager().isPluginEnabled("LuckPerms")) {
            try {
                this.luckPerms = LuckPermsProvider.get();
            }
            catch (IllegalStateException ex) {
                this.getLogger().warning("LuckPerms API unavailable; ranks will use MEMBER fallback.");
            }
        }
        this.economy = this.resolveEconomyProvider();
        this.placeholderApi = Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI");
        if (this.economy == null) {
            this.getLogger().warning("No Vault economy provider found. Sidebar will show Not connected for money.");
        }
        if (this.luckPerms == null) {
            this.getLogger().warning("LuckPerms unavailable. Sidebar/name formatting will use MEMBER fallback.");
        }
        if (!this.placeholderApi) {
            this.getLogger().info("PlaceholderAPI not found. Kelvoras integration will show Not connected until configured.");
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Bukkit.getScheduler().runTask((Plugin)this, () -> this.updatePlayer(event.getPlayer()));
    }

    @EventHandler
    public void onWorldChange(PlayerChangedWorldEvent event) {
        this.updatePlayer(event.getPlayer());
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
        Bukkit.getScheduler().runTask((Plugin)this, () -> this.updatePlayer(event.getPlayer()));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        this.playerBoards.remove(player.getUniqueId());
        Bukkit.getScheduler().runTask((Plugin)this, () -> {
            if (player.getScoreboard() == Bukkit.getScoreboardManager().getMainScoreboard()
                    || player.getScoreboard().getObjective("kelvora") != null) {
                player.setScoreboard(Bukkit.getScoreboardManager().getMainScoreboard());
            }
            player.setPlayerListName(player.getName());
            player.sendPlayerListHeaderAndFooter(Component.empty(), Component.empty());
        });
    }

    private void updatePlayer(Player player) {
        if (this.economy == null) this.economy = this.resolveEconomyProvider();
        String serverAddress = this.getConfig().getString("server-address", "kelvorasmp.mcsh.gg");
        String rank = this.rankKey(player);
        String rankLabel = this.getConfig().getString("ranks." + rank + ".label", rank.toUpperCase(Locale.ROOT));
        String rankColor = this.getConfig().getString("ranks." + rank + ".color", "&a");
        String prefix = null;
        if (this.luckPerms != null) {
            User user = this.luckPerms.getUserManager().getUser(player.getUniqueId());
            if (user != null) prefix = user.getCachedData().getMetaData().getPrefix();
        }
        if (prefix == null || prefix.isBlank()) prefix = rankColor + "[" + rankLabel + "] ";
        player.setPlayerListName(this.color(prefix) + this.color("&f" + player.getName()));

        double balance = this.economyBalance(player);
        String moneyCompact = Double.isFinite(balance) ? this.formatMoney(balance) : "Not connected";
        String kelvoras = this.getKelvoras(player);
        Component header = Component.text("KELVORA SMP", NamedTextColor.GREEN)
            .append(Component.newline())
            .append(Component.text("Survival • Economy • PvP", NamedTextColor.GRAY));
        Component footer = Component.text("Money: ", NamedTextColor.WHITE)
            .append(Component.text(Double.isFinite(balance) ? "$" + moneyCompact : "Not connected",
                Double.isFinite(balance) ? NamedTextColor.GREEN : NamedTextColor.RED))
            .append(Component.newline())
            .append(Component.text("Kelvoras: ", NamedTextColor.WHITE))
            .append(Component.text(kelvoras, "Not connected".equalsIgnoreCase(kelvoras) ? NamedTextColor.RED : NamedTextColor.AQUA))
            .append(Component.newline())
            .append(Component.text("Online: " + Bukkit.getOnlinePlayers().size(), NamedTextColor.GRAY))
            .append(Component.newline())
            .append(Component.text(serverAddress, NamedTextColor.GREEN));
        player.sendPlayerListHeaderAndFooter(header, footer);

        Scoreboard board = this.playerBoards.get(player.getUniqueId());
        if (board == null) {
            board = Bukkit.getScoreboardManager().getNewScoreboard();
            Objective created = board.registerNewObjective("kelvora", "dummy", Component.text("KELVORA SMP", NamedTextColor.GREEN));
            created.setDisplaySlot(DisplaySlot.SIDEBAR);
            for (int i = 0; i < 5; ++i) {
                String entry = ChatColor.values()[i].toString() + ChatColor.RESET;
                Team team = board.registerNewTeam("line" + i);
                team.addEntry(entry);
                created.getScore(entry).setScore(5 - i);
            }
            this.playerBoards.put(player.getUniqueId(), board);
        }
        Objective objective = board.getObjective("kelvora");
        if (objective == null) {
            objective = board.registerNewObjective("kelvora", "dummy", Component.text("KELVORA SMP", NamedTextColor.GREEN));
            objective.setDisplaySlot(DisplaySlot.SIDEBAR);
        } else {
            objective.displayName(Component.text("KELVORA SMP", NamedTextColor.GREEN));
        }
        String moneyLine = Double.isFinite(balance) ? "$" + moneyCompact : "Not connected";
        String[] lines = new String[]{
            this.color("&fRank &r" + rankColor + rankLabel),
            this.color("&fMoney &a" + moneyLine),
            this.color("&fKelvoras &b" + kelvoras),
            this.color("&fOnline &a" + Bukkit.getOnlinePlayers().size() + "&7/" + Bukkit.getMaxPlayers()),
            this.color("&7" + serverAddress)
        };
        for (int i = 0; i < lines.length; ++i) {
            Team team = board.getTeam("line" + i);
            if (team != null) team.setPrefix(this.trimLegacy(lines[i], 64));
        }
        Scoreboard current = player.getScoreboard();
        if (current == Bukkit.getScoreboardManager().getMainScoreboard()
                || current == board || current.getObjective("kelvora") != null) {
            if (current != board) player.setScoreboard(board);
        }
    }

    private String rankKey(Player player) {
        if (this.luckPerms == null) {
            return "member";
        }
        User user = this.luckPerms.getUserManager().getUser(player.getUniqueId());
        if (user == null) {
            return "member";
        }
        String group = user.getPrimaryGroup();
        if (group == null || group.isBlank()) {
            return "member";
        }
        group = group.toLowerCase(Locale.ROOT);
        ConfigurationSection ranks = this.getConfig().getConfigurationSection("ranks");
        if (ranks != null && ranks.contains(group)) {
            return group;
        }
        return "member";
    }

    private String getKelvoras(Player player) {
        String placeholder = this.getConfig().getString("kelvoras.placeholder", "%kelvoras_balance%");
        if (!this.placeholderApi) return "Not connected";
        try {
            Plugin papiPlugin = Bukkit.getPluginManager().getPlugin("PlaceholderAPI");
            if (papiPlugin == null) return "Not connected";
            Class<?> papi = Class.forName("me.clip.placeholderapi.PlaceholderAPI", true, papiPlugin.getClass().getClassLoader());
            String value = (String) papi.getMethod("setPlaceholders", Player.class, String.class).invoke(null, player, placeholder);
            if (value == null || value.isBlank() || value.equals(placeholder) || value.startsWith("%") && value.endsWith("%")) return "Not connected";
            return ChatColor.stripColor(value);
        } catch (ReflectiveOperationException | LinkageError ex) {
            return "Not connected";
        }
    }

    private String formatExactMoney(double amount) {
        return new DecimalFormat("#,##0.00").format(amount);
    }

    private String formatMoney(double amount) {
        double value = Math.abs(amount);
        double scaled = amount;
        String suffix = "";
        if (value >= 1.0E9) {
            scaled = amount / 1.0E9;
            suffix = "B";
        } else if (value >= 1000000.0) {
            scaled = amount / 1000000.0;
            suffix = "M";
        } else if (value >= 1000.0) {
            scaled = amount / 1000.0;
            suffix = "K";
        }
        String number = String.format(Locale.US, "%.1f", scaled).replaceAll("\\.0$", "");
        if (suffix.isEmpty()) {
            number = String.format(Locale.US, "%,.2f", amount).replaceAll("\\.00$", "");
        }
        return number + suffix;
    }

    private String color(String value) {
        return ChatColor.translateAlternateColorCodes((char)'&', (String)(value == null ? "" : value));
    }

    private String trimLegacy(String value, int maxLength) {
        if (value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }
}

