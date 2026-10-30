package net.kelvora.smp;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.command.*;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.world.WorldLoadEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.configuration.ConfigurationSection;

final class CrateManager implements Listener, CommandExecutor, TabCompleter {
    private static final List<String> TYPES = List.of("common", "gold", "crimson", "kelvora", "spawner");
    private final JavaPlugin plugin;
    private final NamespacedKey holoKey;
    private final NamespacedKey spawnerRewardKey;
    private final File dataFile;
    private final File crateConfigDirectory;
    private YamlConfiguration data;
    private final Map<String, Location> crates = new LinkedHashMap<>();
    private final Map<UUID, String> openCrate = new HashMap<>();
    private final Map<UUID, String> editingCrate = new HashMap<>();
    private final Map<UUID, List<String>> editingRewards = new HashMap<>();
    private final Set<Inventory> protectedInventories = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Set<UUID> opening = ConcurrentHashMap.newKeySet();
    private final Map<UUID, String> pendingRemoval = new HashMap<>();
    private final Map<UUID, Long> pendingRemovalAt = new HashMap<>();
    private final java.security.SecureRandom random = new java.security.SecureRandom();
    private final String GUI = "KELVORA | CRATES";
    private final String ADMIN = "KELVORA | CRATE ADMIN";

    CrateManager(JavaPlugin plugin) {
        this.plugin = plugin;
        this.holoKey = new NamespacedKey(plugin, "crate-hologram");
        this.spawnerRewardKey = new NamespacedKey(plugin, "spawner-reward-entity");
        this.dataFile = new File(plugin.getDataFolder(), "crates-data.yml");
        this.crateConfigDirectory = new File(plugin.getDataFolder(), "crates");
    }

    void register() {
        data = YamlConfiguration.loadConfiguration(dataFile);
        migrateLegacyGamblingKeys();
        resetCratesOnceFor238();
        installFinalCratePackOnce239();
        installFinalCratePackOnce240();
        loadLocations();
        rebuildHolograms();
        Bukkit.getPluginManager().registerEvents(this, plugin);
        if (plugin.getCommand("crate") != null) {
            plugin.getCommand("crate").setExecutor(this);
            plugin.getCommand("crate").setTabCompleter(this);
        }
        if (plugin.getCommand("keyall") != null) plugin.getCommand("keyall").setExecutor(this);
        validateConfiguredRewards();
        generateCoverageReport();
    }

    /**
     * One-time clean rebuild for 2.38.0: remove registered physical crates and
     * clear every reward pool, while preserving player key balances and all
     * unrelated plugin data. The marker prevents wiping rewards on later restarts.
     */
    private void resetCratesOnceFor238() {
        if ("2.38.0".equals(data.getString("meta.empty-crate-reset-version", ""))) return;
        int rebuiltBlocks = 0;
        for (String key : new ArrayList<>(data.getKeys(false))) {
            if ("players".equalsIgnoreCase(key) || "meta".equalsIgnoreCase(key)) continue;
            String worldName = data.getString(key + ".location.world");
            World world = worldName == null ? null : Bukkit.getWorld(worldName);
            String type = validType(data.getString(key + ".type", "common"));
            if (type == null) type = "common";
            data.set(key + ".type", type);
            if (world != null && data.contains(key + ".location.x")
                    && data.contains(key + ".location.y") && data.contains(key + ".location.z")) {
                Location location = new Location(world, data.getInt(key + ".location.x"),
                        data.getInt(key + ".location.y"), data.getInt(key + ".location.z"));
                Material appearance = Material.matchMaterial(defaultBlock(type));
                if (appearance != null) {
                    location.getBlock().setType(appearance, false);
                    rebuiltBlocks++;
                }
            }
        }
        data.set("meta.empty-crate-reset-version", "2.38.0");
        if (!save()) plugin.getLogger().severe("Could not persist crate reset marker; the reset may run again after restart.");
        int cleared = 0;
        for (String type : TYPES) {
            YamlConfiguration cfg = crateConfig(type);
            cfg.set("rewards", Collections.emptyList());
            cfg.set("exact-chances", null);
            File target = new File(crateConfigDirectory, type + ".yml");
            try {
                if (!crateConfigDirectory.exists()) crateConfigDirectory.mkdirs();
                cfg.save(target);
                cleared++;
            } catch (IOException ex) {
                plugin.getLogger().severe("Could not reset " + type + " crate rewards: " + ex.getMessage());
            }
            plugin.getConfig().set("crates.types." + type + ".rewards", Collections.emptyList());
            plugin.getConfig().set("crates.types." + type + ".exact-chances", null);
        }
        plugin.getLogger().warning("Crate system reset 2.38.0: recreated " + rebuiltBlocks
                + " registered physical crate block(s) in their existing locations; emptied reward pools for "
                + cleared + "/5 crate types. Player digital key balances were preserved.");
    }

    /**
     * One-time 2.39.0 crate migration: install the requested four weighted reward pools
     * and remove obsolete Spawner crates without touching other plugin systems.
     */
    private void installFinalCratePackOnce239() {
        if ("2.39.1".equals(data.getString("meta.final-crate-pack-version", ""))) return;
        for (String key : new ArrayList<>(data.getKeys(false))) {
            if ("players".equalsIgnoreCase(key) || "meta".equalsIgnoreCase(key)) continue;
            String type = data.getString(key + ".type", "common");
            if ("spawner".equalsIgnoreCase(type) || "gambling".equalsIgnoreCase(type)) {
                String worldName = data.getString(key + ".location.world");
                World world = worldName == null ? null : Bukkit.getWorld(worldName);
                if (world != null && data.contains(key + ".location.x") && data.contains(key + ".location.y") && data.contains(key + ".location.z")) {
                    Location loc = new Location(world, data.getInt(key + ".location.x"), data.getInt(key + ".location.y"), data.getInt(key + ".location.z"));
                    if (loc.getBlock().getType().name().endsWith("_SHULKER_BOX")) loc.getBlock().setType(Material.AIR, false);
                }
                data.set(key, null);
            } else {
                String normalized = validType(type);
                if (normalized != null) data.set(key + ".type", normalized);
            }
        }
        ConfigurationSection players = data.getConfigurationSection("players");
        if (players != null) for (String uuid : players.getKeys(false)) data.set("players." + uuid + ".spawner", null);
        for (String type : TYPES) {
            YamlConfiguration cfg = crateConfig(type);
            String base = "crates.types." + type;
            cfg.set("name", plugin.getConfig().getString(base + ".name", titleCase(type) + " Crate"));
            cfg.set("description", plugin.getConfig().getString(base + ".description", "Special rewards"));
            cfg.set("block", plugin.getConfig().getString(base + ".block", defaultBlock(type)));
            cfg.set("rewards", defaultCrateRewards(type));
            cfg.set("exact-chances", null);
            try { cfg.save(new File(crateConfigDirectory, type + ".yml")); }
            catch (IOException ex) { plugin.getLogger().severe("Could not install " + type + " crate rewards: " + ex.getMessage()); }
        }
        data.set("meta.final-crate-pack-version", "2.39.1");
        if (!save()) plugin.getLogger().severe("Could not persist 2.39.0 crate migration marker.");
        plugin.getLogger().info("Installed the four final crate reward pools (Common, Gold, Crimson, Kelvora) from bundled defaults and removed obsolete Spawner crates.");
    }

    /**
     * 2.40.0 crate migration: add Donut-style mob spawner rewards, preserve all player
     * balances and existing physical crate locations, and remove Sellaxe from Kelvora
     * crate rewards without replacing the other reward pools.
     */
    private void installFinalCratePackOnce240() {
        if ("2.40.0".equals(data.getString("meta.crate-features-version", ""))) return;
        boolean migrationSaved = true;

        YamlConfiguration kelvoraCfg = crateConfig("kelvora");
        List<String> kelvoraRewards = new ArrayList<>(kelvoraCfg.getStringList("rewards"));
        int before = kelvoraRewards.size();
        kelvoraRewards.removeIf(entry -> entry.toLowerCase(Locale.ROOT)
                .startsWith("command:kelvoratools give {player} sellaxe:"));
        if (kelvoraRewards.size() != before) {
            kelvoraCfg.set("rewards", kelvoraRewards);
            try {
                kelvoraCfg.save(new File(crateConfigDirectory, "kelvora.yml"));
                plugin.getLogger().info("Removed Sellaxe from the Kelvora crate reward pool.");
            } catch (IOException ex) {
                migrationSaved = false;
                plugin.getLogger().severe("Could not update Kelvora crate rewards: " + ex.getMessage());
            }
        }

        // Only the new Spawner pool is replaced. Other reward lists and key balances remain untouched.
        YamlConfiguration spawnerCfg = crateConfig("spawner");
        spawnerCfg.set("name", "&5&lSpawner Crate");
        spawnerCfg.set("description", "Skeleton, Spider, and Iron Golem spawners");
        spawnerCfg.set("block", "PURPLE_SHULKER_BOX");
        spawnerCfg.set("rewards", defaultCrateRewards("spawner"));
        spawnerCfg.set("exact-chances", null);
        try {
            if (!crateConfigDirectory.exists()) crateConfigDirectory.mkdirs();
            spawnerCfg.save(new File(crateConfigDirectory, "spawner.yml"));
        } catch (IOException ex) {
            migrationSaved = false;
            plugin.getLogger().severe("Could not install Spawner crate rewards: " + ex.getMessage());
        }

        for (String key : new ArrayList<>(data.getKeys(false))) {
            if ("players".equalsIgnoreCase(key) || "meta".equalsIgnoreCase(key)) continue;
            if (!"spawner".equalsIgnoreCase(data.getString(key + ".type", ""))) continue;
            String worldName = data.getString(key + ".location.world");
            World world = worldName == null ? null : Bukkit.getWorld(worldName);
            if (world == null || !data.contains(key + ".location.x")
                    || !data.contains(key + ".location.y") || !data.contains(key + ".location.z")) continue;
            Location location = new Location(world, data.getInt(key + ".location.x"),
                    data.getInt(key + ".location.y"), data.getInt(key + ".location.z"));
            if (location.getBlock().getType().name().endsWith("_SHULKER_BOX")) {
                location.getBlock().setType(Material.PURPLE_SHULKER_BOX, false);
            }
        }

        if (!migrationSaved) {
            plugin.getLogger().severe("2.40.0 crate migration will retry on the next restart because a crate config could not be saved.");
            return;
        }
        data.set("meta.crate-features-version", "2.40.0");
        if (!save()) plugin.getLogger().severe("Could not save 2.40.0 crate migration marker.");
        plugin.getLogger().info("Installed the Spawner crate and updated Kelvora rewards; player key balances and other crate pools were preserved.");
    }

