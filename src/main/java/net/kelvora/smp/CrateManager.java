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

final class CrateManager implements Listener, CommandExecutor, TabCompleter {
    private static final List<String> TYPES = List.of("common", "kelvora", "gold", "spawner", "gambling");
    private final JavaPlugin plugin;
    private final NamespacedKey holoKey;
    private final File dataFile;
    private YamlConfiguration data;
    private final Map<String, Location> crates = new LinkedHashMap<>();
    private final Map<UUID, String> openCrate = new HashMap<>();
    private final Set<UUID> opening = ConcurrentHashMap.newKeySet();
    private final java.security.SecureRandom random = new java.security.SecureRandom();
    private final String GUI = "KELVORA | CRATES";
    private final String ADMIN = "KELVORA | CRATE ADMIN";

    CrateManager(JavaPlugin plugin) {
        this.plugin = plugin;
        this.holoKey = new NamespacedKey(plugin, "crate-hologram");
        this.dataFile = new File(plugin.getDataFolder(), "crates-data.yml");
    }

    void register() {
        data = YamlConfiguration.loadConfiguration(dataFile);
        loadLocations();
        rebuildHolograms();
        Bukkit.getPluginManager().registerEvents(this, plugin);
        if (plugin.getCommand("crate") != null) {
            plugin.getCommand("crate").setExecutor(this);
            plugin.getCommand("crate").setTabCompleter(this);
        }
        if (plugin.getCommand("keyall") != null) plugin.getCommand("keyall").setExecutor(this);
        generateCoverageReport();
    }

    private void loadLocations() {
        crates.clear();
        ConfigurationSectionLike:
        for (String key : data.getKeys(false)) {
            String path = key + ".location";
            String worldName = data.getString(path + ".world");
            World world = worldName == null ? null : Bukkit.getWorld(worldName);
            if (world == null) continue;
            crates.put(key, new Location(world, data.getInt(path + ".x"), data.getInt(path + ".y"), data.getInt(path + ".z")));
        }
    }

    private void rebuildHolograms() {
        for (World world : Bukkit.getWorlds()) for (Entity entity : world.getEntities()) {
            if (entity instanceof ArmorStand stand && stand.getPersistentDataContainer().has(holoKey, PersistentDataType.STRING)) stand.remove();
        }
        for (Map.Entry<String, Location> entry : crates.entrySet()) createHologram(entry.getKey(), entry.getValue());
    }

    private void createHologram(String type, Location block) {
        if (block.getWorld() == null) return;
        String title = plugin.getConfig().getString("crates.types." + type + ".name", titleCase(type) + " Crate");
        String desc = plugin.getConfig().getString("crates.types." + type + ".description", "Special rewards");
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
    private boolean admin(CommandSender sender) { return sender.isOp() || sender.hasPermission("kelvora.crate.admin"); }
    private String validType(String s) { if (s == null) return null; String v=s.toLowerCase(Locale.ROOT); return TYPES.contains(v)?v:null; }

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (label.equalsIgnoreCase("keyall")) {
            if (!admin(sender)) { sender.sendMessage("You do not have permission."); return true; }
            if (args.length != 2) { sender.sendMessage("Usage: /keyall <type> <amount>"); return true; }
            String type=validType(args[0]); int amount=parseAmount(args[1]);
            if (type==null || amount<1) { sender.sendMessage("Invalid crate type or amount."); return true; }
            List<Player> recipients = new ArrayList<>(Bukkit.getOnlinePlayers());
            for (Player p : recipients) {
                addKeys(p, type, amount);
                p.sendMessage(Component.text("You received " + amount + " digital " + titleCase(type) + " key(s) from the server.", NamedTextColor.GOLD));
                startKeyallAnimation(p, type, amount);
            }
            Bukkit.broadcast(Component.text("🎁 " + titleCase(type) + " keys for everyone! Each online player received " + amount + " digital key(s).", NamedTextColor.LIGHT_PURPLE));
            sender.sendMessage("Granted "+amount+" digital "+titleCase(type)+" key(s) to "+recipients.size()+" online players.");
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
            return true;
        }
        if (!admin(sender)) { sender.sendMessage("You do not have permission."); return true; }
        if (sub.equals("spawn")) {
            if (!(sender instanceof Player p) || args.length!=2) { sender.sendMessage("Usage: /crate spawn <type> (in-game)"); return true; }
            String type=validType(args[1]); if(type==null){sender.sendMessage("Types: "+String.join(", ",TYPES));return true;}
            Block target=p.getTargetBlockExact(6);
            Location loc;
            if(target!=null) loc=(target.getType().isAir()?target:target.getRelative(0,1,0)).getLocation();
            else loc=p.getLocation().getBlock().getRelative(0,2,0).getLocation();
            if(!loc.getBlock().isPassable()){sender.sendMessage("Choose a clear block location (look at the ground or an empty space).");return true;}
            Material appearance=Material.matchMaterial(plugin.getConfig().getString("crates.types."+type+".block","BARREL"));
            if(appearance==null||!appearance.isBlock()||appearance.isAir())appearance=Material.BARREL;
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
            target.setType(Material.AIR); crates.remove(key);data.set(key,null);save();rebuildHolograms();
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
            addKeys(target,type,amount);sender.sendMessage("Granted "+amount+" digital "+titleCase(type)+" key(s) to "+target.getName());return true;
        }
        sender.sendMessage("Usage: /crate <spawn <type>|remove|list|keys|givekey <player> <type> <amount>>");return true;
    }
    private int parseAmount(String s){try{int n=Integer.parseInt(s);return n>0&&n<=100000?n:-1;}catch(Exception e){return -1;}}
    private String playerPath(Player p,String type){return "players."+p.getUniqueId()+"."+type;}
    private int keys(Player p,String type){return data.getInt(playerPath(p,type),0);}
    private void addKeys(Player p,String type,int amount){String path=playerPath(p,type);data.set(path,Math.min(2_000_000_000L,(long)data.getInt(path,0)+amount));save();}

