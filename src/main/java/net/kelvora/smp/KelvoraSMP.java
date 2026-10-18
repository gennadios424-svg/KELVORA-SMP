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

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import me.clip.placeholderapi.PlaceholderAPI;
import net.kelvora.smp.WorthManager;
import net.kyori.adventure.text.Component;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.model.user.User;
import net.milkbowl.vault.economy.Economy;
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
    private Economy economy;
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
            player.setScoreboard(Bukkit.getScoreboardManager().getMainScoreboard());
            this.playerBoards.remove(player.getUniqueId());
            player.setPlayerListName(player.getName());
        }
        this.playerBoards.clear();
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
        if (Bukkit.getPluginManager().isPluginEnabled("Vault") && (registration = this.getServer().getServicesManager().getRegistration(Economy.class)) != null) {
            this.economy = (Economy)registration.getProvider();
        }
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
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        Bukkit.getScheduler().runTask((Plugin)this, () -> {
            player.setScoreboard(Bukkit.getScoreboardManager().getMainScoreboard());
            player.setPlayerListName(player.getName());
        });
    }

    private void updatePlayer(Player player) {
        Objective objective;
        String serverAddress = this.getConfig().getString("server-address", "Configure server address");
        String rank = this.rankKey(player);
        String rankLabel = this.getConfig().getString("ranks." + rank + ".label", rank.toUpperCase(Locale.ROOT));
        String rankColor = this.getConfig().getString("ranks." + rank + ".color", "&a");
        String prefix = this.color(rankColor + "[" + rankLabel + "] ");
        player.setPlayerListName(prefix + this.color("&f" + player.getName()));
        Scoreboard board = this.playerBoards.get(player.getUniqueId());
        if (board == null) {
            board = Bukkit.getScoreboardManager().getNewScoreboard();
            Objective created = board.registerNewObjective("kelvora", "dummy", this.color(this.getConfig().getString("scoreboard.title", "&a&lKELVORA SMP")));
            created.setDisplaySlot(DisplaySlot.SIDEBAR);
            for (int i = 0; i < 5; ++i) {
                String entry = ChatColor.values()[i].toString() + String.valueOf(ChatColor.RESET);
                Team team = board.registerNewTeam("line" + i);
                team.addEntry(entry);
                created.getScore(entry).setScore(5 - i);
            }
            this.playerBoards.put(player.getUniqueId(), board);
        }
        if ((objective = board.getObjective("kelvora")) == null) {
            objective = board.registerNewObjective("kelvora", "dummy", this.color(this.getConfig().getString("scoreboard.title", "&a&lKELVORA SMP")));
            objective.setDisplaySlot(DisplaySlot.SIDEBAR);
        } else {
            objective.displayName((Component)Component.text((String)this.color(this.getConfig().getString("scoreboard.title", "&a&lKELVORA SMP"))));
        }
        String money = this.economy == null ? this.color("&cNot connected") : this.formatMoney(this.economy.getBalance((OfflinePlayer)player));
        String kelvoras = this.getKelvoras(player);
        String[] lines = new String[]{this.color("&fRank &r" + rankColor + rankLabel), this.color("&fMoney &a$" + money), this.color("&fKelvoras &b" + kelvoras), this.color("&fOnline &a" + Bukkit.getOnlinePlayers().size() + "&7/" + Bukkit.getMaxPlayers()), this.color("&7kelvorasmp.mcsh.gg")};
        for (int i = 0; i < lines.length; ++i) {
            Team team = board.getTeam("line" + i);
            if (team == null) continue;
            team.setPrefix(this.trimLegacy(lines[i], 64));
        }
        if (player.getScoreboard() != board) {
            player.setScoreboard(board);
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
        if (!this.placeholderApi) {
            return this.color("&cNot connected");
        }
        String value = PlaceholderAPI.setPlaceholders((Player)player, (String)placeholder);
        if (value == null || value.isBlank() || value.equals(placeholder) || value.startsWith("%") && value.endsWith("%")) {
            return this.color("&cNot connected");
        }
        return value;
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

