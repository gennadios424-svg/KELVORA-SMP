package net.kelvora.smp;

import java.io.File;
import java.io.IOException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.*;
import org.bukkit.command.*;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

final class GambleManager implements Listener, CommandExecutor, TabCompleter {
    private final JavaPlugin plugin;
    private final KelvorasManager kelvoras;
    private final File configFile, dataFile;
    private YamlConfiguration config, data;
    private final Set<UUID> rolling = ConcurrentHashMap.newKeySet();
    private final Random random = new java.security.SecureRandom();

    GambleManager(JavaPlugin plugin, KelvorasManager kelvoras) {
        this.plugin = plugin;
        this.kelvoras = kelvoras;
        this.configFile = new File(plugin.getDataFolder(), "gamble.yml");
        this.dataFile = new File(plugin.getDataFolder(), "gamble-data.yml");
    }

    void register() {
        if (!plugin.getDataFolder().exists()) plugin.getDataFolder().mkdirs();
        if (!configFile.exists()) createDefaultConfig();
        config = YamlConfiguration.loadConfiguration(configFile);
        data = YamlConfiguration.loadConfiguration(dataFile);
        validate();
        Bukkit.getPluginManager().registerEvents(this, plugin);
        if (plugin.getCommand("gamble") != null) {
            plugin.getCommand("gamble").setExecutor(this);
            plugin.getCommand("gamble").setTabCompleter(this);
        }
    }

    private boolean eligible(Material m) {
        if (m == null || !m.isItem() || m.isAir() || m.name().endsWith("_SPAWN_EGG")) return false;
        return !Set.of("BEDROCK","BARRIER","COMMAND_BLOCK","CHAIN_COMMAND_BLOCK","REPEATING_COMMAND_BLOCK",
            "STRUCTURE_BLOCK","STRUCTURE_VOID","JIGSAW","DEBUG_STICK","KNOWLEDGE_BOOK","END_PORTAL_FRAME",
            "END_PORTAL","END_GATEWAY","SPAWNER","TRIAL_SPAWNER","VAULT","BUDDING_AMETHYST",
            "REINFORCED_DEEPSLATE","COMMAND_BLOCK_MINECART").contains(m.name());
    }

    private void createDefaultConfig() {
        config = new YamlConfiguration();
        config.set("settings.reset-timezone", "server");
        config.set("settings.animation-frames", 8);
        config.set("settings.animation-interval-ticks", 3);
        config.set("settings.broadcast-rare-rewards", true);
        config.set("attempts.sprout", 10);
        config.set("attempts.verdant", 12);
        config.set("attempts.emerald", 15);
        config.set("attempts.sovereign", 20);
        config.set("attempts.ascendant", 25);
        config.set("attempts.default", 10);
        config.set("disabled-materials", List.of());
        config.set("rewards", new ArrayList<String>());
        for (Material m : Material.values()) if (eligible(m)) config.getStringList("rewards").add(m.name() + ":1:1:Common");
        // Use a mutable section value to ensure the generated catalogue is actually saved.
        List<String> rewards = new ArrayList<>();
        for (Material m : Material.values()) if (eligible(m)) rewards.add(m.name() + ":1:1:Common");
        rewards.add("MONEY:100:1:Uncommon");
        rewards.add("KELVORAS:50:1:Uncommon");
        config.set("rewards", rewards);
        saveConfig();
    }