    private List<String> defaultCrateRewards(String type) {
        return switch (type.toLowerCase(Locale.ROOT)) {
            case "common" -> List.of(
                "DIAMOND_SWORD:1:7:Common:SHARPNESS=2,UNBREAKING=2",
                "DIAMOND_PICKAXE:1:7:Common:EFFICIENCY=2,UNBREAKING=2",
                "DIAMOND_AXE:1:7:Uncommon:SHARPNESS=2,EFFICIENCY=2,UNBREAKING=2",
                "DIAMOND_SHOVEL:1:7:Common:EFFICIENCY=2,UNBREAKING=2",
                "DIAMOND_HELMET:1:7:Uncommon:PROTECTION=2,UNBREAKING=2",
                "DIAMOND_CHESTPLATE:1:7:Uncommon:PROTECTION=2,UNBREAKING=2",
                "DIAMOND_LEGGINGS:1:7:Uncommon:PROTECTION=2,UNBREAKING=2",
                "DIAMOND_BOOTS:1:7:Uncommon:PROTECTION=2,FEATHER_FALLING=2,UNBREAKING=2",
                "KELP:32:10:Common", "PINK_PETALS:32:10:Common",
                "MONEY:250:14:Common", "KELVORAS:100:10:Common");
            case "gold" -> List.of(
                "DIAMOND_SWORD:1:9:Rare:SHARPNESS=4,UNBREAKING=3,LOOTING=2",
                "DIAMOND_PICKAXE:1:9:Rare:EFFICIENCY=4,UNBREAKING=3,FORTUNE=2",
                "DIAMOND_AXE:1:9:Rare:SHARPNESS=4,EFFICIENCY=4,UNBREAKING=3",
                "DIAMOND_SHOVEL:1:9:Rare:EFFICIENCY=4,UNBREAKING=3,SILK_TOUCH=1",
                "DIAMOND_HELMET:1:9:Rare:PROTECTION=3,UNBREAKING=3,RESPIRATION=3,AQUA_AFFINITY=1",
                "DIAMOND_CHESTPLATE:1:9:Epic:PROTECTION=4,UNBREAKING=3",
                "DIAMOND_LEGGINGS:1:9:Rare:PROTECTION=3,UNBREAKING=3,SWIFT_SNEAK=2",
                "DIAMOND_BOOTS:1:9:Epic:PROTECTION=3,FEATHER_FALLING=4,UNBREAKING=3,DEPTH_STRIDER=3",
                "MONEY:2500:16:Rare", "KELVORAS:1000:12:Rare");
            case "crimson" -> List.of(
                "NETHERITE_SWORD:1:7:Epic:SHARPNESS=4,UNBREAKING=3,LOOTING=3",
                "NETHERITE_SWORD:1:7:Legendary:SHARPNESS=5,UNBREAKING=3",
                "NETHERITE_PICKAXE:1:7:Epic:EFFICIENCY=5,FORTUNE=3,UNBREAKING=3",
                "NETHERITE_PICKAXE:1:7:Legendary:EFFICIENCY=5,SILK_TOUCH=1,UNBREAKING=3",
                "NETHERITE_AXE:1:7:Legendary:SHARPNESS=5,EFFICIENCY=5,UNBREAKING=3",
                "NETHERITE_HELMET:1:7:Epic:PROTECTION=4,UNBREAKING=3,RESPIRATION=3,AQUA_AFFINITY=1",
                "NETHERITE_CHESTPLATE:1:7:Epic:PROTECTION=4,UNBREAKING=3",
                "NETHERITE_LEGGINGS:1:7:Epic:PROTECTION=4,UNBREAKING=3,SWIFT_SNEAK=3",
                "NETHERITE_BOOTS:1:7:Epic:PROTECTION=4,FEATHER_FALLING=4,UNBREAKING=3,DEPTH_STRIDER=3",
                "NETHERITE_INGOT:1:10:Rare", "NETHERITE_INGOT:2:4:Epic",
                "MONEY:15000:13:Epic", "KELVORAS:5000:10:Epic");
            case "kelvora" -> List.of(
                "COMMAND:kelvoratools give {player} drill:17:Mythic",
                "COMMAND:kelvoratools give {player} gainer:17:Mythic",
                "COMMAND:kelvoratools give {player} treetrooper:12:Legendary",
                "MONEY:25000:21:Epic", "KELVORAS:10000:21:Epic");
            case "spawner" -> List.of(
                "SPAWNER_SKELETON:1:45:Common",
                "SPAWNER_SPIDER:1:35:Rare",
                "SPAWNER_IRON_GOLEM:1:20:Legendary");
            default -> List.of();
        };
    }

    private void migrateLegacyGamblingKeys() {
        ConfigurationSection players = data.getConfigurationSection("players");
        if (players == null) return;
        boolean migrate = plugin.getConfig().getBoolean("crates.migrate-gambling-keys-to-crimson", false);
        int legacyPlayers = 0;
        boolean changed = false;
        for (String uuid : players.getKeys(false)) {
            String oldPath = "players." + uuid + ".gambling";
            int old = data.getInt(oldPath, 0);
            if (old <= 0) continue;
            legacyPlayers++;
            if (migrate) {
                String newPath = "players." + uuid + ".crimson";
                long sum = (long) data.getInt(newPath, 0) + old;
                data.set(newPath, (int) Math.min(Integer.MAX_VALUE, sum));
                data.set(oldPath, null);
                changed = true;
            }
        }
        if (changed && !save()) plugin.getLogger().severe("Legacy Gambling key migration could not be saved; check the crate data file before restarting.");
        if (legacyPlayers > 0 && !migrate) plugin.getLogger().warning("Found legacy Gambling keys for " + legacyPlayers + " player(s). They were preserved. Set crates.migrate-gambling-keys-to-crimson: true in config.yml and restart to migrate them to Crimson keys.");
        if (migrate && legacyPlayers > 0) plugin.getLogger().info("Migrated legacy Gambling key balances to Crimson keys for " + legacyPlayers + " player(s).");
    }

    private void loadLocations() {
        crates.clear();
        ConfigurationSectionLike:
        for (String key : data.getKeys(false)) {
            String path = key + ".location";
            if ("players".equalsIgnoreCase(key)) continue;
            if ("gambling".equalsIgnoreCase(data.getString(key + ".type", ""))) {
                data.set(key + ".type", "crimson");
            }
            String worldName = data.getString(path + ".world");
            World world = worldName == null ? null : Bukkit.getWorld(worldName);
            if (world == null) continue;
            Location crateLocation = new Location(world, data.getInt(path + ".x"), data.getInt(path + ".y"), data.getInt(path + ".z"));
            crates.put(key, crateLocation);
            String type = validType(data.getString(key + ".type", "common"));
            if (type == null) type = "common";
            Material appearance = Material.matchMaterial(defaultBlock(type));
            if (appearance != null && appearance.name().endsWith("_SHULKER_BOX")
                    && crateLocation.getBlock().getType().name().endsWith("_SHULKER_BOX")
                    && crateLocation.getBlock().getType() != appearance) {
                crateLocation.getBlock().setType(appearance, false);
            }
        }
        save();
    }

    private String defaultBlock(String type) {
        return switch (type.toLowerCase(Locale.ROOT)) {
            case "common" -> "LIGHT_GRAY_SHULKER_BOX";
            case "kelvora" -> "GREEN_SHULKER_BOX";
            case "gold" -> "YELLOW_SHULKER_BOX";
            case "crimson" -> "RED_SHULKER_BOX";
            case "spawner" -> "PURPLE_SHULKER_BOX";
            default -> "SHULKER_BOX";
        };
    }

    private void rebuildHolograms() {
        for (World world : Bukkit.getWorlds()) for (Entity entity : world.getEntities()) {
            if (entity instanceof ArmorStand stand && stand.getPersistentDataContainer().has(holoKey, PersistentDataType.STRING)) stand.remove();
        }
        // Crate holograms are intentionally disabled; physical crate blocks remain usable.
    }

    private void createHologram(String type, Location block) {
        // Holograms were removed because their ArmorStands could desynchronize or break.
    }