    private void openAdmin(Player p) {
        Inventory inv=Bukkit.createInventory(null,27,Component.text(ADMIN,NamedTextColor.DARK_GREEN));
        int slot=10;for(String type:TYPES){ItemStack item=new ItemStack(Material.CHEST);ItemMeta meta=item.getItemMeta();meta.displayName(color("&6"+titleCase(type)+" Crate"));meta.lore(List.of(color("&7Spawn a physical crate"),color("&fClick to spawn at your target/location")));item.setItemMeta(meta);inv.setItem(slot++,item);}
        ItemStack list=new ItemStack(Material.PAPER);ItemMeta lm=list.getItemMeta();lm.displayName(color("&aRegistered crates: "+crates.size()));list.setItemMeta(lm);inv.setItem(22,list);p.openInventory(inv);
    }
    private void openCrate(Player p,String type) {
        openCrate.put(p.getUniqueId(),type);
        Inventory inv=Bukkit.createInventory(null,27,Component.text(GUI+" | "+titleCase(type),NamedTextColor.DARK_GREEN));
        inv.setItem(4,button(Material.CHEST,"&6&l"+titleCase(type)+" Crate",List.of("&7"+plugin.getConfig().getString("crates.types."+type+".description","Special rewards"))));
        inv.setItem(13,button(Material.TRIPWIRE_HOOK,"&eYour Keys: &f"+keys(p,type),List.of("&7Digital keys only","&7One key per successful opening")));
        List<String> rewards=plugin.getConfig().getStringList("crates.types."+type+".rewards");
        int slot=9;for(String entry:rewards){String[] bits=entry.split(":");Material mat=bits.length>0?Material.matchMaterial(bits[0]):null;if(mat==null||mat.isAir()||!mat.isItem())continue;inv.setItem(slot++,button(mat,"&f"+titleCase(mat.name().toLowerCase(Locale.ROOT).replace('_',' ')),List.of("&7Possible reward","&8Tier: "+(bits.length>3?bits[3]:"Common"))));if(slot>=18)break;}
        inv.setItem(22,button(Material.LIME_DYE,"&a&lOPEN CRATE",List.of("&7Consume one "+titleCase(type)+" key","&7Rewards are randomly selected")));
        inv.setItem(26,button(Material.BARRIER,"&cClose",List.of()));
        p.openInventory(inv);
    }
    private ItemStack button(Material m,String name,List<String> lore){ItemStack i=new ItemStack(m);ItemMeta meta=i.getItemMeta();meta.displayName(color(name));meta.lore(lore.stream().map(this::color).toList());i.setItemMeta(meta);return i;}

