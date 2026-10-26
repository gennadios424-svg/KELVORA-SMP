package net.kelvora.smp;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.time.LocalDate;
import java.time.temporal.WeekFields;
import java.util.*;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

/** Player guide, daily/weekly quests, and economy transfer audit helpers. */
final class KelvoraQualityManager implements Listener {
    private static final String GUIDE_TITLE = "KELVORA // PLAYER GUIDE";
    private static final String QUEST_TITLE = "KELVORA // QUESTS";
    private final JavaPlugin plugin;
    private final KelvorasManager kelvoras;
    private final NamespacedKey actionKey;
    private final File file;
    private final YamlConfiguration data;
    private final Set<String> playerPlaced = new HashSet<>();
    private Object economy;

    KelvoraQualityManager(JavaPlugin plugin, KelvorasManager kelvoras) {
        this.plugin = plugin;
        this.kelvoras = kelvoras;
        this.actionKey = new NamespacedKey(plugin, "quality-action");
        this.file = new File(plugin.getDataFolder(), "player-quests.yml");
        this.data = YamlConfiguration.loadConfiguration(file);
    }

    void register() {
        Bukkit.getPluginManager().registerEvents(this, plugin);
        bind("guide", (sender, args) -> { if (!(sender instanceof Player p)) { sender.sendMessage("Use /guide in-game."); return; } openGuide(p); });
        bind("quests", (sender, args) -> { if (!(sender instanceof Player p)) { sender.sendMessage("Use /quests in-game."); return; } openQuests(p); });
        bind("economylog", (sender, args) -> {
            if (!sender.isOp() && !sender.hasPermission("kelvora.economy.audit")) { sender.sendMessage(ChatColor.RED + "You do not have permission."); return; }
            File log = new File(plugin.getDataFolder(), "economy-audit.log");
            sender.sendMessage(ChatColor.GREEN + "Recent economy transfers:");
            if (!log.exists()) { sender.sendMessage(ChatColor.GRAY + "No transfer entries have been recorded yet."); return; }
            try {
                java.util.List<String> lines = java.nio.file.Files.readAllLines(log.toPath());
                int start = Math.max(0, lines.size() - 10);
                for (int i = start; i < lines.size(); i++) sender.sendMessage(ChatColor.GRAY + lines.get(i));
                sender.sendMessage(ChatColor.DARK_GRAY + "Showing up to the last 10 transfers. File: " + log.getAbsolutePath());
            } catch (IOException ex) { sender.sendMessage(ChatColor.RED + "Could not read the economy audit log."); }
        });
        Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            for (Player p : Bukkit.getOnlinePlayers()) {
                resetIfNeeded(p.getUniqueId());
                addProgress(p, "daily", "play", 1, 30, 750, "daily-play");
                addProgress(p, "weekly", "play", 1, 300, 3000, "weekly-play");
            }
        }, 1200L, 1200L);
    }

    private interface CommandAction { void run(CommandSender sender, String[] args); }
    private void bind(String name, CommandAction action) {
        if (plugin.getCommand(name) != null) plugin.getCommand(name).setExecutor((sender, command, label, args) -> { action.run(sender, args); return true; });
    }

    private void openGuide(Player p) {
        Inventory inv = Bukkit.createInventory(null, 27, net.kyori.adventure.text.Component.text(GUIDE_TITLE));
        for (int i=0;i<27;i++) inv.setItem(i, named(Material.GREEN_STAINED_GLASS_PANE, " ", null, List.of()));
        inv.setItem(10, named(Material.EMERALD, "&a&lECONOMY", "guide", List.of("&7Money: &f/bal", "&7Separate cosmetics currency: &f/kelvoras", "&7Browse purchases: &f/shop", "&7Sell items: &f/sell", "&7Check values: &f/worth", "&7Auction House: &f/ah")));
        inv.setItem(12, named(Material.NAME_TAG, "&2&lRANKS & COSMETICS", "guide", List.of("&7Ranks are purchased, not levelled up.", "&7Cosmetics: &f/shardshop", "&7Equip tags: &f/tags", "&7Glows: &f/glow", "&7Chat colours: &f/chatcolour")));
        inv.setItem(14, named(Material.COMPASS, "&b&lSURVIVAL", "guide", List.of("&7Random teleport: &f/rtp", "&7Homes: &f/sethome, /home", "&7Teams: &f/team", "&7Player teleport requests: &f/tpa")));
        inv.setItem(16, named(Material.BOOK, "&d&lHELP & COMMUNITY", "guide", List.of("&7Daily rewards: &f/daily", "&7Quests: &f/quests", "&7Server guide: &f/guide", "&7Discord: &f/discord", "&7Server IP: &fkelvorasmp.mcsh.gg")));
        inv.setItem(22, named(Material.BARRIER, "&c&lCLOSE", "close", List.of("&7Close the guide")));
        p.openInventory(inv);
    }

    private void openQuests(Player p) {
        resetIfNeeded(p.getUniqueId());
        UUID id=p.getUniqueId();
        Inventory inv=Bukkit.createInventory(null,27,net.kyori.adventure.text.Component.text(QUEST_TITLE));
        for(int i=0;i<27;i++) inv.setItem(i,named(Material.GREEN_STAINED_GLASS_PANE," ",null,List.of()));
        questItem(inv,10,id,"daily","mine",64,1500,Material.IRON_PICKAXE,"&a&lDAILY: MINER","Mine 64 blocks");
        questItem(inv,12,id,"daily","farm",32,1200,Material.WHEAT,"&e&lDAILY: FARMER","Harvest 32 crops");
        questItem(inv,14,id,"daily","mobs",10,1000,Material.IRON_SWORD,"&c&lDAILY: HUNTER","Defeat 10 hostile mobs");
        questItem(inv,16,id,"weekly","pvp",5,5000,Material.DIAMOND_SWORD,"&5&lWEEKLY: FIGHTER","Get 5 player kills");
        questItem(inv,21,id,"weekly","mine",500,4000,Material.DIAMOND_PICKAXE,"&b&lWEEKLY: EXCAVATOR","Mine 500 blocks");
        questItem(inv,23,id,"weekly","play",300,3000,Material.CLOCK,"&d&lWEEKLY: REGULAR","Play for 5 hours");
        inv.setItem(22,named(Material.AMETHYST_SHARD,"&b&lKELVORAS: ✦"+KelvorasManager.format(kelvoras.balance(p)),null,List.of("&7Quest rewards go to your Kelvoras balance.")));
        p.openInventory(inv);
    }

    private void questItem(Inventory inv,int slot,UUID id,String period,String key,int goal,int reward,Material icon,String title,String desc) {
        int progress=data.getInt(id+"."+period+".progress."+key,0);
        boolean claimed=data.getBoolean(id+"."+period+".claimed."+key,false);
        inv.setItem(slot,named(icon,title,null,List.of("&7"+desc,"","&7Progress: &f"+Math.min(progress,goal)+"&7/&f"+goal,"&7Reward: &b✦"+KelvorasManager.format(reward),"",claimed?"&a✔ Reward claimed":progress>=goal?"&a✔ Completed":"&eKeep going!")));
    }

    private ItemStack named(Material material,String title,String action,List<String> lore) {
        ItemStack item=new ItemStack(material); ItemMeta meta=item.getItemMeta();
        meta.displayName(net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.legacyAmpersand().deserialize(title));
        List<net.kyori.adventure.text.Component> lines=new ArrayList<>();
        for(String s:lore) lines.add(net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.legacyAmpersand().deserialize(s));
        meta.lore(lines);
        if(action!=null) meta.getPersistentDataContainer().set(actionKey,PersistentDataType.STRING,action);
        item.setItemMeta(meta); return item;
    }

    @EventHandler public void onClick(InventoryClickEvent e) {
        String title=ChatColor.stripColor(e.getView().getTitle());
        if(!GUIDE_TITLE.equals(title)&&!QUEST_TITLE.equals(title)) return;
        e.setCancelled(true);
        if(!(e.getWhoClicked() instanceof Player p)||e.getClickedInventory()!=e.getView().getTopInventory()) return;
        ItemStack item=e.getCurrentItem();
        if(item==null||!item.hasItemMeta()) return;
        String action=item.getItemMeta().getPersistentDataContainer().get(actionKey,PersistentDataType.STRING);
        if("close".equals(action)) p.closeInventory();
    }
    @EventHandler public void onDrag(InventoryDragEvent e) {
        String title=ChatColor.stripColor(e.getView().getTitle());
        if(GUIDE_TITLE.equals(title)||QUEST_TITLE.equals(title)) e.setCancelled(true);
    }

    @EventHandler public void onBreak(BlockBreakEvent e) {
        if(e.isCancelled()) return;
        Player p=e.getPlayer(); UUID id=p.getUniqueId();
        String loc=key(e.getBlock().getLocation());
        boolean placed=playerPlaced.remove(loc);
        if(placed) return;
        resetIfNeeded(id);
        addProgress(p,"daily","mine",1,64,1500,"daily-mine");
        addProgress(p,"weekly","mine",1,500,4000,"weekly-mine");
        Material m=e.getBlock().getType();
        if(Set.of(Material.WHEAT,Material.CARROTS,Material.POTATOES,Material.BEETROOTS,Material.NETHER_WART,Material.SWEET_BERRY_BUSH,Material.MELON,Material.PUMPKIN).contains(m)) addProgress(p,"daily","farm",1,32,1200,"daily-farm");
    }
    @EventHandler public void onPlace(org.bukkit.event.block.BlockPlaceEvent e) { playerPlaced.add(key(e.getBlockPlaced().getLocation())); }
    @EventHandler public void onMobKill(EntityDeathEvent e) {
        Player p=e.getEntity().getKiller();
        if(p==null||!(e.getEntity() instanceof Monster)) return;
        resetIfNeeded(p.getUniqueId());
        addProgress(p,"daily","mobs",1,10,1000,"daily-mobs");
    }
    @EventHandler public void onPlayerKill(PlayerDeathEvent e) {
        Player killer=e.getEntity().getKiller(); if(killer==null||killer.equals(e.getEntity())) return;
        resetIfNeeded(killer.getUniqueId());
        addProgress(killer,"weekly","pvp",1,5,5000,"weekly-pvp");
    }
    @EventHandler public void onJoin(PlayerJoinEvent e) { resetIfNeeded(e.getPlayer().getUniqueId()); }

    private void resetIfNeeded(UUID id) {
        String day=LocalDate.now().toString();
        LocalDate now=LocalDate.now();
        String week=now.get(WeekFields.ISO.weekBasedYear())+"-W"+String.format(Locale.ROOT,"%02d",now.get(WeekFields.ISO.weekOfWeekBasedYear()));
        String base=id.toString();
        if(!day.equals(data.getString(base+".daily.date",""))) {
            data.set(base+".daily.date",day); data.set(base+".daily.progress",null); data.set(base+".daily.claimed",null);
        }
        if(!week.equals(data.getString(base+".weekly.date",""))) {
            data.set(base+".weekly.date",week); data.set(base+".weekly.progress",null); data.set(base+".weekly.claimed",null);
        }
        save();
    }

    private void addProgress(Player p,String period,String quest,int amount,int goal,double reward,String display) {
        UUID id=p.getUniqueId(); String base=id+"."+period;
        String progressPath=base+".progress."+quest;
        if(data.getBoolean(base+".claimed."+quest,false)) return;
        int next=Math.min(goal,data.getInt(progressPath,0)+amount);
        data.set(progressPath,next);
        if(next>=goal) {
            data.set(base+".claimed."+quest,true);
            if(kelvoras.deposit(p,reward)) p.sendMessage(ChatColor.GREEN+"Quest complete: "+display.replace('-',' ')+"! Reward: ✦"+KelvorasManager.format(reward)+" Kelvoras.");
            else { data.set(base+".claimed."+quest,false); p.sendMessage(ChatColor.RED+"Quest completed but the reward could not be saved. Please contact staff."); }
        }
        save();
    }

    private String key(org.bukkit.Location l) { return l.getWorld().getName()+":"+l.getBlockX()+":"+l.getBlockY()+":"+l.getBlockZ(); }
    private void save() { try { if(!plugin.getDataFolder().exists()) plugin.getDataFolder().mkdirs(); data.save(file); } catch(IOException ex) { plugin.getLogger().warning("Could not save quests: "+ex.getMessage()); } }

    static synchronized void logTransfer(JavaPlugin plugin,String currency,String from,String to,double amount) {
        try {
            File dir=plugin.getDataFolder(); if(!dir.exists()) dir.mkdirs();
            java.nio.file.Files.writeString(new File(dir,"economy-audit.log").toPath(),
                java.time.Instant.now()+" | "+currency+" | "+from+" -> "+to+" | "+String.format(Locale.US,"%.2f",amount)+System.lineSeparator(),
                java.nio.file.StandardOpenOption.CREATE,java.nio.file.StandardOpenOption.APPEND);
        } catch(IOException ex) { plugin.getLogger().warning("Could not write economy audit log: "+ex.getMessage()); }
        if(amount>=100000.0) for(Player staff:Bukkit.getOnlinePlayers()) {
            if(staff.isOp()||staff.hasPermission("kelvora.economy.audit")) staff.sendMessage(ChatColor.GOLD+"[Economy Alert] Large "+currency+" transfer: "+from+" -> "+to+" ("+String.format(Locale.US,"%.2f",amount)+").");
        }
    }
}
