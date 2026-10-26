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
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.model.user.User;
import net.luckperms.api.node.types.InheritanceNode;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
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
import org.bukkit.inventory.ItemStack;

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
    private StaffFeaturesManager staffFeaturesManager;
    private int refreshTask;
    private int tabFrame;
    private int footerFrame;
    private final Map<UUID, Scoreboard> playerBoards = new HashMap<UUID, Scoreboard>();
    private final Map<UUID, Long> lastMovementAt = new HashMap<>();
    private final Set<UUID> afkPlayers = new java.util.HashSet<>();
    private static final long AFK_AFTER_MS = 30L * 60L * 1000L;

    public void onEnable() {
        this.saveDefaultConfig();
        this.getConfig().options().copyDefaults(true);
        // One-time economy price migration: keep /worth and shop pricing aligned on existing servers.
        if (this.getConfig().getInt("economy.price-schema-version", 0) < 1) {
            for (String item : java.util.List.of("TOTEM_OF_UNDYING", "SHULKER_SHELL", "SHULKER_BOX", "WHITE_SHULKER_BOX", "ORANGE_SHULKER_BOX", "MAGENTA_SHULKER_BOX", "LIGHT_BLUE_SHULKER_BOX", "YELLOW_SHULKER_BOX", "LIME_SHULKER_BOX", "PINK_SHULKER_BOX", "GRAY_SHULKER_BOX", "LIGHT_GRAY_SHULKER_BOX", "CYAN_SHULKER_BOX", "PURPLE_SHULKER_BOX", "BLUE_SHULKER_BOX", "BROWN_SHULKER_BOX", "GREEN_SHULKER_BOX", "RED_SHULKER_BOX", "BLACK_SHULKER_BOX")) {
                this.getConfig().set("prices." + item, 250);
            }
            this.getConfig().set("economy.price-schema-version", 1);
        }
        this.saveConfig();
        this.setupIntegrations();
        this.kelvorasManager = new KelvorasManager(this);
        this.kelvorasManager.register();
        new Phase32Commands(this, this.kelvorasManager).register();
        this.worthManager = new WorthManager(this);
        this.worthManager.register();
        new ShopSellManager(this).register();
        new AuctionHouseManager(this).register();
        BountyCombatManager combatManager = new BountyCombatManager(this);
        combatManager.register();
        new RtqManager(this, combatManager).register();
        new BaltopManager(this).register();
        new CoinflipManager(this, combatManager).register();
        new RtpManager(this, combatManager).register();
        new HomesManager(this, combatManager).register();
        new TeleportSettingsManager(this, combatManager).register();
        new EconomyFeaturesManager(this).register();
        new KelvoraQualityManager(this, this.kelvorasManager).register();
        this.teamManager = new TeamManager(this);
        this.teamManager.register();
        new RankKitManager(this).register();
        this.shardShopManager = new ShardShopManager(this, this.kelvorasManager);
        this.shardShopManager.register();
        this.staffFeaturesManager = new StaffFeaturesManager(this);
        this.staffFeaturesManager.register();
        new ChatSpamProtectionManager(this).register();
        CrateManager crateManager = new CrateManager(this);
        crateManager.register();
        new RankDailyKeysManager(this, crateManager).register();
        new GambleManager(this, this.kelvorasManager).register();
        new LeaderboardManager(this, this.kelvorasManager).register();
        new StatsManager(this, this.kelvorasManager).register(this);
        new SpecialToolsManager(this, this.kelvorasManager, this.worthManager).register();
        if (this.getCommand("discord") != null) this.getCommand("discord").setExecutor((sender, command, label, args) -> {
            sender.sendMessage(Component.text("Join the Kelvora SMP Discord: ", NamedTextColor.LIGHT_PURPLE)
                .append(Component.text("https://discord.gg/gUt2uGcwe", NamedTextColor.AQUA)
                    .clickEvent(net.kyori.adventure.text.event.ClickEvent.openUrl("https://discord.gg/gUt2uGcwe"))));
            return true;
        });
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
        this.registerModerationCommands();
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
        Player player = event.getPlayer();
        this.lastMovementAt.put(player.getUniqueId(), System.currentTimeMillis());
        this.afkPlayers.remove(player.getUniqueId());
        Bukkit.getScheduler().runTask((Plugin)this, () -> {
            this.updatePlayer(player);
            String ip = this.getConfig().getString("server-address", "Configure server-address");
            player.sendTitle("§a§lJOIN KELVORA SMP", "§fIP: §e" + ip, 10, 80, 20);
            player.sendMessage(Component.text("━━━━━━━━━━━━━━━━━━━━━━━━━━━━", NamedTextColor.DARK_GREEN));
            player.sendMessage(Component.text("🌿 JOIN KELVORA SMP", NamedTextColor.GREEN).decorate(net.kyori.adventure.text.format.TextDecoration.BOLD));
            player.sendMessage(Component.text("Server IP: ", NamedTextColor.WHITE).append(Component.text(ip, NamedTextColor.YELLOW).decorate(net.kyori.adventure.text.format.TextDecoration.BOLD)));
            player.sendMessage(Component.text("Java + Bedrock • Use /discord to join our community.", NamedTextColor.GRAY));
            player.sendMessage(Component.text("━━━━━━━━━━━━━━━━━━━━━━━━━━━━", NamedTextColor.DARK_GREEN));
        });
    }

    @EventHandler
    public void onPlayerMove(PlayerMoveEvent event) {
        if (event.getTo() == null) return;
        Location from = event.getFrom();
        Location to = event.getTo();
        if (from.getWorld() == null || to.getWorld() == null) return;
        // Head turns do not count as movement; only changing position resets the 30-minute AFK timer.
        if (from.getWorld().equals(to.getWorld()) && from.getBlockX() == to.getBlockX()
                && from.getBlockY() == to.getBlockY() && from.getBlockZ() == to.getBlockZ()) return;
        UUID id = event.getPlayer().getUniqueId();
        this.lastMovementAt.put(id, System.currentTimeMillis());
        if (this.afkPlayers.remove(id)) {
            Bukkit.getScheduler().runTask(this, () -> {
                Player player = Bukkit.getPlayer(id);
                if (player != null) this.updatePlayer(player);
            });
        }
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
        this.lastMovementAt.remove(player.getUniqueId());
        this.afkPlayers.remove(player.getUniqueId());
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
        String rank = this.rankKey(player);
        String defaultRankLabel = rank.toUpperCase(Locale.ROOT);
        String rankLabel = this.getConfig().getString("ranks." + rank + ".label", defaultRankLabel);
        String rankColor = this.getConfig().getString("ranks." + rank + ".color", "&a");
        ShardShopManager cosmetics = this.shardShopManager;
        String cosmeticTag = cosmetics == null ? null : cosmetics.equippedTagFormatted(player);
        String cosmeticSuffix = cosmeticTag == null ? "" : " " + cosmeticTag;
        long now = System.currentTimeMillis();
        long lastMovement = this.lastMovementAt.computeIfAbsent(player.getUniqueId(), ignored -> now);
        boolean afk = now - lastMovement >= AFK_AFTER_MS;
        if (afk) this.afkPlayers.add(player.getUniqueId());
        else this.afkPlayers.remove(player.getUniqueId());
        String extraRoles = this.staffFeaturesManager == null ? "" : this.staffFeaturesManager.tabLabels(player);
        String liveLabel = this.staffFeaturesManager == null ? "" : this.staffFeaturesManager.liveTabLabel(player);
        String afkLabel = afk ? this.color("&8[&7&lAFK&8]") + " " : "";
        player.setPlayerListName(afkLabel + (extraRoles.isBlank() ? this.color(rankColor + "[" + rankLabel + "]") : this.color(extraRoles)) + " " + player.getName() + cosmeticSuffix + (liveLabel.isBlank() ? "" : " " + liveLabel));

        double balance = this.economyBalance(player);
        String moneyCompact = Double.isFinite(balance) ? this.formatMoney(balance) : "Not connected";
        String kelvoras = this.getKelvoras(player);
        Component rotatingColor = Component.text("\n\n🌿 K E L V O R A  S M P",
            switch (this.tabFrame) {
                case 0 -> net.kyori.adventure.text.format.TextColor.color(0x008000);
                case 1 -> NamedTextColor.DARK_GREEN;
                default -> net.kyori.adventure.text.format.TextColor.color(0x7CFC00);
            }).decorate(net.kyori.adventure.text.format.TextDecoration.BOLD)
              .decorate(net.kyori.adventure.text.format.TextDecoration.UNDERLINED);
        Component rotatingMessage = rotatingColor.append(Component.text("\n━━━━━━━━━━━━━━━━━━━━━━━━\n", NamedTextColor.DARK_GREEN));
        Component footerMessage = switch (this.footerFrame) {
            case 0 -> Component.text("💬 JOIN OUR DISCORD\nType /discord to get the invite and join us", NamedTextColor.LIGHT_PURPLE)
                .clickEvent(net.kyori.adventure.text.event.ClickEvent.openUrl("https://discord.gg/gUt2uGcwe"));
            case 1 -> Component.text("🛡️ APPLY FOR STAFF\nOpen a Discord ticket to apply", NamedTextColor.GOLD)
                .clickEvent(net.kyori.adventure.text.event.ClickEvent.openUrl("https://discord.gg/gUt2uGcwe"));
            case 2 -> Component.text("🎥 APPLY FOR MEDIA\nMeet the requirements, then open a ticket", NamedTextColor.AQUA)
                .clickEvent(net.kyori.adventure.text.event.ClickEvent.openUrl("https://discord.gg/gUt2uGcwe"));
            case 3 -> Component.text("⚔️ JOIN KELVORA SMP\nJava + Bedrock • Cracked players welcome", NamedTextColor.GREEN);
            default -> Component.text("🌿 Join Kelvora SMP\nJava + Bedrock • Cracked players welcome", NamedTextColor.YELLOW);
        };
        player.sendPlayerListHeaderAndFooter(rotatingMessage, footerMessage);

        Scoreboard board = this.playerBoards.get(player.getUniqueId());
        if (board == null) {
            Scoreboard externalBoard = player.getScoreboard();
            board = Bukkit.getScoreboardManager().getNewScoreboard();
            for (Team sourceTeam : externalBoard.getTeams()) {
                if (board.getTeam(sourceTeam.getName()) != null) continue;
                Team targetTeam = board.registerNewTeam(sourceTeam.getName());
                targetTeam.setPrefix(sourceTeam.getPrefix());
                targetTeam.setSuffix(sourceTeam.getSuffix());
                targetTeam.setColor(sourceTeam.getColor());
                for (String entry : sourceTeam.getEntries()) targetTeam.addEntry(entry);
            }
            Objective created = board.registerNewObjective("kelvora", "dummy", Component.text("✦ KELVORA ", NamedTextColor.GREEN).decorate(net.kyori.adventure.text.format.TextDecoration.BOLD).append(Component.text("SMP", NamedTextColor.WHITE).decorate(net.kyori.adventure.text.format.TextDecoration.BOLD)));
            created.setDisplaySlot(DisplaySlot.SIDEBAR);
            for (int i = 0; i < 9; ++i) {
                String entry = ChatColor.values()[i].toString() + ChatColor.RESET;
                Team team = board.registerNewTeam("line" + i);
                team.addEntry(entry);
                org.bukkit.scoreboard.Score score = created.getScore(entry);
                score.setScore(7 - i);
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
            this.color("&c⚔ Kills: &f" + kills),
            this.color("&4☠ Deaths: &f" + deaths),
            this.color("&b⏱ Playtime: &f" + playtime),
            this.color("&3🛡 Team: &b" + teamName),
            this.color("&e👥 Online: &f" + Bukkit.getOnlinePlayers().size() + "&7/" + Bukkit.getMaxPlayers()),
            this.color("&7IP: &f" + this.getConfig().getString("server-address", "Configure server-address"))
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
        // Keep cosmetic glow teams intact while adding compact Money + Team labels above players.
        if (this.shardShopManager != null) this.shardShopManager.refreshCosmetics(player);
        this.updateNameplateTeams(player, moneyCompact, teamName, afk);
    }

    private void updateNameplateTeams(Player target, String moneyCompact, String teamName, boolean afk) {
        String uuidPart = target.getUniqueId().toString().substring(0, 8);
        String headTeamName = "khead_" + uuidPart;
        String glowTeamName = "ksglow_" + uuidPart;
        String prefix = this.color("&a$" + moneyCompact + " &8» ");
        String suffix = this.color(" &8[&b" + teamName + "&8]" + (afk ? " &8[&7&lAFK&8]" : ""));
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            Scoreboard board = viewer.getScoreboard();
            Team glowTeam = board.getTeam(glowTeamName);
            boolean glowing = glowTeam != null && glowTeam.hasEntry(target.getName());
            Team displayTeam = glowing ? glowTeam : board.getTeam(headTeamName);
            if (displayTeam == null) displayTeam = board.registerNewTeam(glowing ? glowTeamName : headTeamName);
            for (Team other : new java.util.ArrayList<>(board.getTeams())) {
                if (!other.getName().equals(displayTeam.getName()) && other.hasEntry(target.getName())
                        && (other.getName().startsWith("khead_") || other.getName().startsWith("ksglow_"))) {
                    other.removeEntry(target.getName());
                }
            }
            // If a different plugin already assigned the entry, move it so the requested nameplate is visible.
            for (Team other : new java.util.ArrayList<>(board.getTeams())) {
                if (!other.getName().equals(displayTeam.getName()) && other.hasEntry(target.getName())) other.removeEntry(target.getName());
            }
            displayTeam.setPrefix(this.trimLegacy(prefix, 64));
            displayTeam.setSuffix(this.trimLegacy(suffix, 64));
            if (!displayTeam.hasEntry(target.getName())) displayTeam.addEntry(target.getName());
        }
    }

    // Remove external scoreboard prefix/suffix text from player nameplates (e.g. Money/Team).
    // TAB-list ranks are handled separately with setPlayerListName above.
    private void clearNameplateInfo(Scoreboard board) {
        if (board == null) return;
        Set<String> onlineNames = new java.util.HashSet<>();
        for (Player online : Bukkit.getOnlinePlayers()) onlineNames.add(online.getName());
        for (Team team : board.getTeams()) {
            boolean isPlayerTeam = team.getEntries().stream().anyMatch(onlineNames::contains);
            if (!isPlayerTeam) continue;
            if (!team.getPrefix().isEmpty()) team.setPrefix("");
            if (!team.getSuffix().isEmpty()) team.setSuffix("");
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
        if (group == null || group.isBlank()) return "sprout";
        group = group.toLowerCase(Locale.ROOT);
        // Progression is independent of staff/media groups: prefer the actual inherited progression rank.
        for (String progression : new String[]{"ascendant", "sovereign", "emerald", "verdant", "sprout", "i_sprout"}) {
            if (user.getNodes().stream().anyMatch(node -> node instanceof InheritanceNode inheritance && inheritance.getGroupName().equalsIgnoreCase(progression))) {
                return progression.equals("i_sprout") ? "sprout" : progression;
            }
        }
        if (Set.of("helper", "moderator", "mod", "admin", "owner", "creator", "default", "member", "i_sprout").contains(group)) return "member";
        if (group.equals("vip")) return "verdant";
        ConfigurationSection ranks = this.getConfig().getConfigurationSection("ranks");
        if (ranks != null && ranks.contains(group) && !Set.of("helper", "moderator", "admin", "owner", "creator").contains(group)) return group;
        return "member";
    }

    KelvorasManager passiveKelvorasManager() { return this.kelvorasManager; }
    ShardShopManager getShardShopManager() { return this.shardShopManager; }

    private String displayRankKey(Player player) {
        return this.rankKey(player);
    }
    boolean hasRoleGroup(Player player, String groupName) {
        if (this.luckPerms == null) return false;
        User user = this.luckPerms.getUserManager().getUser(player.getUniqueId());
        return user != null && user.getNodes().stream().anyMatch(node ->
            node instanceof InheritanceNode inheritance && inheritance.getGroupName().equalsIgnoreCase(groupName));
    }
    TeamManager getTeamManager() { return this.teamManager; }
    void refreshSidebar(Player player) { if (player != null && player.isOnline()) updatePlayer(player); }

    Component chatRankPrefix(Player player) {
        String key = this.rankKey(player);
        String label = this.getConfig().getString("ranks." + key + ".label", key.toUpperCase(Locale.ROOT));
        String colour = this.getConfig().getString("ranks." + key + ".color", "&a");
        String roleLabels = this.staffFeaturesManager == null ? "" : this.staffFeaturesManager.tabLabels(player);
        String combined = roleLabels.isBlank() ? colour + "[" + label + "] " : roleLabels + " ";
        return LegacyComponentSerializer.legacyAmpersand().deserialize(combined);
    }

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

    private void registerModerationCommands() {
        if (this.getCommand("ban") != null) this.getCommand("ban").setExecutor((sender, command, label, args) -> {
            if (!sender.isOp() && !sender.hasPermission("kelvora.staff.ban")) {
                sender.sendMessage(Component.text("You do not have permission.", NamedTextColor.RED)); return true;
            }
            if (args.length < 2 || !args[0].matches("[A-Za-z0-9_]{3,16}")) {
                sender.sendMessage(Component.text("Usage: /ban <player> <reason>", NamedTextColor.RED)); return true;
            }
            String target = args[0];
            String reason = String.join(" ", java.util.Arrays.copyOfRange(args, 1, args.length));
            Bukkit.getBanList(org.bukkit.BanList.Type.NAME).addBan(target, reason, null, sender.getName());
            Player online = Bukkit.getPlayerExact(target);
            if (online != null) online.kick(Component.text("You are banned from Kelvora SMP.\nReason: " + reason, NamedTextColor.RED));
            sender.sendMessage(Component.text("Banned " + target + ". Reason: " + reason, NamedTextColor.GREEN));
            return true;
        });
        if (this.getCommand("banip") != null) this.getCommand("banip").setExecutor((sender, command, label, args) -> {
            if (!sender.isOp() && !sender.hasPermission("kelvora.staff.ban")) {
                sender.sendMessage(Component.text("You do not have permission.", NamedTextColor.RED)); return true;
            }
            if (args.length < 2) {
                sender.sendMessage(Component.text("Usage: /banip <online-player|IP-address> <reason>", NamedTextColor.RED)); return true;
            }
            String target = args[0];
            Player online = Bukkit.getPlayerExact(target);
            String ip = target;
            if (online != null && online.getAddress() != null && online.getAddress().getAddress() != null) {
                ip = online.getAddress().getAddress().getHostAddress();
            } else if (!target.matches("[0-9a-fA-F:.]{3,45}")) {
                sender.sendMessage(Component.text("Target must be online or you must provide an IP address.", NamedTextColor.RED)); return true;
            }
            String reason = String.join(" ", java.util.Arrays.copyOfRange(args, 1, args.length));
            Bukkit.getBanList(org.bukkit.BanList.Type.IP).addBan(ip, reason, null, sender.getName());
            if (online != null) online.kick(Component.text("Your IP is banned from Kelvora SMP.\nReason: " + reason, NamedTextColor.RED));
            sender.sendMessage(Component.text("IP ban added.", NamedTextColor.GREEN));
            return true;
        });
    }

    @EventHandler(priority = org.bukkit.event.EventPriority.HIGHEST, ignoreCancelled = true)
    public void preventZStaffPickup(org.bukkit.event.entity.EntityPickupItemEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        ItemStack stack = event.getItem().getItemStack();
        if (stack == null || !stack.hasItemMeta() || stack.getItemMeta().displayName() == null) return;
        String itemName = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
            .serialize(stack.getItemMeta().displayName()).toLowerCase(Locale.ROOT);
        if (itemName.contains("zstaff") && !player.isOp() && !player.hasPermission("kelvora.staff.zstaff") && !player.hasPermission("kelvora.staff.mod") && !player.hasPermission("kelvora.staff.manager") && !player.hasPermission("kelvora.roles.manage") && !player.hasPermission("kelvora.suslist") && !this.hasRoleGroup(player, "helper") && !this.hasRoleGroup(player, "moderator") && !this.hasRoleGroup(player, "manager") && !this.hasRoleGroup(player, "admin") && !this.hasRoleGroup(player, "owner")) {
            event.setCancelled(true);
        }
    }

}