    private void validate() {
        List<String> valid = new ArrayList<>();
        for (String entry : config.getStringList("rewards")) {
            String[] p = entry.split(":", 4);
            boolean ok = false;
            if (p.length >= 4) {
                try {
                    double weight = Double.parseDouble(p[2]);
                    String kind = p[0].toUpperCase(Locale.ROOT);
                    if (Double.isFinite(weight) && weight > 0) {
                        if (kind.equals("MONEY") || kind.equals("KELVORAS")) {
                            double amount = Double.parseDouble(p[1]);
                            ok = Double.isFinite(amount) && amount > 0;
                        } else {
                            Material m = Material.matchMaterial(p[0]);
                            int amount = Integer.parseInt(p[1]);
                            ok = eligible(m) && amount > 0 && amount <= m.getMaxStackSize();
                        }
                    }
                } catch (RuntimeException ignored) {}
            }
            if (ok) valid.add(entry);
            else plugin.getLogger().warning("Ignoring invalid gamble reward entry in gamble.yml: " + entry);
        }
        config.set("validated-rewards", valid);
        ConfigurationSection chances = config.getConfigurationSection("exact-chances");
        if (chances != null) {
            double total = 0;
            for (String material : chances.getKeys(false)) {
                double chance = chances.getDouble(material, -1);
                if (!Double.isFinite(chance) || chance < 0 || chance >= 100 || !eligible(Material.matchMaterial(material))) {
                    plugin.getLogger().warning("Invalid gamble exact chance: " + material + "=" + chance);
                } else total += chance;
            }
            if (total >= 100) plugin.getLogger().warning("gamble.yml exact-chances total must be below 100%; exact chances will be ignored.");
        }
    }

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) { sender.sendMessage("This command is in-game only."); return true; }
        if (args.length > 0 && args[0].equalsIgnoreCase("reload")) {
            if (!player.isOp()) { player.sendMessage(Component.text("Only server operators can reload gamble.yml.", NamedTextColor.RED)); return true; }
            YamlConfiguration previous = config;
            YamlConfiguration next = YamlConfiguration.loadConfiguration(configFile);
            config = next;
            validate();
            if (config.getStringList("validated-rewards").isEmpty()) {
                config = previous;
                player.sendMessage(Component.text("Invalid gamble.yml: no valid rewards. Previous configuration retained.", NamedTextColor.RED));
            } else player.sendMessage(Component.text("Gamble reward configuration validated and reloaded.", NamedTextColor.GREEN));
            return true;
        }
        LocalDate today = LocalDate.now(ZoneId.systemDefault());
        String path = "players." + player.getUniqueId();
        String date = data.getString(path + ".date", "");
        if (!today.toString().equals(date)) {
            data.set(path + ".date", today.toString());
            data.set(path + ".used", 0);
            if (!saveData()) { player.sendMessage(Component.text("Could not save daily gamble reset. Try again later.", NamedTextColor.RED)); return true; }
        }
        int limit = allowance(player);
        int used = data.getInt(path + ".used", 0);
        int remaining = Math.max(0, limit - used);
        if (args.length > 0 && args[0].equalsIgnoreCase("remaining")) {
            player.sendMessage(Component.text("Daily gambles remaining: " + remaining + "/" + limit, NamedTextColor.GOLD));
            return true;
        }
        if (rolling.contains(player.getUniqueId())) { player.sendMessage(Component.text("Your gamble is already rolling.", NamedTextColor.YELLOW)); return true; }
        if (remaining <= 0) { player.sendMessage(Component.text("You have used all " + limit + " daily gambles. Come back after the daily reset.", NamedTextColor.RED)); return true; }
        String reward = chooseReward();
        if (reward == null) { player.sendMessage(Component.text("No valid rewards are configured; no attempt was used.", NamedTextColor.RED)); return true; }
        String[] bits = reward.split(":", 4);
        Material material = Material.matchMaterial(bits[0]);
        if (material != null && eligible(material)) {
            int amount = Integer.parseInt(bits[1]);
            if (!canFit(player, new ItemStack(material, amount))) {
                player.sendMessage(Component.text("Make room in your inventory before gambling. No attempt was used.", NamedTextColor.RED)); return true;
            }
        }
        data.set(path + ".used", used + 1);
        if (!saveData()) { data.set(path + ".used", used); player.sendMessage(Component.text("Could not save your attempt; no gamble was started.", NamedTextColor.RED)); return true; }
        rolling.add(player.getUniqueId());
        player.sendMessage(Component.text("Daily gambles remaining: " + (remaining - 1) + "/" + limit, NamedTextColor.GOLD));
        player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.7f, 0.8f);
        final int frames = Math.max(1, Math.min(30, config.getInt("settings.animation-frames", 8)));
        final long interval = Math.max(1, Math.min(20, config.getLong("settings.animation-interval-ticks", 3)));
        final int[] step = {0};
        Bukkit.getScheduler().runTaskTimer(plugin, task -> {
            Player online = Bukkit.getPlayer(player.getUniqueId());
            if (online == null || !online.isOnline()) {
                task.cancel();
                rolling.remove(player.getUniqueId());
                data.set(path + ".used", Math.max(0, data.getInt(path + ".used", 1) - 1));
                saveData();
                return;
            }
            if (step[0]++ < frames) {
                List<String> pool = config.getStringList("validated-rewards");
                if (!pool.isEmpty()) online.sendActionBar(Component.text("✦ ROLLING ✦ " + pool.get(random.nextInt(pool.size())).split(":")[0].replace('_',' ') + " ✦", NamedTextColor.GOLD));
                online.playSound(online.getLocation(), Sound.UI_BUTTON_CLICK, 0.5f, 0.7f + step[0] * 0.06f);
                return;
            }
            task.cancel();
            boolean delivered = deliver(online, reward);
            if (!delivered) {
                data.set(path + ".used", Math.max(0, data.getInt(path + ".used", 1) - 1));
                saveData();
                online.sendMessage(Component.text("Reward delivery failed. Your attempt was refunded.", NamedTextColor.RED));
            } else {
                online.sendTitle("§4✦ KELVORA ✦", "§fYou won: §e" + rewardLabel(reward), 5, 35, 10);
                online.sendActionBar(Component.text("Reward: " + rewardLabel(reward), NamedTextColor.GREEN));
                online.playSound(online.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.8f, 1.1f);
                String rarity = bits.length > 3 ? bits[3] : "Common";
                if (config.getBoolean("settings.broadcast-rare-rewards", true) && (rarity.equalsIgnoreCase("Legendary") || rarity.equalsIgnoreCase("Mythic")))
                    Bukkit.broadcast(Component.text(online.getName() + " won " + rewardLabel(reward) + " from /gamble!", NamedTextColor.GOLD));
            }
            rolling.remove(player.getUniqueId());
        }, 1L, interval);
        return true;
    }

    private int allowance(Player p) {
        String best = "sprout";
        List<String> ranks = List.of("sprout", "verdant", "emerald", "sovereign", "ascendant");
        try {
            Class<?> provider = Class.forName("net.luckperms.api.LuckPermsProvider");
            Object lp = provider.getMethod("get").invoke(null);
            Object users = lp.getClass().getMethod("getUserManager").invoke(lp);
            Object user = users.getClass().getMethod("getUser", UUID.class).invoke(users, p.getUniqueId());
            if (user != null) {
                String primary = String.valueOf(user.getClass().getMethod("getPrimaryGroup").invoke(user)).toLowerCase(Locale.ROOT);
                if (ranks.contains(primary)) best = primary;
                Object nodes = user.getClass().getMethod("getNodes").invoke(user);
                if (nodes instanceof Iterable<?> it) for (Object node : it) {
                    if (!node.getClass().getName().contains("InheritanceNode")) continue;
                    String group = String.valueOf(node.getClass().getMethod("getGroupName").invoke(node)).toLowerCase(Locale.ROOT);
                    if (ranks.contains(group) && ranks.indexOf(group) > ranks.indexOf(best)) best = group;
                }
            }
        } catch (ReflectiveOperationException | LinkageError ignored) {}
        return Math.max(0, config.getInt("attempts." + best, config.getInt("attempts.default", 10)));
    }

    private String chooseReward() {
        List<String> pool = config.getStringList("validated-rewards");
        if (pool.isEmpty()) return null;
        ConfigurationSection exact = config.getConfigurationSection("exact-chances");
        if (exact != null) {
            double total = 0;
            for (String k : exact.getKeys(false)) total += exact.getDouble(k, 0);
            if (total < 100) {
                double roll = random.nextDouble() * 100;
                double cursor = 0;
                for (String k : exact.getKeys(false)) {
                    cursor += exact.getDouble(k, 0);
                    if (roll < cursor) {
                        for (String entry : pool) if (entry.split(":", 4)[0].equalsIgnoreCase(k)) return entry;
                    }
                }
            }
        }
        double total = 0;
        for (String e : pool) total += Double.parseDouble(e.split(":", 4)[2]);
        double pick = random.nextDouble() * total;
        for (String e : pool) { pick -= Double.parseDouble(e.split(":", 4)[2]); if (pick <= 0) return e; }
        return pool.get(pool.size() - 1);
    }

    private boolean deliver(Player p, String reward) {
        String[] b = reward.split(":", 4);
        try {
            if (b[0].equalsIgnoreCase("MONEY")) {
                Object economy = Bukkit.getServicesManager().getRegistration((Class)Class.forName("net.milkbowl.vault.economy.Economy")).getProvider();
                Object response = economy.getClass().getMethod("depositPlayer", org.bukkit.OfflinePlayer.class, double.class).invoke(economy, p, Double.parseDouble(b[1]));
                return (Boolean)response.getClass().getMethod("transactionSuccess").invoke(response);
            }
            if (b[0].equalsIgnoreCase("KELVORAS")) return kelvoras.deposit(p, Double.parseDouble(b[1]));
            Material m = Material.matchMaterial(b[0]);
            if (!eligible(m)) return false;
            ItemStack item = new ItemStack(m, Integer.parseInt(b[1]));
            if (!canFit(p, item)) return false;
            return p.getInventory().addItem(item).isEmpty();
        } catch (Exception ex) {
            plugin.getLogger().warning("Gamble reward delivery failed: " + ex.getMessage());
            return false;
        }
    }

    private boolean canFit(Player p, ItemStack item) {
        int left = item.getAmount();
        for (ItemStack s : p.getInventory().getStorageContents()) {
            if (s == null || s.getType().isAir()) left -= item.getMaxStackSize();
            else if (s.isSimilar(item)) left -= Math.max(0, s.getMaxStackSize() - s.getAmount());
            if (left <= 0) return true;
        }
        return false;
    }
    private String rewardLabel(String reward) {
        String[] b = reward.split(":", 4);
        if (b[0].equalsIgnoreCase("MONEY")) return "$" + b[1] + " Money";
        if (b[0].equalsIgnoreCase("KELVORAS")) return "✦" + b[1] + " Kelvoras";
        return b[1] + "x " + b[0].toLowerCase(Locale.ROOT).replace('_', ' ');
    }
    private boolean saveData() { try { data.save(dataFile); return true; } catch (IOException ex) { plugin.getLogger().severe("Could not save gamble data: " + ex.getMessage()); return false; } }
    private void saveConfig() { try { config.save(configFile); } catch (IOException ex) { plugin.getLogger().severe("Could not create gamble.yml: " + ex.getMessage()); } }
    @EventHandler public void onJoin(PlayerJoinEvent event) { /* usage and reset are restored from gamble-data.yml */ }
    @Override public List<String> onTabComplete(CommandSender s, Command c, String a, String[] args) {
        if (args.length == 1 && s.isOp()) return List.of("remaining", "reload");
        if (args.length == 1) return List.of("remaining");
        return List.of();
    }
}
