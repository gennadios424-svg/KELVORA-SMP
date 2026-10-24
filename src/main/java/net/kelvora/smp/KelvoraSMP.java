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
import java.text.DecimalFormatSymbols;
import java.util.HashMap;
import java.util.Locale;
import java.util.Set;
import java.util.Map;
import java.util.UUID;
import net.kelvora.smp.WorthManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.model.user.User;
import net.luckperms.api.node.types.InheritanceNode;
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
import org.bukkit.Statistic;

public final class KelvoraSMP
extends JavaPlugin
implements Listener {
    private Object economy;
    private LuckPerms luckPerms;
    private boolean placeholderApi;
    private KelvorasManager kelvorasManager;
    private WorthManager worthManager;
    private ShardShopManager shardShopManager;
    private TeamManager teamManager;
    private int refreshTask;
    private int tabFrame;
    private int footerFrame;
    private final Map<UUID, Scoreboard> playerBoards = new HashMap<UUID, Scoreboard>();

    public void onEnable() {
        this.saveDefaultConfig();
        this.getConfig().options().copyDefaults(true);
        this.saveConfig();
        this.setupIntegrations();
        this.kelvorasManager = new KelvorasManager(this);
        this.kelvorasManager.register();
        new Phase32Commands(this, this.kelvorasManager).register();
        this.worthManager = new WorthManager(this);
        this.worthManager.register();
        new MetaManager(this, this.worthManager).register();
        new ShopSellManager(this).register();
        new AuctionHouseManager(this).register();
        BountyCombatManager combatManager = new BountyCombatManager(this);
        combatManager.register();
        new TpaManager(this, combatManager).register();
        new LiveManager(this).register();
        new RtqManager(this, combatManager).register();
        new BaltopManager(this).register();
        new CoinflipManager(this, combatManager).register();
        new RtpManager(this, combatManager).register();
        new HomesManager(this, combatManager).register();
        this.teamManager = new TeamManager(this);
        this.teamManager.register();
        new RankKitManager(this).register();
        this.shardShopManager = new ShardShopManager(this, this.kelvorasManager);
        this.shardShopManager.register();
        CrateManager crateManager = new CrateManager(this);
        crateManager.register();
        new RankDailyKeysManager(this, crateManager).register();
        new GambleManager(this, this.kelvorasManager).register();
        new LeaderboardManager(this, this.kelvorasManager).register();
        new StatsManager(this, this.kelvorasManager).register(this);
        new SpecialToolsManager(this, this.kelvorasManager, this.worthManager).register();
        if (this.getCommand("bal") != null) {
            this.getCommand("bal").setExecutor((sender, command, label, args) -> {
                if (!(sender instanceof Player player)) { sender.sendMessage("Use this command in-game."); return true; }
                if (args.length != 0) { player.sendMessage(Component.text("Usage: /bal", NamedTextColor.RED)); return true; }
                if (!player.hasPermission("kelvora.balance")) { player.sendMessage(Component.text("You do not have permission.", NamedTextColor.RED)); return true; }
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
            for (Player player : Bukkit.getOnlinePlayers()) this.updatePlayer(player);
        }, 1L, Math.max(20L, this.getConfig().getLong("refresh-ticks", 40L)));
        Bukkit.getScheduler().runTaskTimer(this, () -> {
            this.tabFrame = (this.tabFrame + 1) % 3;
            this.footerFrame = (this.footerFrame + 1) % 5;
            for (Player online : Bukkit.getOnlinePlayers()) this.updatePlayer(online);
        }, 60L, 60L);
        for (Player player : Bukkit.getOnlinePlayers()) {
            this.updatePlayer(player);
        }
        new PassiveKelvorasManager(this).register();
        this.getLogger().info("Kelvora SMP presentation enabled.");
    }

    public void onDisable() {
        if (this.kelvorasManager != null) this.kelvorasManager.save();
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
            this.getLogger().info("PlaceholderAPI not found; legacy Kelvoras migration will be skipped, and the persistent local balance system will still work.");
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
        String staffRole = this.staffRoleKey(player);
        if (staffRole != null) {
            String staffLabel = this.getConfig().getString("ranks." + staffRole + ".label", staffRole.toUpperCase(Locale.ROOT));
            String staffColor = this.getConfig().getString("ranks." + staffRole + ".color", "&b");
            prefix = staffColor + "[" + staffLabel + "] ";
        } else if (this.hasLuckPermsGroup(player, "booster")) {
            prefix = this.getConfig().getString("ranks.booster.color", "&5") + "[Booster] ";
        } else if (prefix == null || prefix.isBlank()) prefix = rankColor + "[" + rankLabel + "] ";
        ShardShopManager cosmetics = this.shardShopManager;
        String cosmeticTitle = cosmetics == null ? null : cosmetics.equippedTitle(player);
        String cosmeticTag = cosmetics == null ? null : cosmetics.equippedTag(player);
        String cosmeticPrefix = cosmeticTitle == null ? "" : this.color("&6{" + cosmeticTitle + "} ");
        String cosmeticSuffix = cosmeticTag == null ? "" : this.color(" &b[" + cosmeticTag + "]");
        // Always show the actual progression rank, and retain a separate LuckPerms staff/media prefix if it is different.
        String plainPrefix = ChatColor.stripColor(ChatColor.translateAlternateColorCodes('&', prefix));
        boolean prefixShowsProgression = plainPrefix != null && plainPrefix.toLowerCase(Locale.ROOT).contains(rankLabel.toLowerCase(Locale.ROOT));
        String progressionPrefix = this.color(rankColor + "[" + rankLabel + "] ");
        player.setPlayerListName(progressionPrefix + (prefixShowsProgression ? "" : this.color(prefix)) + cosmeticPrefix + player.getName() + cosmeticSuffix);

        double balance = this.economyBalance(player);
        String moneyCompact = Double.isFinite(balance) ? this.formatMoney(balance) : "Not connected";
        String kelvoras = this.getKelvoras(player);
        Component rotatingMessage = switch (this.tabFrame) {
            case 0 -> Component.text("🌿 KELVORA SMP", net.kyori.adventure.text.format.TextColor.color(0x008000));
            case 1 -> Component.text("🌿 KELVORA SMP", NamedTextColor.DARK_GREEN);
            default -> Component.text("🌿 KELVORA SMP", net.kyori.adventure.text.format.TextColor.color(0x7CFC00));
        };
        Component footerMessage = switch (this.footerFrame) {
            case 0 -> Component.text("💬 JOIN OUR DISCORD", NamedTextColor.LIGHT_PURPLE)
                .clickEvent(net.kyori.adventure.text.event.ClickEvent.openUrl("https://discord.gg/gUt2uGcwe"));
            case 1 -> Component.text("🛡️ APPLY FOR STAFF", NamedTextColor.GOLD);
            case 2 -> Component.text("🎥 APPLY FOR MEDIA", NamedTextColor.AQUA);
            case 3 -> Component.text("⚔️ JOIN KELVORA SMP", NamedTextColor.GREEN);
            default -> Component.text("🌐 kelvorasmp.mcsh.gg", NamedTextColor.YELLOW);
        };
        player.sendPlayerListHeaderAndFooter(rotatingMessage, footerMessage);

        Scoreboard board = this.playerBoards.get(player.getUniqueId());
        if (board == null) {
            board = Bukkit.getScoreboardManager().getNewScoreboard();
            Objective created = board.registerNewObjective("kelvora", "dummy", Component.text("🌿 KELVORA ", NamedTextColor.GREEN).append(Component.text("SMP", NamedTextColor.WHITE)));
            created.setDisplaySlot(DisplaySlot.SIDEBAR);
            for (int i = 0; i < 9; ++i) {
                String entry = ChatColor.values()[i].toString() + ChatColor.RESET;
                Team team = board.registerNewTeam("line" + i);
                team.addEntry(entry);
                org.bukkit.scoreboard.Score score = created.getScore(entry);
                score.setScore(9 - i);
                score.numberFormat(io.papermc.paper.scoreboard.numbers.NumberFormat.blank());
            }
            this.playerBoards.put(player.getUniqueId(), board);
        }
        Objective objective = board.getObjective("kelvora");
        if (objective == null) {
            objective = board.registerNewObjective("kelvora", "dummy", Component.text("🌿 KELVORA ", NamedTextColor.GREEN).append(Component.text("SMP", NamedTextColor.WHITE)));
            objective.setDisplaySlot(DisplaySlot.SIDEBAR);
        } else {
            objective.displayName(Component.text("🌿 KELVORA ", NamedTextColor.GREEN).append(Component.text("SMP", NamedTextColor.WHITE)));
        }
        int kills = player.getStatistic(Statistic.PLAYER_KILLS);
        int deaths = player.getStatistic(Statistic.DEATHS);
        long playMinutes = player.getStatistic(Statistic.PLAY_ONE_MINUTE) / 1200L;
        String playtime = playMinutes >= 60 ? (playMinutes / 60) + "h " + (playMinutes % 60) + "m" : playMinutes + "m";
        String teamName = this.teamManager == null ? "No Team" : this.teamManager.teamName(player);
        // Keep the two authoritative currencies immediately under the server title.
        String rankDisplay = rankLabel.toUpperCase(Locale.ROOT);
        String[] lines = new String[]{
            this.color("&6&l⭐ RANK: " + rankColor + "&l" + rankDisplay),
            this.color("&a&l💰 Money: &f" + (Double.isFinite(balance) ? "$" + moneyCompact : "Not connected")),
            this.color("&d&l💎 Kelvoras: &d" + (this.kelvorasManager == null ? "0" : KelvorasManager.format(this.kelvorasManager.balance(player)))),
            this.color("&3🛡 Team: &b" + teamName),
            this.color("&c⚔ Kills: &f" + kills),
            this.color("&4☠ Deaths: &c" + deaths),
            this.color("&9⏱ Playtime: &b" + playtime),
            this.color("&e👥 Online: &f" + Bukkit.getOnlinePlayers().size() + "&7/" + Bukkit.getMaxPlayers()),
            this.color("&7🌐 IP: &f" + serverAddress)
        };
        for (int i = 0; i < lines.length; ++i) {
            Team team = board.getTeam("line" + i);
            if (team != null) team.setPrefix(this.trimLegacy(lines[i], 64));
        }
        Scoreboard current = player.getScoreboard();
        // Remove below-name score objectives (often wired to Vault balance placeholders); the sidebar remains untouched.
        this.removeBelowNameObjectives(Bukkit.getScoreboardManager().getMainScoreboard());
        if (current != Bukkit.getScoreboardManager().getMainScoreboard()) this.removeBelowNameObjectives(current);
        if (current == Bukkit.getScoreboardManager().getMainScoreboard()
                || current == board || current.getObjective("kelvora") != null) {
            if (current != board) player.setScoreboard(board);
        }
    }

    private boolean hasLuckPermsGroup(Player player, String group) {
        if (this.luckPerms == null) return false;
        User user = this.luckPerms.getUserManager().getUser(player.getUniqueId());
        if (user == null) return false;
        if (group.equalsIgnoreCase(user.getPrimaryGroup())) return true;
        return user.getNodes().stream().anyMatch(node -> node instanceof InheritanceNode inheritance
            && inheritance.getGroupName().equalsIgnoreCase(group));
    }

    private String staffRoleKey(Player player) {
        // Check highest staff role first; staff/supporter groups never replace progression rank.
        for (String role : new String[]{"owner", "admin", "manager", "moderator", "mod", "helper"}) {
            if (this.hasLuckPermsGroup(player, role)) return role.equals("mod") ? "moderator" : role;
        }
        return null;
    }

    private void removeBelowNameObjectives(Scoreboard scoreboard) {
        for (Objective objective : new java.util.ArrayList<>(scoreboard.getObjectives())) {
            if (objective.getDisplaySlot() == DisplaySlot.BELOW_NAME) objective.unregister();
        }
    }

    private String rankKey(Player player) {
        if (this.luckPerms == null) {
            return "sprout";
        }
        User user = this.luckPerms.getUserManager().getUser(player.getUniqueId());
        if (user == null) {
            return "member";
        }
        String group = user.getPrimaryGroup();
        if (group == null || group.isBlank()) return "sprout";
        group = group.toLowerCase(Locale.ROOT);
        // Progression is independent of staff/media groups: prefer the actual inherited progression rank.
        for (String progression : new String[]{"ascendant", "sovereign", "emerald", "verdant", "booster", "sprout", "i_sprout", "member", "default"}) {
            if (user.getNodes().stream().anyMatch(node -> node instanceof InheritanceNode inheritance && inheritance.getGroupName().equalsIgnoreCase(progression))) {
                if (progression.equals("i_sprout")) return "sprout";
                if (progression.equals("default")) return "member";
                return progression;
            }
        }
        if (Set.of("helper", "moderator", "mod", "manager", "admin", "owner", "creator", "media").contains(group)) return "member";
        if (group.equals("vip")) return "verdant";
        ConfigurationSection ranks = this.getConfig().getConfigurationSection("ranks");
        if (ranks != null && ranks.contains(group) && !Set.of("helper", "moderator", "admin", "owner", "creator").contains(group)) return group;
        return "sprout";
    }

    KelvorasManager passiveKelvorasManager() { return this.kelvorasManager; }
    ShardShopManager getShardShopManager() { return this.shardShopManager; }
    TeamManager getTeamManager() { return this.teamManager; }
    void refreshSidebar(Player player) { if (player != null && player.isOnline()) updatePlayer(player); }

    private String getKelvoras(Player player) {
        if (this.kelvorasManager == null) return "✦0";
        return "✦" + KelvorasManager.format(this.kelvorasManager.balance(player));
    }

    private String formatExactMoney(double amount) {
        return new DecimalFormat("#,##0.00", DecimalFormatSymbols.getInstance(Locale.US)).format(amount);
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

