package net.kelvora.smp;

import java.io.File;
import java.io.IOException;
import java.util.*;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.model.user.User;
import net.luckperms.api.node.types.InheritanceNode;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.command.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

/** Persistent, rank-checked daily digital crate-key claims. */
final class RankDailyKeysManager {
    private static final List<String> RANKS = List.of("sprout", "verdant", "emerald", "sovereign", "ascendant");
    private final JavaPlugin plugin;
    private final CrateManager crates;
    private final File file;
    private YamlConfiguration claims;
    private LuckPerms luckPerms;

    RankDailyKeysManager(JavaPlugin plugin, CrateManager crates) {
        this.plugin = plugin;
        this.crates = crates;
        this.file = new File(plugin.getDataFolder(), "rank-key-claims.yml");
    }

    void register() {
        claims = YamlConfiguration.loadConfiguration(file);
        if (Bukkit.getPluginManager().isPluginEnabled("LuckPerms")) {
            try { luckPerms = LuckPermsProvider.get(); }
            catch (IllegalStateException ex) { plugin.getLogger().warning("Daily rank keys require LuckPerms."); }
        }
        plugin.getConfig().addDefault("rank-daily-keys.cooldown-hours", 24);
        plugin.getConfig().addDefault("rank-daily-keys.rewards.sprout", List.of("common:1"));
        plugin.getConfig().addDefault("rank-daily-keys.rewards.verdant", List.of("common:1", "kelvora:1"));
        plugin.getConfig().addDefault("rank-daily-keys.rewards.emerald", List.of("kelvora:2", "gold:1"));
        plugin.getConfig().addDefault("rank-daily-keys.rewards.sovereign", List.of("gold:2", "spawner:1"));
        plugin.getConfig().addDefault("rank-daily-keys.rewards.ascendant", List.of("crimson:1", "gold:2", "spawner:1"));
        plugin.getConfig().options().copyDefaults(true);
        plugin.saveConfig();
        for (String rank : RANKS) {
            PluginCommand command = plugin.getCommand(rank);
            if (command == null) {
                plugin.getLogger().severe("Missing rank daily-key command /" + rank + " in plugin.yml.");
                continue;
            }
            command.setExecutor((sender, cmd, label, args) -> claim(sender, rank, args));
        }
    }

    private boolean claim(CommandSender sender, String requestedRank, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Rank key claims can only be used in-game.");
            return true;
        }
        if (args.length != 0) {
            player.sendMessage(Component.text("Usage: /" + requestedRank, NamedTextColor.YELLOW));
            return true;
        }
        String actual = progressionRank(player);
        if (!requestedRank.equals(actual)) {
            player.sendMessage(Component.text("This daily key command is for the " + title(requestedRank) + " rank. Your eligible progression rank is " + (actual == null ? "not set" : title(actual)) + ".", NamedTextColor.RED));
            return true;
        }
        List<String> rewards = plugin.getConfig().getStringList("rank-daily-keys.rewards." + requestedRank);
        if (rewards.isEmpty()) {
            player.sendMessage(Component.text("No daily key rewards are configured for your rank. Please contact staff.", NamedTextColor.RED));
            return true;
        }
        long now = System.currentTimeMillis();
        String path = "claims." + player.getUniqueId() + "." + requestedRank;
        long previous = claims.getLong(path, 0L);
        long cooldown = Math.max(1L, plugin.getConfig().getLong("rank-daily-keys.cooldown-hours", 24L)) * 3_600_000L;
        if (previous + cooldown > now) {
            player.sendMessage(Component.text("You can claim /" + requestedRank + " again in " + duration(previous + cooldown - now) + ".", NamedTextColor.YELLOW));
            return true;
        }
        Map<String, Integer> parsed = new LinkedHashMap<>();
        for (String reward : rewards) {
            String[] bits = reward.split(":", 2);
            if (bits.length != 2) continue;
            String type = bits[0].toLowerCase(Locale.ROOT);
            int amount;
            try { amount = Integer.parseInt(bits[1]); } catch (NumberFormatException ex) { continue; }
            if (!List.of("common", "kelvora", "gold", "spawner", "crimson").contains(type) || amount < 1 || amount > 100000) continue;
            parsed.merge(type, amount, Integer::sum);
        }
        if (parsed.isEmpty()) {
            player.sendMessage(Component.text("The configured rewards are invalid. No keys were granted.", NamedTextColor.RED));
            return true;
        }
        claims.set(path, now);
        if (!save()) {
            claims.set(path, previous);
            player.sendMessage(Component.text("Could not safely save your claim. No keys were granted.", NamedTextColor.RED));
            return true;
        }
        if (!crates.grantDigitalKeys(player, parsed)) {
            claims.set(path, previous);
            save();
            player.sendMessage(Component.text("The key grant could not be saved. The cooldown was rolled back; no keys were granted.", NamedTextColor.RED));
            return true;
        }
        StringJoiner summary = new StringJoiner(", ");
        parsed.forEach((type, amount) -> summary.add(amount + " " + title(type) + " key" + (amount == 1 ? "" : "s")));
        player.sendMessage(Component.text("Daily rank reward claimed: " + summary + ". Next claim in 24 hours.", NamedTextColor.GREEN));
        return true;
    }

    private String progressionRank(Player player) {
        if (luckPerms == null) return null;
        try {
            User user = luckPerms.getUserManager().getUser(player.getUniqueId());
            if (user == null) return null;
            String best = null;
            String primary = user.getPrimaryGroup() == null ? "" : user.getPrimaryGroup().toLowerCase(Locale.ROOT);
            if (RANKS.contains(primary)) best = primary;
            for (var node : user.getNodes()) {
                if (!(node instanceof InheritanceNode inheritance)) continue;
                String group = inheritance.getGroupName().toLowerCase(Locale.ROOT);
                if (RANKS.contains(group) && (best == null || RANKS.indexOf(group) > RANKS.indexOf(best))) best = group;
            }
            return best;
        } catch (Exception ex) {
            plugin.getLogger().warning("Could not resolve progression rank for " + player.getName() + ": " + ex.getMessage());
            return null;
        }
    }

    private boolean save() {
        try { claims.save(file); return true; }
        catch (IOException ex) { plugin.getLogger().severe("Could not persist rank-key cooldowns: " + ex.getMessage()); return false; }
    }
    private String title(String value) { return value.substring(0, 1).toUpperCase(Locale.ROOT) + value.substring(1).toLowerCase(Locale.ROOT); }
    private String duration(long millis) {
        long total = Math.max(0, millis) / 60_000L;
        long days = total / 1440, hours = (total % 1440) / 60, minutes = total % 60;
        return (days > 0 ? days + "d " : "") + hours + "h " + minutes + "m";
    }
}