    @EventHandler public void interact(PlayerInteractEvent e) {
        if(e.getAction()!=Action.RIGHT_CLICK_BLOCK||e.getClickedBlock()==null)return;
        String key=locKey(e.getClickedBlock().getLocation());if(!crates.containsKey(key))return;
        e.setCancelled(true);Player p=e.getPlayer();String type=data.getString(key+".type","common");openCrate(p,type);
    }
    @EventHandler public void protectBreak(BlockBreakEvent e){if(crates.containsKey(locKey(e.getBlock().getLocation()))){e.setCancelled(true);e.getPlayer().sendMessage(Component.text("This crate is protected.",NamedTextColor.RED));}}
    @EventHandler public void protectPlace(BlockPlaceEvent e){if(crates.containsKey(locKey(e.getBlockPlaced().getLocation()))){e.setCancelled(true);}}
    @EventHandler public void worldLoad(WorldLoadEvent e){Bukkit.getScheduler().runTask(plugin,()->{loadLocations();rebuildHolograms();});}
    @EventHandler public void protectEntityExplosion(EntityExplodeEvent e){e.blockList().removeIf(b->crates.containsKey(locKey(b.getLocation())));}
    @EventHandler public void protectBlockExplosion(BlockExplodeEvent e){e.blockList().removeIf(b->crates.containsKey(locKey(b.getLocation())));}
    @EventHandler public void protectPiston(org.bukkit.event.block.BlockPistonExtendEvent e){for(Block b:e.getBlocks())if(crates.containsKey(locKey(b.getLocation()))){e.setCancelled(true);return;}}
    @EventHandler public void protectPiston(org.bukkit.event.block.BlockPistonRetractEvent e){for(Block b:e.getBlocks())if(crates.containsKey(locKey(b.getLocation()))){e.setCancelled(true);return;}}