    private String locKey(Location l) { return l.getWorld().getName() + "," + l.getBlockX() + "," + l.getBlockY() + "," + l.getBlockZ(); }
    private String titleCase(String s) { return s.substring(0,1).toUpperCase(Locale.ROOT) + s.substring(1).toLowerCase(Locale.ROOT); }
    private Component color(String s) { return net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.legacyAmpersand().deserialize(s); }
    private boolean admin(CommandSender sender) {
        if (sender.isOp() || sender.hasPermission("kelvora.crate.admin") || sender.hasPermission("kelvora.crate.edit") || sender.hasPermission("kelvora.roles.manage")) return true;
        if (sender instanceof Player p && plugin instanceof KelvoraSMP k) {
            return k.hasRoleGroup(p, "owner") || k.hasRoleGroup(p, "admin") || k.hasRoleGroup(p, "manager") || k.hasRoleGroup(p, "moderator") || k.hasRoleGroup(p, "helper") || k.hasRoleGroup(p, "staff");
        }
        return false;
    }
    private String validType(String s) { if (s == null) return null; String v=s.toLowerCase(Locale.ROOT); return TYPES.contains(v)?v:null; }

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (label.equalsIgnoreCase("keyall")) {
            if (!admin(sender)) { sender.sendMessage("You do not have permission."); return true; }
            if (args.length != 2) { sender.sendMessage("Usage: /keyall <type> <amount>"); return true; }
            String type=validType(args[0]); int amount=parseAmount(args[1]);
            if (type==null || amount<1) { sender.sendMessage("Invalid crate type or amount."); return true; }
            List<Player> recipients = new ArrayList<>(Bukkit.getOnlinePlayers());
            int granted = 0;
            for (Player p : recipients) {
                if (!addKeys(p, type, amount)) {
                    p.sendMessage(Component.text("Could not save your key balance. Please contact staff; no keys were granted.", NamedTextColor.RED));
                    continue;
                }
                granted++;
                p.sendMessage(Component.text("You received " + amount + " digital " + titleCase(type) + " key(s) from the server.", NamedTextColor.GOLD));
                startKeyallAnimation(p, type, amount);
            }
            if (granted > 0) Bukkit.broadcast(Component.text("🎁 " + titleCase(type) + " keys distributed to " + granted + " online player(s).", NamedTextColor.LIGHT_PURPLE));
            sender.sendMessage("Granted "+amount+" digital "+titleCase(type)+" key(s) to "+granted+" player(s).");
            return true;
        }
        if (args.length==0) {
            if (!admin(sender)) { sender.sendMessage("You do not have permission."); return true; }
            if (sender instanceof Player p) openAdmin(p); else sender.sendMessage("Use /crate <spawn|remove|list|keys|givekey>.");
            return true;
        }
        String sub=args[0].toLowerCase(Locale.ROOT);
        if (sub.equals("keys")) {
            if (!(sender instanceof Player p)) { sender.sendMessage("Use this command in-game."); return true; }
            sender.sendMessage(Component.text("Your digital crate keys", NamedTextColor.GOLD));
            for(String type:TYPES) sender.sendMessage(Component.text(titleCase(type)+": "+keys(p,type), NamedTextColor.AQUA));
            int legacy = data.getInt("players." + p.getUniqueId() + ".gambling", 0);
            if (legacy > 0) sender.sendMessage(Component.text("Legacy Gambling keys preserved: " + legacy + " (operator can enable migration in config.yml)", NamedTextColor.YELLOW));
            return true;
        }
        if (!admin(sender)) { sender.sendMessage("You do not have permission."); return true; }
        if (sub.equals("edit")) {
            if (!(sender instanceof Player p) || args.length != 2) { sender.sendMessage("Usage: /crate edit <type> (in-game, operator only)"); return true; }
            String type = validType(args[1]);
            if (type == null) { sender.sendMessage("Types: " + String.join(", ", TYPES)); return true; }
            openRewardEditor(p, type);
            return true;
        }
        if (sub.equals("add")) {
            if (args.length < 6 || args.length > 7) {
                sender.sendMessage("Usage: /crate add <type> <material|MONEY|KELVORAS|TOOL> <amount-or-tool> <weight> <rarity> [ENCHANT=LEVEL,...]");
                return true;
            }
            String type = validType(args[1]);
            if (type == null) { sender.sendMessage("Types: " + String.join(", ", TYPES)); return true; }
            String kind = args[2].toUpperCase(Locale.ROOT);
            String amountOrTool = args[3];
            double weight;
            try { weight = Double.parseDouble(args[4]); } catch (NumberFormatException ex) { weight = -1; }
            String rarity = args[5].replace(':', ' ');
            if (!Double.isFinite(weight) || weight <= 0 || rarity.isBlank()) {
                sender.sendMessage("Weight must be positive and rarity must not be blank."); return true;
            }
            String entry;
            if (kind.equals("TOOL")) {
                String tool = amountOrTool.toLowerCase(Locale.ROOT);
                if (!Set.of("drill", "gainer", "sellaxe", "treetrooper").contains(tool)) {
                    sender.sendMessage("Existing tools: drill, gainer, sellaxe, treetrooper"); return true;
                }
                entry = "COMMAND:kelvoratools give {player} " + tool + ":" + weight + ":" + rarity;
            } else if (kind.equals("MONEY") || kind.equals("KELVORAS")) {
                try {
                    double amount = Double.parseDouble(amountOrTool);
                    if (!Double.isFinite(amount) || amount <= 0) throw new NumberFormatException();
                } catch (NumberFormatException ex) { sender.sendMessage("Currency amount must be positive."); return true; }
                entry = kind + ":" + amountOrTool + ":" + weight + ":" + rarity;
            } else {
                int amount;
                try { amount = Integer.parseInt(amountOrTool); } catch (NumberFormatException ex) { amount = -1; }
                if (isSpawnerRewardKind(kind)) {
                    if (amount != 1 || args.length == 7) {
                        sender.sendMessage("Spawner rewards must have amount 1 and do not use enchantments."); return true;
                    }
                    entry = kind + ":1:" + weight + ":" + rarity;
                } else {
                    Material material = Material.matchMaterial(kind);
                    if (!isAllowedReward(material) || amount < 1 || amount > (material == null ? 0 : material.getMaxStackSize())) {
                        sender.sendMessage("Invalid reward material or stack amount."); return true;
                    }
                    entry = material.name() + ":" + amount + ":" + weight + ":" + rarity;
                    if (args.length == 7) {
                        String enchants = args[6].replace(" ", "");
                        if (parseEnchantments(enchants) == null) { sender.sendMessage("Invalid enchantments. Format: SHARPNESS=2,UNBREAKING=3"); return true; }
                        entry += ":" + enchants;
                    }
                }
            }
            List<String> rewards = configuredRewards(type);
            if (rewards.size() >= 45) { sender.sendMessage("This crate already has 45 reward entries."); return true; }
            rewards.add(entry);
            if (saveEditedRewards(type, rewards)) sender.sendMessage(Component.text("Added and saved " + entry + " to " + titleCase(type) + " Crate.", NamedTextColor.GREEN));
            else sender.sendMessage(Component.text("Reward was not added because validation or saving failed.", NamedTextColor.RED));
            return true;
        }
        if (sub.equals("modify")) {
            if (args.length < 5 || args.length > 6) {
                sender.sendMessage("Usage: /crate modify <type> <index> <weight> <rarity> [ENCHANT=LEVEL,...|-]");
                return true;
            }
            String type = validType(args[1]);
            int index;
            double weight;
            try { index = Integer.parseInt(args[2]) - 1; weight = Double.parseDouble(args[3]); }
            catch (NumberFormatException ex) { sender.sendMessage("Index and weight must be numbers."); return true; }
            if (type == null) { sender.sendMessage("Types: " + String.join(", ", TYPES)); return true; }
            List<String> rewards = configuredRewards(type);
            if (index < 0 || index >= rewards.size() || !Double.isFinite(weight) || weight <= 0) {
                sender.sendMessage("Invalid 1-based reward index or weight."); return true;
            }
            String[] parts = rewards.get(index).split(":", 5);
            if (parts.length < 4) { sender.sendMessage("That reward entry is invalid."); return true; }
            String rarity = args[4].replace(':', ' ');
            if (rarity.isBlank()) { sender.sendMessage("Rarity must not be blank."); return true; }
            String enchantPart = parts.length > 4 ? parts[4] : "";
            if (args.length == 6) {
                enchantPart = args[5].equals("-") ? "" : args[5].replace(" ", "");
                if (!enchantPart.isBlank() && parseEnchantments(enchantPart) == null) {
                    sender.sendMessage("Invalid enchantments. Format: SHARPNESS=2,UNBREAKING=3"); return true;
                }
            }
            String updated = parts[0] + ":" + parts[1] + ":" + weight + ":" + rarity + (enchantPart.isBlank() ? "" : ":" + enchantPart);
            rewards.set(index, updated);
            if (saveEditedRewards(type, rewards)) sender.sendMessage(Component.text("Updated reward #" + (index + 1) + " and saved.", NamedTextColor.GREEN));
            else sender.sendMessage(Component.text("Update failed validation or could not be saved.", NamedTextColor.RED));
            return true;
        }
        if (sub.equals("remove-reward")) {
            if (args.length != 3) { sender.sendMessage("Usage: /crate remove-reward <type> <index>"); return true; }
            String type = validType(args[1]);
            int index;
            try { index = Integer.parseInt(args[2]) - 1; } catch (NumberFormatException ex) { index = -1; }
            if (type == null) { sender.sendMessage("Types: " + String.join(", ", TYPES)); return true; }
            List<String> rewards = configuredRewards(type);
            if (index < 0 || index >= rewards.size()) { sender.sendMessage("Reward index is out of range. Use /crate edit " + type + " to view rewards."); return true; }
            String removed = rewards.remove(index);
            if (saveEditedRewards(type, rewards)) sender.sendMessage(Component.text("Removed and saved reward: " + removed, NamedTextColor.YELLOW));
            else sender.sendMessage(Component.text("Removal failed; the previous reward pool was retained.", NamedTextColor.RED));
            return true;
        }
        if (sub.equals("reload")) {
            plugin.reloadConfig();
            validateConfiguredRewards();
            sender.sendMessage(Component.text("Crate configs reloaded/validated. Edit plugins/KelvoraSMP/crates/*.yml; invalid reward lines are logged.", NamedTextColor.GREEN));
            return true;
        }
        if (sub.equals("spawn")) {
            if (!(sender instanceof Player p) || args.length!=2) { sender.sendMessage("Usage: /crate spawn <type> (in-game)"); return true; }
            String type=validType(args[1]); if(type==null){sender.sendMessage("Types: "+String.join(", ",TYPES));return true;}
            Block target=p.getTargetBlockExact(6);
            Location loc;
            if(target!=null) loc=(target.getType().isAir()?target:target.getRelative(0,1,0)).getLocation();
            else loc=p.getLocation().getBlock().getRelative(0,2,0).getLocation();
            if(!loc.getBlock().isPassable()){sender.sendMessage("Choose a clear block location (look at the ground or an empty space).");return true;}
            Material appearance=Material.matchMaterial(defaultBlock(type));
            if(appearance==null) appearance=Material.SHULKER_BOX;
            loc.getBlock().setType(appearance);
            String key=locKey(loc);
            crates.put(key,loc);
            data.set(key+".type",type); data.set(key+".location.world",loc.getWorld().getName());data.set(key+".location.x",loc.getBlockX());data.set(key+".location.y",loc.getBlockY());data.set(key+".location.z",loc.getBlockZ());
            save();
            createHologram(type,loc);
            p.sendMessage(Component.text("Placed "+titleCase(type)+" Crate. Keys are digital; use /crate keys to check them.",NamedTextColor.GREEN));
            return true;
        }
        if (sub.equals("remove")) {
            if (!(sender instanceof Player p)) {sender.sendMessage("Use this command in-game.");return true;}
            Block target=p.getTargetBlockExact(6); if(target==null){p.sendMessage("Look at a crate block within 6 blocks.");return true;}
            String key=locKey(target.getLocation()); if(!crates.containsKey(key)){p.sendMessage("That is not a registered Kelvora crate.");return true;}
            UUID id = p.getUniqueId();
            long now = System.currentTimeMillis();
            if (!key.equals(pendingRemoval.get(id)) || now - pendingRemovalAt.getOrDefault(id, 0L) > 10_000L) {
                pendingRemoval.put(id, key);
                pendingRemovalAt.put(id, now);
                p.sendMessage(Component.text("Run /crate remove again within 10 seconds to confirm removing this crate.", NamedTextColor.YELLOW));
                return true;
            }
            pendingRemoval.remove(id); pendingRemovalAt.remove(id);
            target.setType(Material.AIR); crates.remove(key);data.set(key,null);
            if (!save()) { p.sendMessage(Component.text("Could not save crate removal. Check server logs.", NamedTextColor.RED)); return true; }
            rebuildHolograms();
            p.sendMessage(Component.text("Crate and hologram removed.",NamedTextColor.GREEN));return true;
        }
        if(sub.equals("list")) {
            sender.sendMessage("Registered physical crates: "+crates.size());
            crates.forEach((key,loc)->sender.sendMessage("- "+data.getString(key+".type")+" at "+key));
            return true;
        }
        if(sub.equals("givekey")) {
            if(args.length!=4){sender.sendMessage("Usage: /crate givekey <player> <type> <amount>");return true;}
            Player target=Bukkit.getPlayerExact(args[1]);String type=validType(args[2]);int amount=parseAmount(args[3]);
            if(target==null||type==null||amount<1){sender.sendMessage("Target must be online; check type and amount.");return true;}
            if(!addKeys(target,type,amount)){sender.sendMessage("Could not save the key balance; the grant was rolled back.");return true;}sender.sendMessage("Granted "+amount+" digital "+titleCase(type)+" key(s) to "+target.getName());return true;
        }
        sender.sendMessage("Usage: /crate <spawn <type>|remove|list|keys|givekey <player> <type> <amount>>");return true;
    }
    private int parseAmount(String s){try{int n=Integer.parseInt(s);return n>0&&n<=100000?n:-1;}catch(Exception e){return -1;}}
    boolean grantDigitalKeys(Player player, String type, int amount) {
        String normalized = validType(type);
        if (player == null || normalized == null || amount < 1) return false;
        return addKeys(player, normalized, amount);
    }

    boolean grantDigitalKeys(Player player, Map<String, Integer> rewards) {
        if (player == null || rewards == null || rewards.isEmpty()) return false;
        Map<String, Integer> oldValues = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> entry : rewards.entrySet()) {
            String type = validType(entry.getKey());
            Integer amount = entry.getValue();
            if (type == null || amount == null || amount < 1) return false;
            String path = playerPath(player, type);
            int old = data.getInt(path, 0);
            if ((long) old + amount > Integer.MAX_VALUE) return false;
            oldValues.put(path, old);
        }
        for (Map.Entry<String, Integer> entry : rewards.entrySet()) {
            String type = validType(entry.getKey());
            data.set(playerPath(player, type), data.getInt(playerPath(player, type), 0) + entry.getValue());
        }
        if (save()) return true;
        oldValues.forEach(data::set);
        return false;
    }
    private String playerPath(Player p,String type){return "players."+p.getUniqueId()+"."+type;}
    private int keys(Player p,String type){return data.getInt(playerPath(p,type),0);}
    private boolean addKeys(Player p,String type,int amount){String path=playerPath(p,type);int old=data.getInt(path,0);data.set(path,Math.min(2_000_000_000L,(long)old+amount));if(!save()){data.set(path,old);return false;}return true;}

    private void openRewardEditor(Player player, String type) {
        UUID editorId = player.getUniqueId();
        List<String> rewards = editingRewards.get(editorId);
        if (rewards == null || !type.equalsIgnoreCase(editingCrate.get(editorId))) {
            rewards = new ArrayList<>(configuredRewards(type));
            editingRewards.put(editorId, rewards);
            editingCrate.put(editorId, type);
        }
        Inventory inv = Bukkit.createInventory(null, 54, Component.text("KELVORA | EDIT " + type.toUpperCase(Locale.ROOT), NamedTextColor.GREEN));
        protectedInventories.add(inv);
        for (int i = 0; i < 45; i++) {
            if (i >= rewards.size()) continue;
            String entry = rewards.get(i);
            String[] bits = entry.split(":", 5);
            if (bits.length < 4) continue;
            List<String> rewardLore = new ArrayList<>();
            rewardLore.add("&7Weight: " + bits[2]);
            rewardLore.add("&7Rarity: " + bits[3]);
            if (bits.length > 4 && !bits[4].isBlank()) rewardLore.add("&dEnchants: " + bits[4]);
            rewardLore.add("&cClick to remove this reward");
            try {
                inv.setItem(i, rewardDisplayItem(entry, "&b" + bits[0] + " x" + bits[1], rewardLore));
            } catch (RuntimeException ex) {
                plugin.getLogger().warning("Could not display crate reward " + entry + ": " + ex.getMessage());
            }
        }
        inv.setItem(45, button(Material.BARRIER, "&cCancel", List.of("&7Discard unsaved edits")));
        inv.setItem(49, button(Material.EMERALD, "&a&lSAVE REWARDS", List.of("&7Save reward list safely", "&7New inventory items use weight 1 / Common")));
        inv.setItem(53, button(Material.HOPPER, "&eAdd from inventory", List.of("&7Click an item in your inventory", "&7to add a copy as a reward", "&7Use /crate add for enchanted gear and custom tools")));
        player.openInventory(inv);
    }

    private boolean saveEditedRewards(String type, List<String> rewards) {
        for (String entry : rewards) {
            String[] bits = entry.split(":", 5);
            if (bits.length < 4) return false;
            try {
                double weight = Double.parseDouble(bits[2]);
                if (!Double.isFinite(weight) || weight <= 0) return false;
                String kind = bits[0].toUpperCase(Locale.ROOT);
                if (kind.equals("MONEY") || kind.equals("KELVORAS")) {
                    double value = Double.parseDouble(bits[1]);
                    if (!Double.isFinite(value) || value <= 0) return false;
                } else if (kind.equals("COMMAND")) {
                    if (bits[1].isBlank()) return false;
                } else {
                    int amount = Integer.parseInt(bits[1]);
                    if (isSpawnerRewardKind(kind)) {
                        if (amount != 1 || (bits.length > 4 && !bits[4].isBlank())) return false;
                    } else {
                        Material material = Material.matchMaterial(bits[0]);
                        if (!isAllowedReward(material) || amount <= 0 || amount > material.getMaxStackSize()) return false;
                        if (bits.length > 4 && !bits[4].isBlank() && parseEnchantments(bits[4]) == null) return false;
                    }
                }
            } catch (RuntimeException ex) { return false; }
        }
        File target = new File(crateConfigDirectory, type.toLowerCase(Locale.ROOT) + ".yml");
        File temp = new File(crateConfigDirectory, type.toLowerCase(Locale.ROOT) + ".yml.tmp");
        YamlConfiguration cfg = crateConfig(type);
        cfg.set("rewards", rewards);
        try {
            cfg.save(temp);
            try {
                java.nio.file.Files.move(temp.toPath(), target.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException ex) {
                java.nio.file.Files.move(temp.toPath(), target.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            validateConfiguredRewards();
            return true;
        } catch (Exception ex) {
            try { java.nio.file.Files.deleteIfExists(temp.toPath()); } catch (IOException ignored) {}
            plugin.getLogger().severe("Could not save " + type + " crate rewards; previous config was retained: " + ex.getMessage());
            return false;
        }
    }

    private Map<org.bukkit.enchantments.Enchantment, Integer> parseEnchantments(String raw) {
        Map<org.bukkit.enchantments.Enchantment, Integer> result = new LinkedHashMap<>();
        if (raw == null || raw.isBlank()) return result;
        for (String part : raw.split(",")) {
            String[] pair = part.trim().split("=", 2);
            if (pair.length != 2) return null;
            org.bukkit.enchantments.Enchantment enchantment = org.bukkit.Registry.ENCHANTMENT.get(
                    org.bukkit.NamespacedKey.minecraft(pair[0].trim().toLowerCase(Locale.ROOT)));
            if (enchantment == null) return null;
            try {
                int level = Integer.parseInt(pair[1].trim());
                if (level < 1 || level > enchantment.getMaxLevel()) return null;
                result.put(enchantment, level);
            } catch (NumberFormatException ex) { return null; }
        }
        return result;
    }

    private double chancePercent(String type, String selectedEntry) {
        List<String> entries = configuredRewards(type);
        double total = 0.0, selectedWeight = 0.0;
        for (String entry : entries) {
            String[] parts = entry.split(":", 5);
            if (parts.length < 4) continue;
            try {
                double weight = Double.parseDouble(parts[2]);
                if (!Double.isFinite(weight) || weight <= 0) continue;
                total += weight;
                if (entry.equals(selectedEntry)) selectedWeight = weight;
            } catch (NumberFormatException ignored) { }
        }
        return total <= 0 ? 0.0 : selectedWeight * 100.0 / total;
    }

    private String rewardLabel(String[] bits) {
        String kind = bits[0].toUpperCase(Locale.ROOT);
        if (kind.equals("MONEY")) return "$" + bits[1] + " Money";
        if (kind.equals("KELVORAS")) return bits[1] + " Kelvoras";
        if (kind.equals("COMMAND")) {
            String command = bits[1].toLowerCase(Locale.ROOT);
            if (command.contains(" drill")) return "Kelvora Drill";
            if (command.contains(" gainer")) return "Kelvora Gainer";
            if (command.contains(" sellaxe")) return "Kelvora Sellaxe";
            if (command.contains(" treetrooper")) return "Kelvora Tree Trooper";
            return "Special reward";
        }
        if (isSpawnerRewardKind(kind)) return spawnerMobName(kind) + " Spawner";
        Material material = Material.matchMaterial(bits[0]);
        return material == null ? bits[0] : titleCase(material.name().toLowerCase(Locale.ROOT).replace('_', ' ')) + " x" + bits[1];
    }

    private void openAdmin(Player p) {
        Inventory inv=Bukkit.createInventory(null,27,Component.text(ADMIN,NamedTextColor.DARK_GREEN));
        protectedInventories.add(inv);
        int slot=10;for(String type:TYPES){ItemStack item=new ItemStack(Material.CHEST);ItemMeta meta=item.getItemMeta();meta.displayName(color("&6"+titleCase(type)+" Crate"));meta.lore(List.of(color("&7Spawn a physical crate"),color("&fClick to spawn at your target/location")));item.setItemMeta(meta);inv.setItem(slot++,item);}
        ItemStack list=new ItemStack(Material.PAPER);ItemMeta lm=list.getItemMeta();lm.displayName(color("&aRegistered crates: "+crates.size()));list.setItemMeta(lm);inv.setItem(22,list);p.openInventory(inv);
    }

    private void openCrate(Player p, String type) {
        openCrate.put(p.getUniqueId(), type);
        Inventory inv = Bukkit.createInventory(null, 54, Component.text(GUI + " | " + titleCase(type), NamedTextColor.DARK_GREEN));
        protectedInventories.add(inv);
        Material crateMaterial = Material.matchMaterial(defaultBlock(type));
        inv.setItem(4, button(crateMaterial == null ? Material.SHULKER_BOX : crateMaterial,
                "&6&l" + titleCase(type) + " Crate",
                List.of("&7" + crateConfig(type).getString("description", "Special rewards"),
                        "&7Each displayed chance is the real drop chance.")));
        inv.setItem(0, button(Material.TRIPWIRE_HOOK, "&eYour Keys: &f" + keys(p, type),
                List.of("&7Digital keys only", "&7One key per successful opening")));
        List<String> rewards = configuredRewards(type);
        int slot = 9;
        for (String entry : rewards) {
            if (slot > 44) break;
            String[] bits = entry.split(":", 5);
            if (bits.length < 4) continue;
            if (!isValidRewardEntry(entry)) continue;
            double chance = chancePercent(type, entry);
            List<String> lore = new ArrayList<>();
            lore.add("&7Chance: &f" + String.format(Locale.US, "%.2f", chance) + "%");
            lore.add("&8Rarity: " + bits[3]);
            if (bits.length > 4 && !bits[4].isBlank()) lore.add("&dEnchants: " + bits[4]);
            inv.setItem(slot++, rewardDisplayItem(entry, "&f" + rewardLabel(bits), lore));
        }
        inv.setItem(49, button(Material.LIME_DYE, "&a&lOPEN CRATE",
                List.of("&7Consume one " + titleCase(type) + " key", "&7Reward is selected server-side")));
        inv.setItem(53, button(Material.BARRIER, "&cClose", List.of()));
        p.openInventory(inv);
    }
    private ItemStack button(Material m,String name,List<String> lore){ItemStack i=new ItemStack(m);ItemMeta meta=i.getItemMeta();meta.displayName(color(name));meta.lore(lore.stream().map(this::color).toList());i.setItemMeta(meta);return i;}

    @EventHandler public void interact(PlayerInteractEvent e) {
        if(e.getAction()!=Action.RIGHT_CLICK_BLOCK||e.getClickedBlock()==null)return;
        String key=locKey(e.getClickedBlock().getLocation());if(!crates.containsKey(key))return;
        e.setCancelled(true);Player p=e.getPlayer();String type=data.getString(key+".type","common");if(type.equalsIgnoreCase("gambling"))type="crimson";openCrate(p,type);
    }
    @EventHandler public void protectBreak(BlockBreakEvent e){if(crates.containsKey(locKey(e.getBlock().getLocation()))){e.setCancelled(true);e.getPlayer().sendMessage(Component.text("This crate is protected.",NamedTextColor.RED));}}
    @EventHandler public void protectPlace(BlockPlaceEvent e){if(crates.containsKey(locKey(e.getBlockPlaced().getLocation()))){e.setCancelled(true);}}
    @EventHandler public void worldLoad(WorldLoadEvent e){Bukkit.getScheduler().runTask(plugin,()->{loadLocations();rebuildHolograms();});}
    @EventHandler public void protectEntityExplosion(EntityExplodeEvent e){e.blockList().removeIf(b->crates.containsKey(locKey(b.getLocation())));}
    @EventHandler public void protectBlockExplosion(BlockExplodeEvent e){e.blockList().removeIf(b->crates.containsKey(locKey(b.getLocation())));}
    @EventHandler public void protectPiston(org.bukkit.event.block.BlockPistonExtendEvent e){for(Block b:e.getBlocks())if(crates.containsKey(locKey(b.getLocation()))){e.setCancelled(true);return;}}
    @EventHandler public void protectPiston(org.bukkit.event.block.BlockPistonRetractEvent e){for(Block b:e.getBlocks())if(crates.containsKey(locKey(b.getLocation()))){e.setCancelled(true);return;}}

    private void startKeyallAnimation(Player player, String type, int amount) {
        final UUID id = player.getUniqueId();
        final String[] frames = new String[] { "━━━━━━━━━━━━━━━━━━━━", "🌿 KELVORA SMP", "Distributing server keys...",
            "✦ " + titleCase(type).toUpperCase(Locale.ROOT) + " CRATE ✦",
            "✦ " + amount + " " + titleCase(type).toUpperCase(Locale.ROOT) + " KEYS RECEIVED ✦" };
        final NamedTextColor[] colors = new NamedTextColor[] { NamedTextColor.GREEN, NamedTextColor.GREEN, NamedTextColor.LIGHT_PURPLE,
            NamedTextColor.GOLD, NamedTextColor.AQUA };
        final org.bukkit.scheduler.BukkitTask[] task = new org.bukkit.scheduler.BukkitTask[1];
        task[0] = Bukkit.getScheduler().runTaskTimer(plugin, new Runnable() {
            int step = 0;
            public void run() {
                Player current = Bukkit.getPlayer(id);
                if (current == null || !current.isOnline()) { Bukkit.getScheduler().cancelTask(task[0].getTaskId()); return; }
                Component headline = Component.text("🌿 KELVORA SMP", NamedTextColor.GREEN);
                Component subtitle = Component.text(frames[step], colors[step]);
                current.showTitle(net.kyori.adventure.title.Title.title(headline, subtitle));
                current.playSound(current.getLocation(), step == frames.length - 1 ? Sound.UI_TOAST_CHALLENGE_COMPLETE : Sound.BLOCK_NOTE_BLOCK_PLING, 0.85f, 0.8f + (step * 0.18f));
                current.getWorld().spawnParticle(org.bukkit.Particle.END_ROD, current.getLocation().add(0, 1.0, 0), step == frames.length - 1 ? 32 : 8, 0.45, 0.55, 0.45, 0.02);
                if (step == frames.length - 1) {
                    current.sendMessage(Component.text("You received exactly " + amount + " " + titleCase(type) + " key(s).", NamedTextColor.GREEN));
                    Bukkit.getScheduler().cancelTask(task[0].getTaskId()); return;
                }
                step++;
            }
        }, 1L, 12L);
    }

    @EventHandler(priority = EventPriority.HIGHEST) public void click(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player p)) return;
        String title = e.getView().getTitle();
        if (!protectedInventories.contains(e.getView().getTopInventory())) return;
        e.setCancelled(true);
        e.setResult(Event.Result.DENY);
        if (title.startsWith("KELVORA | EDIT ")) {
            String type = editingCrate.get(p.getUniqueId());
            List<String> rewards = editingRewards.get(p.getUniqueId());
            if (type == null || rewards == null) return;
            int raw = e.getRawSlot();
            if (raw == 45) { editingCrate.remove(p.getUniqueId()); editingRewards.remove(p.getUniqueId()); p.closeInventory(); return; }
            if (raw == 49 && e.getClickedInventory() == e.getView().getTopInventory()) {
                if (saveEditedRewards(type, rewards)) {
                    p.sendMessage(Component.text("Saved " + rewards.size() + " " + titleCase(type) + " rewards.", NamedTextColor.GREEN));
                    editingCrate.remove(p.getUniqueId()); editingRewards.remove(p.getUniqueId()); p.closeInventory();
                } else p.sendMessage(Component.text("Reward config validation/save failed. The previous reward pool was retained.", NamedTextColor.RED));
                return;
            }
            if (e.getClickedInventory() == e.getView().getTopInventory()) {
                if (raw == 53) {
                    p.sendMessage(Component.text("To add a reward, click the item in your lower inventory. Click a reward above to remove it, then SAVE REWARDS.", NamedTextColor.YELLOW));
                    return;
                }
                if (raw >= 0 && raw < 45 && raw < rewards.size()) {
                    String removed = rewards.remove(raw);
                    if (saveEditedRewards(type, rewards)) {
                        p.sendMessage(Component.text("Removed and saved reward " + removed + ".", NamedTextColor.YELLOW));
                    } else {
                        rewards.add(raw, removed);
                        p.sendMessage(Component.text("Could not save removal; the reward was restored.", NamedTextColor.RED));
                    }
                    Bukkit.getScheduler().runTask(plugin, () -> openRewardEditor(p, type));
                }
                return;
            }
            if (e.getClickedInventory() == e.getView().getBottomInventory()) {
                ItemStack source = e.getCurrentItem();
                if (source == null || source.getType().isAir() || !isAllowedReward(source.getType())) {
                    p.sendMessage(Component.text("That item cannot be a crate reward.", NamedTextColor.RED)); return;
                }
                if (rewards.size() >= 45) { p.sendMessage(Component.text("The editor supports up to 45 reward entries.", NamedTextColor.RED)); return; }
                String added = source.getType().name() + ":" + source.getAmount() + ":1:Common";
                rewards.add(added);
                if (saveEditedRewards(type, rewards)) {
                    p.sendMessage(Component.text("Added and saved " + source.getType().name() + " x" + source.getAmount() + ".", NamedTextColor.GREEN));
                } else {
                    rewards.remove(added);
                    p.sendMessage(Component.text("Could not save this reward; no change was kept.", NamedTextColor.RED));
                }
                Bukkit.getScheduler().runTask(plugin, () -> openRewardEditor(p, type));
            }
            return;
        }
        if (e.getClickedInventory() == null || e.getClickedInventory() != e.getView().getTopInventory()) return;
        if (title.equals(ADMIN)) {
            int slot = e.getRawSlot() - 10;
            if (slot >= 0 && slot < TYPES.size()) {
                String type = TYPES.get(slot);
                p.closeInventory();
                p.performCommand("crate spawn " + type);
            }
            return;
        }
        String type = openCrate.get(p.getUniqueId());
        if (type == null) return;
        if (title.startsWith("KELVORA | ROLLING ")) {
            if (e.getRawSlot() == 26) p.closeInventory();
            return;
        }
        if (e.getRawSlot() == 53) { p.closeInventory(); return; }
        if (e.getRawSlot() != 49 || !opening.add(p.getUniqueId())) return;
        if (keys(p, type) <= 0) {
            p.sendMessage(Component.text("You need a " + titleCase(type) + " key.", NamedTextColor.RED));
            opening.remove(p.getUniqueId()); return;
        }
        String reward = chooseReward(type);
        if (reward == null) {
            p.sendMessage(Component.text("No valid rewards configured; your key was not used.", NamedTextColor.RED));
            opening.remove(p.getUniqueId()); return;
        }
        String[] bits = reward.split(":", 5);
        final String rewardKind = bits[0].toUpperCase(Locale.ROOT);
        final double rewardValue;
        final int rewardAmount;
        final Material rewardMaterial;
        final ItemStack rewardItem;
        try {
            if (rewardKind.equals("KELVORAS") || rewardKind.equals("MONEY")) {
                rewardValue = Double.parseDouble(bits[1]);
                if (!Double.isFinite(rewardValue) || rewardValue <= 0) throw new IllegalArgumentException();
                rewardAmount = 1;
                rewardMaterial = null;
                rewardItem = null;
            } else if (rewardKind.equals("COMMAND")) {
                if (bits[1].isBlank()) throw new IllegalArgumentException();
                rewardValue = 0;
                rewardAmount = 1;
                rewardMaterial = null;
                rewardItem = null;
            } else {
                int amount = Integer.parseInt(bits[1]);
                if (!isValidRewardEntry(reward)) throw new IllegalArgumentException("Invalid configured reward");
                rewardValue = 0;
                rewardAmount = amount;
                rewardMaterial = isSpawnerRewardKind(rewardKind) ? Material.SPAWNER : Material.matchMaterial(bits[0]);
                rewardItem = createRewardItem(rewardKind, amount, bits.length > 4 ? bits[4] : "");
            }
        } catch (Exception ex) {
            p.sendMessage(Component.text("Configured reward is invalid. Your key was not used.", NamedTextColor.RED));
            opening.remove(p.getUniqueId()); return;
        }
        if (rewardItem != null && !canFit(p, rewardItem)) {
            p.sendMessage(Component.text("Make room in your inventory before opening this crate. Your key was not used.", NamedTextColor.RED));
            opening.remove(p.getUniqueId()); return;
        }
        String path = playerPath(p, type);
        int oldBalance = keys(p, type);
        data.set(path, oldBalance - 1);
        if (!save()) {
            data.set(path, oldBalance);
            p.sendMessage(Component.text("Could not save key balance; opening cancelled.", NamedTextColor.RED));
            opening.remove(p.getUniqueId()); return;
        }
        Inventory inv = Bukkit.createInventory(null, 27, Component.text("KELVORA | ROLLING " + titleCase(type), NamedTextColor.DARK_GREEN));
        protectedInventories.add(inv);
        for (int slot = 0; slot < 27; slot++) {
            if (slot < 9 || slot >= 18 || slot % 9 == 0 || slot % 9 == 8)
                inv.setItem(slot, button(Material.GREEN_STAINED_GLASS_PANE, " ", List.of()));
        }
        inv.setItem(4, button(Material.CHEST, "&6&l" + titleCase(type).toUpperCase(Locale.ROOT) + " CRATE", List.of("&7Reward reveal")));
        inv.setItem(13, button(Material.CLOCK, "&e&lRolling...", List.of("&7The server selected the reward securely")));
        inv.setItem(22, button(Material.YELLOW_STAINED_GLASS_PANE, "&ePlease wait...", List.of("&7One key consumed")));
        inv.setItem(26, button(Material.BARRIER, "&cClose", List.of("&7The reward will still be delivered")));
        p.openInventory(inv);
        final UUID id = p.getUniqueId();
        final org.bukkit.scheduler.BukkitTask[] task = new org.bukkit.scheduler.BukkitTask[1];
        task[0] = Bukkit.getScheduler().runTaskTimer(plugin, new Runnable() {
            int step = 0;
            public void run() {
                Player current = Bukkit.getPlayer(id);
                if (step < 20 && current != null && current.isOnline()) {
                    List<String> valid = configuredRewards(type).stream().filter(CrateManager.this::isValidRewardEntry).toList();
                    if (!valid.isEmpty()) {
                        String preview = valid.get(random.nextInt(valid.size()));
                        String[] previewBits = preview.split(":", 5);
                        inv.setItem(13, rewardDisplayItem(preview,
                                "&e&lRolling: " + rewardLabel(previewBits),
                                List.of("&7Rolling preview", "&8The final reward is selected securely")));
                    }
                    current.playSound(current.getLocation(), Sound.UI_BUTTON_CLICK, 0.4f, 0.75f + step * 0.06f);
                    step++; return;
                }
                Bukkit.getScheduler().cancelTask(task[0].getTaskId());
                if (current == null || !current.isOnline()) {
                    data.set(path, keysByUuid(id, type) + 1);
                    save();
                    opening.remove(id);
                    return;
                }
                ItemStack[] inventoryBefore = current.getInventory().getStorageContents();
                boolean delivered = true;
                String rewardLabel;
                try {
                    if (rewardKind.equals("KELVORAS")) {
                        KelvorasManager km = ((KelvoraSMP) plugin).passiveKelvorasManager();
                        delivered = km != null && km.deposit(current, rewardValue);
                        rewardLabel = KelvorasManager.format(rewardValue) + " Kelvoras";
                    } else if (rewardKind.equals("MONEY")) {
                        org.bukkit.plugin.RegisteredServiceProvider<net.milkbowl.vault.economy.Economy> provider = Bukkit.getServicesManager().getRegistration(net.milkbowl.vault.economy.Economy.class);
                        delivered = provider != null && provider.getProvider().depositPlayer(current, rewardValue).transactionSuccess();
                        rewardLabel = "$" + rewardValue + " Money";
                    } else if (rewardKind.equals("COMMAND")) {
                        String commandText = bits[1].replace("{player}", current.getName()).replace("%player%", current.getName());
                        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), commandText.startsWith("/") ? commandText.substring(1) : commandText);
                        rewardLabel = "Special reward";
                    } else {
                        Map<Integer, ItemStack> overflow = current.getInventory().addItem(rewardItem.clone());
                        if (!overflow.isEmpty()) {
                            current.getInventory().setStorageContents(inventoryBefore);
                            delivered = false;
                        }
                        rewardLabel = rewardLabel(bits);
                    }
                } catch (Exception deliveryError) {
                    delivered = false;
                    rewardLabel = "Reward";
                    plugin.getLogger().warning("Crate reward delivery failed for " + current.getName() + ": " + deliveryError.getMessage());
                }
                if (!delivered) {
                    data.set(path, keys(current, type) + 1);
                    save();
                    current.sendMessage(Component.text("Reward delivery failed; your key was refunded.", NamedTextColor.RED));
                } else {
                    String rarity = bits.length > 3 ? bits[3] : "Common";
                    current.sendMessage(Component.text("Crate reward: " + rewardLabel, NamedTextColor.GOLD));
                    current.playSound(current.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.6f, 1.2f);
                    if (plugin.getConfig().getBoolean("crates.broadcast-rare-rewards", true) && (rarity.equalsIgnoreCase("Legendary") || rarity.equalsIgnoreCase("Mythic")))
                        Bukkit.broadcast(Component.text(current.getName() + " won " + rewardLabel + " from a " + titleCase(type) + " Crate!", NamedTextColor.GOLD));
                }
                if (delivered) {
                    inv.setItem(13, rewardDisplayItem(reward, "&a&lREWARD WON: " + rewardLabel, List.of("&7Your reward has been delivered")));
                    inv.setItem(22, button(Material.LIME_STAINED_GLASS_PANE, "&a&lSUCCESS", List.of("&7Enjoy your reward")));
                } else {
                    inv.setItem(13, button(Material.BARRIER, "&c&lDELIVERY FAILED", List.of("&7Your key was refunded")));
                    inv.setItem(22, button(Material.RED_STAINED_GLASS_PANE, "&cRefunded", List.of()));
                }
                opening.remove(id);
                Bukkit.getScheduler().runTaskLater(plugin, () -> {
                    Player online = Bukkit.getPlayer(id);
                    if (online != null && online.isOnline() && online.getOpenInventory().getTopInventory() == inv) online.closeInventory();
                }, 40L);
            }
        }, 1L, 3L);
    }

    private int keysByUuid(UUID id, String type) { return data.getInt("players." + id + "." + type, 0); }

    private boolean canFit(Player player, ItemStack item) {
        int remaining = item.getAmount();
        for (ItemStack stack : player.getInventory().getStorageContents()) {
            if (stack == null || stack.getType().isAir()) continue;
            if (stack.isSimilar(item)) remaining -= Math.max(0, stack.getMaxStackSize() - stack.getAmount());
            if (remaining <= 0) return true;
        }
        return remaining <= 0 || player.getInventory().firstEmpty() != -1;
    }

    @EventHandler(priority = EventPriority.HIGHEST) public void drag(InventoryDragEvent e){if(protectedInventories.contains(e.getView().getTopInventory())){e.setCancelled(true); e.setResult(Event.Result.DENY);}}
    @EventHandler public void close(org.bukkit.event.inventory.InventoryCloseEvent e){protectedInventories.remove(e.getInventory()); if(e.getPlayer() instanceof Player p && e.getView().getTitle().startsWith("KELVORA | EDIT ") && !e.getView().getTitle().equals("KELVORA | EDIT ")) { /* edits remain in memory until save/cancel; reconnecting cannot persist them */ }}
    @EventHandler public void quit(org.bukkit.event.player.PlayerQuitEvent e) {
        openCrate.remove(e.getPlayer().getUniqueId());
        // A consumed key is refunded by the pending completion if the player disconnects.
    }
    private boolean isAllowedReward(Material material) {
        if (material == null || !material.isItem() || material.isAir()) return false;
        String name = material.name();
        if (name.endsWith("_SPAWN_EGG")) return false;
        return !Set.of("BEDROCK","BARRIER","COMMAND_BLOCK","CHAIN_COMMAND_BLOCK","REPEATING_COMMAND_BLOCK",
            "STRUCTURE_BLOCK","STRUCTURE_VOID","JIGSAW","DEBUG_STICK","KNOWLEDGE_BOOK","END_PORTAL_FRAME",
            "END_PORTAL","END_GATEWAY","SPAWNER","TRIAL_SPAWNER","VAULT","BUDDING_AMETHYST",
            "REINFORCED_DEEPSLATE","COMMAND_BLOCK_MINECART").contains(name);
    }

    private boolean isSpawnerRewardKind(String kind) {
        return kind != null && Set.of("SPAWNER_SKELETON", "SPAWNER_IRON_GOLEM", "SPAWNER_SPIDER")
                .contains(kind.toUpperCase(Locale.ROOT));
    }

    private org.bukkit.entity.EntityType spawnerEntityForReward(String kind) {
        if (kind == null) return null;
        return switch (kind.toUpperCase(Locale.ROOT)) {
            case "SPAWNER_SKELETON" -> org.bukkit.entity.EntityType.SKELETON;
            case "SPAWNER_IRON_GOLEM" -> org.bukkit.entity.EntityType.IRON_GOLEM;
            case "SPAWNER_SPIDER" -> org.bukkit.entity.EntityType.SPIDER;
            default -> null;
        };
    }

    private String spawnerMobName(String kind) {
        org.bukkit.entity.EntityType type = spawnerEntityForReward(kind);
        return type == null ? "Mob" : titleCase(type.name().toLowerCase(Locale.ROOT).replace('_', ' '));
    }

    private ItemStack createRewardItem(String kind, int amount, String enchantText) {
        if (isSpawnerRewardKind(kind)) {
            org.bukkit.entity.EntityType entityType = spawnerEntityForReward(kind);
            if (entityType == null || amount != 1) throw new IllegalArgumentException("Unsupported spawner reward");
            ItemStack item = new ItemStack(Material.SPAWNER, 1);
            ItemMeta meta = item.getItemMeta();
            String mob = spawnerMobName(kind);
            meta.displayName(color("&a&l" + mob + " Spawner"));
            String plural = switch (entityType) {
                case SKELETON -> "Skeletons";
                case IRON_GOLEM -> "Iron Golems";
                case SPIDER -> "Spiders";
                default -> mob + "s";
            };
            meta.lore(List.of(color("&7Place to create a " + mob + " spawner"),
                    color("&8Spawns " + plural), color("&5Kelvora Spawner")));
            meta.getPersistentDataContainer().set(spawnerRewardKey, PersistentDataType.STRING, entityType.name());
            item.setItemMeta(meta);
            return item;
        }
        Material material = Material.matchMaterial(kind);
        if (!isAllowedReward(material) || amount < 1 || amount > material.getMaxStackSize()) {
            throw new IllegalArgumentException("Unsupported crate reward item");
        }
        ItemStack item = new ItemStack(material, amount);
        if (enchantText != null && !enchantText.isBlank()) {
            Map<org.bukkit.enchantments.Enchantment, Integer> enchants = parseEnchantments(enchantText);
            if (enchants == null) throw new IllegalArgumentException("Invalid reward enchantments");
            enchants.forEach(item::addUnsafeEnchantment);
        }
        return item;
    }

    private ItemStack rewardDisplayItem(String entry, String displayName, List<String> lore) {
        String[] bits = entry.split(":", 5);
        if (bits.length < 4) throw new IllegalArgumentException("Invalid reward entry");
        String kind = bits[0].toUpperCase(Locale.ROOT);
        ItemStack item;
        if (isSpawnerRewardKind(kind)) {
            item = createRewardItem(kind, 1, "");
        } else if (kind.equals("MONEY")) {
            item = new ItemStack(Material.GOLD_INGOT);
        } else if (kind.equals("KELVORAS")) {
            item = new ItemStack(Material.AMETHYST_SHARD);
        } else if (kind.equals("COMMAND")) {
            String label = rewardLabel(bits);
            Material icon = label.contains("Drill") ? Material.DIAMOND_PICKAXE
                    : label.contains("Gainer") ? Material.NETHERITE_PICKAXE
                    : label.contains("Sellaxe") || label.contains("Tree Trooper") ? Material.NETHERITE_AXE
                    : Material.NETHER_STAR;
            item = new ItemStack(icon);
        } else {
            item = createRewardItem(kind, 1, bits.length > 4 ? bits[4] : "");
        }
        ItemMeta meta = item.getItemMeta();
        meta.displayName(color(displayName));
        meta.lore(lore.stream().map(this::color).toList());
        item.setItemMeta(meta);
        return item;
    }

    private boolean isValidRewardEntry(String entry) {
        String[] bits = entry.split(":", 5);
        if (bits.length < 4) return false;
        try {
            double weight = Double.parseDouble(bits[2]);
            if (!Double.isFinite(weight) || weight <= 0) return false;
            String kind = bits[0].toUpperCase(Locale.ROOT);
            if (kind.equals("MONEY") || kind.equals("KELVORAS")) {
                double amount = Double.parseDouble(bits[1]);
                return Double.isFinite(amount) && amount > 0;
            }
            if (kind.equals("COMMAND")) return !bits[1].isBlank();
            int amount = Integer.parseInt(bits[1]);
            if (isSpawnerRewardKind(kind)) return amount == 1;
            Material material = Material.matchMaterial(bits[0]);
            if (!isAllowedReward(material) || amount < 1 || amount > material.getMaxStackSize()) return false;
            return bits.length <= 4 || bits[4].isBlank() || parseEnchantments(bits[4]) != null;
        } catch (RuntimeException ex) {
            return false;
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void applySpawnerRewardOnPlace(BlockPlaceEvent event) {
        ItemStack hand = event.getItemInHand();
        if (hand == null || !hand.hasItemMeta()) return;
        String entityName = hand.getItemMeta().getPersistentDataContainer()
                .get(spawnerRewardKey, PersistentDataType.STRING);
        if (entityName == null) return;
        org.bukkit.entity.EntityType entityType;
        try { entityType = org.bukkit.entity.EntityType.valueOf(entityName); }
        catch (IllegalArgumentException ex) { return; }
        if (!Set.of(org.bukkit.entity.EntityType.SKELETON, org.bukkit.entity.EntityType.IRON_GOLEM,
                org.bukkit.entity.EntityType.SPIDER).contains(entityType)) return;
        Block placed = event.getBlockPlaced();
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (placed.getType() != Material.SPAWNER) return;
            org.bukkit.block.BlockState state = placed.getState();
            if (!(state instanceof org.bukkit.block.CreatureSpawner spawner)) return;
            spawner.setSpawnedType(entityType);
            spawner.update(true, false);
        });
    }

    private YamlConfiguration crateConfig(String type) {
        if (!crateConfigDirectory.exists()) crateConfigDirectory.mkdirs();
        File file = new File(crateConfigDirectory, type.toLowerCase(Locale.ROOT) + ".yml");
        if (!file.exists()) {
            YamlConfiguration created = new YamlConfiguration();
            String base = "crates.types." + type;
            created.set("name", plugin.getConfig().getString(base + ".name", titleCase(type) + " Crate"));
            created.set("description", plugin.getConfig().getString(base + ".description", "Special rewards"));
            created.set("block", plugin.getConfig().getString(base + ".block", "SHULKER_BOX"));
            created.set("rewards", plugin.getConfig().getStringList(base + ".rewards"));
            ConfigurationSection exact = plugin.getConfig().getConfigurationSection(base + ".exact-chances");
            if (exact != null) created.set("exact-chances", exact.getValues(false));
            try { created.save(file); } catch (IOException ex) { plugin.getLogger().warning("Could not create " + file.getPath() + ": " + ex.getMessage()); }
        }
        return YamlConfiguration.loadConfiguration(file);
    }

    private List<String> configuredRewards(String type) {
        return new ArrayList<>(crateConfig(type).getStringList("rewards"));
    }
    private void validateConfiguredRewards() {
        for (String type : TYPES) {
            List<String> entries = configuredRewards(type);
            if (entries.isEmpty()) plugin.getLogger().warning("Crate '" + type + "' has no configured rewards.");
            for (String entry : entries) {
                String[] b = entry.split(":", 5);
                boolean valid = false;
                if (b.length >= 4) {
                    String kind = b[0].toUpperCase(Locale.ROOT);
                    try {
                        double weight = Double.parseDouble(b[2]);
                        valid = Double.isFinite(weight) && weight > 0;
                        if (kind.equals("KELVORAS") || kind.equals("MONEY")) {
                            double amount = Double.parseDouble(b[1]);
                            valid &= Double.isFinite(amount) && amount > 0;
                        } else if (kind.equals("COMMAND")) {
                            valid &= !b[1].isBlank();
                        } else {
                            int amount = Integer.parseInt(b[1]);
                            if (isSpawnerRewardKind(kind)) {
                                valid &= amount == 1;
                            } else {
                                Material material = Material.matchMaterial(b[0]);
                                valid &= isAllowedReward(material) && amount > 0 && amount <= material.getMaxStackSize();
                                if (b.length > 4 && !b[4].isBlank()) valid &= parseEnchantments(b[4]) != null;
                            }
                        }
                    } catch (Exception ex) { valid = false; }
                }
                if (!valid) plugin.getLogger().warning("Invalid or disallowed reward in crates.types." + type + ": " + entry);
            }
            ConfigurationSection chances = crateConfig(type).getConfigurationSection("exact-chances");
            if (chances != null) {
                double totalChance = 0.0;
                for (String key : chances.getKeys(false)) {
                    double percent = chances.getDouble(key, -1);
                    Material material = Material.matchMaterial(key);
                    boolean present = entries.stream().anyMatch(value -> value.split(":", 5)[0].equalsIgnoreCase(key));
                    if (!Double.isFinite(percent) || percent < 0 || percent >= 100 || !isAllowedReward(material) || !present) {
                        plugin.getLogger().warning("Invalid or disabled exact chance in crates.types." + type + ".exact-chances." + key + ": " + percent);
                    } else totalChance += percent;
                }
                if (totalChance >= 100) plugin.getLogger().warning("Exact chances for crate '" + type + "' total " + totalChance + "%; must be below 100%.");
            }
        }
    }


    private String chooseReward(String type) {
        List<String> valid = new ArrayList<>();
        double total = 0.0;
        for (String entry : configuredRewards(type)) {
            String[] bits = entry.split(":", 5);
            if (bits.length < 4) continue;
            boolean ok = false;
            try {
                double weight = Double.parseDouble(bits[2]);
                if (!Double.isFinite(weight) || weight <= 0) continue;
                String kind = bits[0].toUpperCase(Locale.ROOT);
                if (kind.equals("KELVORAS") || kind.equals("MONEY")) {
                    double amount = Double.parseDouble(bits[1]);
                    ok = Double.isFinite(amount) && amount > 0;
                } else if (kind.equals("COMMAND")) {
                    ok = !bits[1].isBlank();
                } else {
                    int amount = Integer.parseInt(bits[1]);
                    if (isSpawnerRewardKind(kind)) {
                        ok = amount == 1;
                    } else {
                        Material material = Material.matchMaterial(bits[0]);
                        ok = isAllowedReward(material) && amount > 0 && amount <= material.getMaxStackSize();
                        if (ok && bits.length > 4 && !bits[4].isBlank()) ok = parseEnchantments(bits[4]) != null;
                    }
                }
                if (!ok) continue;
                total += weight;
                valid.add(entry);
            } catch (RuntimeException ignored) { }
        }
        if (total <= 0) return null;
        double pick = random.nextDouble() * total;
        for (String entry : valid) {
            pick -= Double.parseDouble(entry.split(":", 5)[2]);
            if (pick < 0) return entry;
        }
        return valid.get(valid.size() - 1);
    }
    private boolean save(){try{data.save(dataFile);return true;}catch(IOException e){plugin.getLogger().severe("Could not save crate data: "+e.getMessage());return false;}}
    private void generateCoverageReport(){
        File report=new File(plugin.getDataFolder(),"crate-item-coverage.txt");List<String> lines=new ArrayList<>();int eligible=0,configured=0,excluded=0;
        lines.add("Kelvora crate material coverage report — generated "+new Date());lines.add("Reward config uses RELATIVE WEIGHTS per opening, not exact per-opening percentages.");
        Set<String> configuredNames=new HashSet<>();for(String type:TYPES)for(String r:configuredRewards(type)){String[] b=r.split(":");if(b.length>0)configuredNames.add(b[0]);}
        for(Material m:Material.values()){
            String n=m.name();String reason=null;
            if(!m.isItem()||m.isAir())reason="not an item / air";
            else if(n.endsWith("_SPAWN_EGG"))reason="spawn eggs excluded by default";
            else if(Set.of("BEDROCK","BARRIER","COMMAND_BLOCK","CHAIN_COMMAND_BLOCK","REPEATING_COMMAND_BLOCK","STRUCTURE_BLOCK","STRUCTURE_VOID","JIGSAW","DEBUG_STICK","KNOWLEDGE_BOOK","END_PORTAL_FRAME","END_PORTAL","END_GATEWAY","SPAWNER","TRIAL_SPAWNER","VAULT","BUDDING_AMETHYST","REINFORCED_DEEPSLATE").contains(n))reason="technical, unobtainable, or protected block";
            if(reason!=null){excluded++;lines.add("EXCLUDED "+n+" — "+reason);continue;}
            eligible++;if(configuredNames.contains(n)){configured++;lines.add("CONFIGURED "+n);}else lines.add("ELIGIBLE_NOT_CONFIGURED "+n+" — valid item not currently in default reward pools");
        }
        lines.add(0,"Summary: eligible="+eligible+", configured="+configured+", excluded="+excluded);
        try{java.nio.file.Files.write(report.toPath(),lines);}catch(IOException e){plugin.getLogger().warning("Could not write crate coverage report: "+e.getMessage());}
    }
    @Override public List<String> onTabComplete(CommandSender s,Command c,String a,String[] args){
        if(a.equalsIgnoreCase("keyall")) return args.length==1 ? TYPES : List.of();
        if(args.length==1) return List.of("spawn","remove","list","keys","givekey","reload","edit","add","modify","remove-reward");
        if(args.length==2&&args[0].equalsIgnoreCase("spawn")) return TYPES;
        if(args.length==2&&args[0].equalsIgnoreCase("edit")) return TYPES;
        if(args.length==2&&args[0].equalsIgnoreCase("givekey")) return Bukkit.getOnlinePlayers().stream().map(Player::getName).toList();
        if(args.length==3&&args[0].equalsIgnoreCase("givekey")) return TYPES;
        return List.of();
    }
}
