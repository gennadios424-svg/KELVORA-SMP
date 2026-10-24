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
    private static final List<String> TYPES = List.of("common", "kelvora", "gold", "spawner", "crimson");
    private final JavaPlugin plugin;
    private final NamespacedKey holoKey;
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
        this.dataFile = new File(plugin.getDataFolder(), "crates-data.yml");
        this.crateConfigDirectory = new File(plugin.getDataFolder(), "crates");
    }

    void register() {
        data = YamlConfiguration.loadConfiguration(dataFile);
        migrateLegacyGamblingKeys();
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
            case "spawner" -> "PURPLE_SHULKER_BOX";
            case "crimson" -> "RED_SHULKER_BOX";
            default -> "SHULKER_BOX";
        };
    }

    private void rebuildHolograms() {
        for (World world : Bukkit.getWorlds()) for (Entity entity : world.getEntities()) {
            if (entity instanceof ArmorStand stand && stand.getPersistentDataContainer().has(holoKey, PersistentDataType.STRING)) stand.remove();
        }
        for (Map.Entry<String, Location> entry : crates.entrySet()) createHologram(entry.getKey(), entry.getValue());
    }

    private void createHologram(String type, Location block) {
        if (block.getWorld() == null) return;
        String title = crateConfig(type).getString("name", titleCase(type) + " Crate");
        String desc = crateConfig(type).getString("description", "Special rewards");
        Location base = block.clone().add(0.5, 1.15, 0.5);
        spawnLine(base, type, "&6&l" + title);
        spawnLine(base.clone().add(0, -0.27, 0), type, "&fRight-click to open");
        spawnLine(base.clone().add(0, -0.54, 0), type, "&7" + desc);
    }

    private void spawnLine(Location location, String type, String text) {
        ArmorStand stand = location.getWorld().spawn(location, ArmorStand.class, entity -> {
            entity.setInvisible(true); entity.setMarker(true); entity.setGravity(false); entity.setCustomNameVisible(true);
            entity.customName(color(text)); entity.setPersistent(true);
            entity.getPersistentDataContainer().set(holoKey, PersistentDataType.STRING, type);
        });
    }

    private String locKey(Location l) { return l.getWorld().getName() + "," + l.getBlockX() + "," + l.getBlockY() + "," + l.getBlockZ(); }
    private String titleCase(String s) { return s.substring(0,1).toUpperCase(Locale.ROOT) + s.substring(1).toLowerCase(Locale.ROOT); }
    private Component color(String s) { return net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.legacyAmpersand().deserialize(s); }
    private boolean admin(CommandSender sender) { return sender.isOp(); }
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
            String[] bits = entry.split(":", 4);
            if (bits.length < 4) continue;
            Material material = Material.matchMaterial(bits[0]);
            String kind = bits[0].toUpperCase(Locale.ROOT);
            if (kind.equals("MONEY")) material = Material.GOLD_INGOT;
            else if (kind.equals("KELVORAS")) material = Material.AMETHYST_SHARD;
            else if (kind.equals("COMMAND")) material = Material.COMMAND_BLOCK;
            if (material == null || !material.isItem()) continue;
            ItemStack icon = new ItemStack(material, 1);
            ItemMeta meta = icon.getItemMeta();
            meta.displayName(Component.text(bits[0] + " x" + bits[1], NamedTextColor.AQUA));
            meta.lore(List.of(Component.text("Weight: " + bits[2], NamedTextColor.GRAY), Component.text("Rarity: " + bits[3], NamedTextColor.GRAY), Component.text("Click to remove this reward", NamedTextColor.RED)));
            icon.setItemMeta(meta);
            inv.setItem(i, icon);
        }
        inv.setItem(45, button(Material.BARRIER, "&cCancel", List.of("&7Discard unsaved edits")));
        inv.setItem(49, button(Material.EMERALD, "&a&lSAVE REWARDS", List.of("&7Save reward list safely", "&7New inventory items use weight 1 / Common")));
        inv.setItem(53, button(Material.HOPPER, "&eAdd from inventory", List.of("&7Click an item in your inventory", "&7to add a copy as a reward", "&7Enchantments/custom metadata are not yet supported by this editor")));
        player.openInventory(inv);
    }

    private boolean saveEditedRewards(String type, List<String> rewards) {
        for (String entry : rewards) {
            String[] bits = entry.split(":", 4);
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
                    Material material = Material.matchMaterial(bits[0]);
                    int amount = Integer.parseInt(bits[1]);
                    if (!isAllowedReward(material) || amount <= 0 || amount > material.getMaxStackSize()) return false;
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

    private void openAdmin(Player p) {
        Inventory inv=Bukkit.createInventory(null,27,Component.text(ADMIN,NamedTextColor.DARK_GREEN));
        protectedInventories.add(inv);
        int slot=10;for(String type:TYPES){ItemStack item=new ItemStack(Material.CHEST);ItemMeta meta=item.getItemMeta();meta.displayName(color("&6"+titleCase(type)+" Crate"));meta.lore(List.of(color("&7Spawn a physical crate"),color("&fClick to spawn at your target/location")));item.setItemMeta(meta);inv.setItem(slot++,item);}
        ItemStack list=new ItemStack(Material.PAPER);ItemMeta lm=list.getItemMeta();lm.displayName(color("&aRegistered crates: "+crates.size()));list.setItemMeta(lm);inv.setItem(22,list);p.openInventory(inv);
    }
    private void openCrate(Player p,String type) {
        openCrate.put(p.getUniqueId(),type);
        Inventory inv=Bukkit.createInventory(null,27,Component.text(GUI+" | "+titleCase(type),NamedTextColor.DARK_GREEN));
        protectedInventories.add(inv);
        inv.setItem(4,button(Material.matchMaterial(defaultBlock(type)) == null ? Material.SHULKER_BOX : Material.matchMaterial(defaultBlock(type)),"&6&l"+titleCase(type)+" Crate",List.of("&7"+plugin.getConfig().getString("crates.types."+type+".description","Special rewards"))));
        inv.setItem(13,button(Material.TRIPWIRE_HOOK,"&eYour Keys: &f"+keys(p,type),List.of("&7Digital keys only","&7One key per successful opening")));
        List<String> rewards=configuredRewards(type);
        int slot=9;for(String entry:rewards){String[] bits=entry.split(":",4);if(bits.length<4)continue;String kind=bits[0].toUpperCase(Locale.ROOT);Material mat=Material.matchMaterial(bits[0]);if(kind.equals("KELVORAS")){inv.setItem(slot++,button(Material.AMETHYST_SHARD,"&b"+bits[1]+" Kelvoras",List.of("&7Possible currency reward","&8Tier: "+bits[3])));}else if(kind.equals("MONEY")){inv.setItem(slot++,button(Material.GOLD_INGOT,"&6$"+bits[1]+" Money",List.of("&7Possible currency reward","&8Tier: "+bits[3])));}else if(kind.equals("COMMAND")){inv.setItem(slot++,button(Material.COMMAND_BLOCK,"&dSpecial reward",List.of("&7Server reward","&8Tier: "+bits[3])));}else if(mat!=null&&isAllowedReward(mat)){inv.setItem(slot++,button(mat,"&f"+titleCase(mat.name().toLowerCase(Locale.ROOT).replace('_',' ')),List.of("&7Possible reward","&8Tier: "+bits[3])));}if(slot>=18)break;}
        inv.setItem(22,button(Material.LIME_DYE,"&a&lOPEN CRATE",List.of("&7Consume one "+titleCase(type)+" key","&7Rewards are randomly selected")));
        inv.setItem(26,button(Material.BARRIER,"&cClose",List.of()));
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
                if (step == frames.length - 1) {
                    current.sendMessage(Component.text("You received exactly " + amount + " " + titleCase(type) + " key(s).", NamedTextColor.GREEN));
                    Bukkit.getScheduler().cancelTask(task[0].getTaskId()); return;
                }
                step++;
            }
        }, 1L, 12L);
    }

    @EventHandler public void click(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player p)) return;
        String title = e.getView().getTitle();
        if (!protectedInventories.contains(e.getView().getTopInventory())) return;
        e.setCancelled(true);
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
                if (raw >= 0 && raw < 45 && raw < rewards.size()) {
                    String removed = rewards.remove(raw);
                    p.sendMessage(Component.text("Removed reward " + removed + ". Click SAVE REWARDS to persist.", NamedTextColor.YELLOW));
                    Bukkit.getScheduler().runTask(plugin, () -> openRewardEditor(p, type));
                }
                return;
            }
            if (e.getClickedInventory() == e.getView().getBottomInventory()) {
                ItemStack cursorItem = e.getCurrentItem();
                if (cursorItem == null || cursorItem.getType().isAir() || !isAllowedReward(cursorItem.getType())) return;
                if (cursorItem.getAmount() > cursorItem.getType().getMaxStackSize()) return;
                if (rewards.size() >= 45) { p.sendMessage(Component.text("The editor supports up to 45 reward entries.", NamedTextColor.RED)); return; }
                rewards.add(cursorItem.getType().name() + ":" + cursorItem.getAmount() + ":1:Common");
                p.sendMessage(Component.text("Added " + cursorItem.getType().name() + " x" + cursorItem.getAmount() + " as a reward template. Click SAVE REWARDS to persist.", NamedTextColor.GREEN));
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
        if (e.getRawSlot() == 26) { p.closeInventory(); return; }
        if (e.getRawSlot() != 22 || !opening.add(p.getUniqueId())) return;
        if (keys(p, type) <= 0) {
            p.sendMessage(Component.text("You need a " + titleCase(type) + " key.", NamedTextColor.RED));
            opening.remove(p.getUniqueId()); return;
        }
        String reward = chooseReward(type);
        if (reward == null) {
            p.sendMessage(Component.text("No valid rewards configured; your key was not used.", NamedTextColor.RED));
            opening.remove(p.getUniqueId()); return;
        }
        String[] bits = reward.split(":", 4);
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
                Material mat = Material.matchMaterial(bits[0]);
                int amount = Integer.parseInt(bits[1]);
                if (!isAllowedReward(mat) || amount < 1 || amount > mat.getMaxStackSize()) throw new IllegalArgumentException();
                rewardValue = 0;
                rewardAmount = amount;
                rewardMaterial = mat;
                rewardItem = new ItemStack(mat, amount);
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
        Inventory inv = e.getView().getTopInventory();
        inv.setItem(13, button(Material.CLOCK, "&e&lRolling...", List.of("&7The server selected the reward securely")));
        inv.setItem(22, button(Material.YELLOW_STAINED_GLASS_PANE, "&ePlease wait...", List.of("&7One key consumed")));
        final UUID id = p.getUniqueId();
        final org.bukkit.scheduler.BukkitTask[] task = new org.bukkit.scheduler.BukkitTask[1];
        task[0] = Bukkit.getScheduler().runTaskTimer(plugin, new Runnable() {
            int step = 0;
            public void run() {
                Player current = Bukkit.getPlayer(id);
                if (step < 8 && current != null && current.isOnline()) {
                    List<String> entries = configuredRewards(type);
                    List<String> valid = entries.stream().filter(x -> {
                        String[] q = x.split(":");
                        Material m = q.length > 0 ? Material.matchMaterial(q[0]) : null;
                        return q.length >= 4 && m != null && m.isItem() && !m.isAir();
                    }).toList();
                    if (!valid.isEmpty()) {
                        String preview = valid.get(random.nextInt(valid.size()));
                        Material previewMat = Material.matchMaterial(preview.split(":")[0]);
                        if (previewMat != null && previewMat.isItem() && !previewMat.isAir())
                            inv.setItem(13, button(previewMat, "&e&lRolling: " + titleCase(previewMat.name().toLowerCase(Locale.ROOT).replace('_',' ')), List.of("&7Preview only")));
                    }
                    current.playSound(current.getLocation(), Sound.UI_BUTTON_CLICK, 0.4f, 0.8f + step * 0.1f);
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
                        rewardLabel = rewardAmount + "x " + rewardMaterial.name().toLowerCase(Locale.ROOT).replace('_',' ');
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
                current.closeInventory();
                opening.remove(id);
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

    @EventHandler public void drag(InventoryDragEvent e){if(protectedInventories.contains(e.getView().getTopInventory()))e.setCancelled(true);}
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
                String[] b = entry.split(":", 4);
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
                            Material material = Material.matchMaterial(b[0]);
                            int amount = Integer.parseInt(b[1]);
                            valid &= isAllowedReward(material) && amount > 0 && amount <= material.getMaxStackSize();
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
                    boolean present = entries.stream().anyMatch(value -> value.split(":", 4)[0].equalsIgnoreCase(key));
                    if (!Double.isFinite(percent) || percent < 0 || percent >= 100 || !isAllowedReward(material) || !present) {
                        plugin.getLogger().warning("Invalid or disabled exact chance in crates.types." + type + ".exact-chances." + key + ": " + percent);
                    } else totalChance += percent;
                }
                if (totalChance >= 100) plugin.getLogger().warning("Exact chances for crate '" + type + "' total " + totalChance + "%; must be below 100%.");
            }
        }
    }

    private String chooseReward(String type) {
        List<String> entries = configuredRewards(type);
        ConfigurationSection chanceSection = crateConfig(type).getConfigurationSection("exact-chances");
        Set<String> exactChanceMaterials = new HashSet<>();
        if (chanceSection != null) {
            double totalExact = 0;
            for (String key : chanceSection.getKeys(false)) {
                double percent = chanceSection.getDouble(key, -1);
                if (!Double.isFinite(percent) || percent < 0 || percent >= 100) { plugin.getLogger().warning("Invalid exact crate chance " + type + "." + key + "=" + percent); continue; }
                Material material = Material.matchMaterial(key);
                if (!isAllowedReward(material) || entries.stream().noneMatch(entry -> entry.split(":", 4)[0].equalsIgnoreCase(material.name()))) {
                    plugin.getLogger().warning("Invalid or disabled exact-chance reward material " + type + "." + key); continue;
                }
                totalExact += percent;
                exactChanceMaterials.add(material.name());
            }
            if (totalExact >= 100) { plugin.getLogger().warning("Exact chances for crate " + type + " must total less than 100%."); return null; }
            double chanceRoll = random.nextDouble() * 100.0;
            double cumulativeChance = 0.0;
            for (String key : chanceSection.getKeys(false)) {
                double percent = chanceSection.getDouble(key, -1);
                Material material = Material.matchMaterial(key);
                if (!isAllowedReward(material) || !Double.isFinite(percent) || percent < 0 || percent >= 100) continue;
                cumulativeChance += percent;
                if (chanceRoll < cumulativeChance) {
                    for (String entry : entries) if (entry.split(":", 4)[0].equalsIgnoreCase(material.name())) return entry;
                    return material.name() + ":1:1:Mythic";
                }
            }
        }
        List<String> valid = new ArrayList<>(); double total = 0;
        for (String entry : entries) {
            String[] b = entry.split(":", 4); if (b.length < 4 || exactChanceMaterials.contains(b[0].toUpperCase(Locale.ROOT))) continue;
            double w; try { w = Double.parseDouble(b[2]); } catch (Exception ex) { continue; }
            if (!Double.isFinite(w) || w <= 0) continue;
            String kind = b[0].toUpperCase(Locale.ROOT); boolean ok = false;
            try {
                if (kind.equals("KELVORAS") || kind.equals("MONEY")) { double amount = Double.parseDouble(b[1]); ok = Double.isFinite(amount) && amount > 0; }
                else if (kind.equals("COMMAND")) ok = !b[1].isBlank();
                else { Material m = Material.matchMaterial(b[0]); int a = Integer.parseInt(b[1]); ok = isAllowedReward(m) && a > 0 && a <= m.getMaxStackSize(); }
            } catch (Exception ignored) { }
            if (!ok) continue; total += w; valid.add(entry);
        }
        if (total <= 0) return null;
        double pick = random.nextDouble() * total;
        for (String entry : valid) { pick -= Double.parseDouble(entry.split(":", 4)[2]); if (pick <= 0) return entry; }
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
        if(args.length==1) return List.of("spawn","remove","list","keys","givekey","reload","edit");
        if(args.length==2&&args[0].equalsIgnoreCase("spawn")) return TYPES;
        if(args.length==2&&args[0].equalsIgnoreCase("edit")) return TYPES;
        if(args.length==2&&args[0].equalsIgnoreCase("givekey")) return Bukkit.getOnlinePlayers().stream().map(Player::getName).toList();
        if(args.length==3&&args[0].equalsIgnoreCase("givekey")) return TYPES;
        return List.of();
    }
}