    private void startKeyallAnimation(Player player, String type, int amount) {
        final String animationTitle = "KELVORA | KEY DELIVERY";
        Inventory inv = Bukkit.createInventory(null, 27, Component.text(animationTitle, NamedTextColor.DARK_PURPLE));
        inv.setItem(4, button(Material.CHEST, "&d&l" + titleCase(type) + " Keys", List.of("&7Digital keys already credited", "&f+" + amount + " key(s)", "&8Presentation only; no reward roll")));
        player.openInventory(inv);
        final org.bukkit.scheduler.BukkitTask[] task = new org.bukkit.scheduler.BukkitTask[1];
        task[0] = Bukkit.getScheduler().runTaskTimer(plugin, new Runnable() {
            int step = 0;
            public void run() {
                if (!player.isOnline() || !player.getOpenInventory().getTitle().equals(animationTitle)) { Bukkit.getScheduler().cancelTask(task[0].getTaskId()); return; }
                if (step < 8) {
                    Material[] cycle = {Material.CHEST, Material.ENDER_CHEST, Material.TRIPWIRE_HOOK, Material.AMETHYST_SHARD, Material.GOLD_INGOT};
                    inv.setItem(13, button(cycle[step % cycle.length], "&d&lRolling delivery...", List.of("&7Your keys are safe")));
                    player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.4f, 0.8f + step * 0.1f);
                    step++;
                } else {
                    inv.setItem(13, button(Material.LIME_DYE, "&a&lKEYS DELIVERED", List.of("&f+" + amount + " " + titleCase(type) + " key(s)", "&7Use a physical crate to open them")));
                    player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.6f, 1.2f);
                    Bukkit.getScheduler().cancelTask(task[0]);
                    Bukkit.getScheduler().runTaskLater(plugin, () -> { if (player.isOnline() && player.getOpenInventory().getTitle().equals(animationTitle)) player.closeInventory(); }, 30L);
                }
            }
        }, 1L, 3L);
    }

    @EventHandler public void click(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player p)) return;
        String title = e.getView().getTitle();
        if (title.equals("KELVORA | KEY DELIVERY")) { e.setCancelled(true); return; }
        if (!title.startsWith(GUI) && !title.equals(ADMIN)) return;
        e.setCancelled(true);
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
        String[] bits = reward.split(":");
        Material mat = Material.matchMaterial(bits[0]);
        int amount;
        try { amount = Integer.parseInt(bits[1]); } catch (Exception ex) { amount = 1; }
        if (mat == null || mat.isAir() || !mat.isItem() || amount < 1 || amount > mat.getMaxStackSize()) {
            p.sendMessage(Component.text("Configured reward is invalid. Your key was not used.", NamedTextColor.RED));
            opening.remove(p.getUniqueId()); return;
        }
        ItemStack rewardItem = new ItemStack(mat, amount);
        if (!canFit(p, rewardItem)) {
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
        final int[] task = new int[1];
        task[0] = Bukkit.getScheduler().runTaskTimer(plugin, new Runnable() {
            int step = 0;
            public void run() {
                Player current = Bukkit.getPlayer(id);
                if (step < 8 && current != null && current.isOnline()) {
                    List<String> entries = plugin.getConfig().getStringList("crates.types." + type + ".rewards");
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
                Bukkit.getScheduler().cancelTask(task[0]);
                if (current == null || !current.isOnline()) {
                    data.set(path, keysByUuid(id, type) + 1);
                    save();
                    opening.remove(id);
                    return;
                }
                Map<Integer, ItemStack> overflow = current.getInventory().addItem(rewardItem.clone());
                if (!overflow.isEmpty()) {
                    data.set(path, keys(current, type) + 1);
                    save();
                    current.sendMessage(Component.text("Reward delivery failed; your key was refunded.", NamedTextColor.RED));
                } else {
                    String rarity = bits.length > 3 ? bits[3] : "Common";
                    current.sendMessage(Component.text("Crate reward: " + amount + "x " + mat.name().toLowerCase(Locale.ROOT).replace('_',' '), NamedTextColor.GOLD));
                    current.playSound(current.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.6f, 1.2f);
                    if (plugin.getConfig().getBoolean("crates.broadcast-rare-rewards", true) && (rarity.equalsIgnoreCase("Legendary") || rarity.equalsIgnoreCase("Mythic")))
                        Bukkit.broadcast(Component.text(current.getName() + " won " + mat.name().toLowerCase(Locale.ROOT).replace('_',' ') + " from a " + titleCase(type) + " Crate!", NamedTextColor.GOLD));
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

    @EventHandler public void drag(InventoryDragEvent e){String t=e.getView().getTitle();if(t.startsWith(GUI)||t.equals(ADMIN))e.setCancelled(true);}
    @EventHandler public void quit(org.bukkit.event.player.PlayerQuitEvent e) {
        openCrate.remove(e.getPlayer().getUniqueId());
        // A consumed key is refunded by the pending completion if the player disconnects.
    }
    private String chooseReward(String type) {
        List<String> entries=plugin.getConfig().getStringList("crates.types."+type+".rewards");
        List<String> valid=new ArrayList<>();double total=0;
        for(String entry:entries){String[] b=entry.split(":");if(b.length<3)continue;Material m=Material.matchMaterial(b[0]);double w;try{w=Double.parseDouble(b[2]);}catch(Exception ex){continue;}int a;try{a=Integer.parseInt(b[1]);}catch(Exception ex){continue;}if(m==null||!m.isItem()||m.isAir()||a<1||a>m.getMaxStackSize()||!Double.isFinite(w)||w<=0)continue;total+=w;valid.add(entry);}
        if(total<=0)return null;double pick=random.nextDouble()*total;for(String e:valid){pick-=Double.parseDouble(e.split(":")[2]);if(pick<=0)return e;}return valid.get(valid.size()-1);
    }
    private boolean save(){try{data.save(dataFile);return true;}catch(IOException e){plugin.getLogger().severe("Could not save crate data: "+e.getMessage());return false;}}
    private void generateCoverageReport(){
        File report=new File(plugin.getDataFolder(),"crate-item-coverage.txt");List<String> lines=new ArrayList<>();int eligible=0,configured=0,excluded=0;
        lines.add("Kelvora crate material coverage report — generated "+new Date());lines.add("Reward config uses RELATIVE WEIGHTS per opening, not exact per-opening percentages.");
        Set<String> configuredNames=new HashSet<>();for(String type:TYPES)for(String r:plugin.getConfig().getStringList("crates.types."+type+".rewards")){String[] b=r.split(":");if(b.length>0)configuredNames.add(b[0]);}
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
        if(args.length==1) return List.of("spawn","remove","list","keys","givekey");
        if(args.length==2&&args[0].equalsIgnoreCase("spawn")) return TYPES;
        if(args.length==2&&args[0].equalsIgnoreCase("givekey")) return Bukkit.getOnlinePlayers().stream().map(Player::getName).toList();
        if(args.length==3&&args[0].equalsIgnoreCase("givekey")) return TYPES;
        return List.of();
    }
}
