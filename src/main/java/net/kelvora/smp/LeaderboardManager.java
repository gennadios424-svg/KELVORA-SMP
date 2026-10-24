package net.kelvora.smp;

import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.*;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.*;
import org.bukkit.command.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

final class LeaderboardManager implements Listener, CommandExecutor, TabCompleter {
    private static final String TITLE = "KELVORA | LEADERBOARDS";
    private static final List<String> TYPES = List.of("kills", "deaths", "playtime", "money", "kelvoras", "rank");
    private static final DecimalFormat EXACT = new DecimalFormat("#,##0.##", DecimalFormatSymbols.getInstance(Locale.US));
    private final JavaPlugin plugin;
    private final KelvorasManager kelvoras;
    private final Map<UUID, String> selected = new HashMap<>();
    private final Map<UUID, Integer> pages = new HashMap<>();

    LeaderboardManager(JavaPlugin plugin, KelvorasManager kelvoras) { this.plugin = plugin; this.kelvoras = kelvoras; }

    void register() {
        Bukkit.getPluginManager().registerEvents(this, plugin);
        if (plugin.getCommand("leaderboard") != null) {
            plugin.getCommand("leaderboard").setExecutor(this);
            plugin.getCommand("leaderboard").setTabCompleter(this);
        }
    }

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player p)) { sender.sendMessage("Use this command in-game."); return true; }
        String type = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        if (!type.isEmpty() && !TYPES.contains(type)) { p.sendMessage(Component.text("Categories: " + String.join(", ", TYPES), NamedTextColor.YELLOW)); return true; }
        int page = 0;
        if (args.length > 1) try { page = Math.max(0, Integer.parseInt(args[1]) - 1); } catch (NumberFormatException ex) { p.sendMessage(Component.text("Page must be a positive number.", NamedTextColor.RED)); return true; }
        if (type.isEmpty()) openMenu(p); else openBoard(p, type, page);
        return true;
    }

    private void openMenu(Player p) {
        Inventory inv = Bukkit.createInventory(null, 36, Component.text("KELVORA  |  LEADERBOARDS", NamedTextColor.DARK_GREEN));
        ItemStack frame = new ItemStack(Material.GREEN_STAINED_GLASS_PANE);
        ItemMeta frameMeta = frame.getItemMeta(); frameMeta.displayName(Component.text(" ")); frame.setItemMeta(frameMeta);
        for (int slot : new int[]{0,1,2,3,4,5,6,7,8,27,28,29,30,31,32,33,34,35}) inv.setItem(slot, frame.clone());
        Material[] icons = {Material.IRON_SWORD, Material.SKELETON_SKULL, Material.CLOCK, Material.GOLD_INGOT, Material.AMETHYST_SHARD, Material.NETHER_STAR};
        int[] slots = {10, 12, 14, 19, 21, 23};
        for (int i=0;i<TYPES.size();i++) {
            String type=TYPES.get(i);
            ItemStack item=new ItemStack(icons[i]); ItemMeta meta=item.getItemMeta();
            meta.displayName(Component.text(label(type), color(type)));
            meta.lore(List.of(Component.text("Click to view the rankings", NamedTextColor.GRAY)));
            item.setItemMeta(meta); inv.setItem(slots[i],item);
        }
        p.openInventory(inv);
    }

    private void openBoard(Player p, String type, int page) {
        List<Entry> entries = entries(type);
        int maxPage = Math.max(0, (entries.size()-1)/10);
        page = Math.min(page, maxPage);
        selected.put(p.getUniqueId(), type); pages.put(p.getUniqueId(), page);
        Inventory inv=Bukkit.createInventory(null,54,Component.text(TITLE+" | "+label(type),NamedTextColor.DARK_GREEN));
        for(int i=0;i<10;i++) {
            int index=page*10+i;
            if(index>=entries.size()) break;
            Entry entry=entries.get(index);
            ItemStack item=new ItemStack(index==0?Material.GOLD_INGOT:index==1?Material.IRON_INGOT:index==2?Material.COPPER_INGOT:Material.PAPER);
            ItemMeta meta=item.getItemMeta();
            meta.displayName(Component.text("#"+(index+1)+" "+entry.name, index==0?NamedTextColor.GOLD:index==1?NamedTextColor.WHITE:index==2?NamedTextColor.RED:NamedTextColor.AQUA));
            meta.lore(List.of(Component.text(entry.display,NamedTextColor.GREEN),Component.text("Position "+(index+1),NamedTextColor.GRAY)));
            item.setItemMeta(meta); inv.setItem(10+i,item);
        }
        ItemStack back=new ItemStack(Material.ARROW); ItemMeta bm=back.getItemMeta(); bm.displayName(Component.text("Categories",NamedTextColor.YELLOW)); back.setItemMeta(bm); inv.setItem(45,back);
        ItemStack pageItem=new ItemStack(Material.PAPER); ItemMeta pm=pageItem.getItemMeta(); pm.displayName(Component.text("Page "+(page+1)+" / "+(maxPage+1),NamedTextColor.AQUA)); pageItem.setItemMeta(pm); inv.setItem(49,pageItem);
        if(page>0){ItemStack prev=new ItemStack(Material.SPECTRAL_ARROW);ItemMeta m=prev.getItemMeta();m.displayName(Component.text("Previous page",NamedTextColor.YELLOW));prev.setItemMeta(m);inv.setItem(48,prev);}
        if(page<maxPage){ItemStack next=new ItemStack(Material.SPECTRAL_ARROW);ItemMeta m=next.getItemMeta();m.displayName(Component.text("Next page",NamedTextColor.YELLOW));next.setItemMeta(m);inv.setItem(50,next);}
        p.openInventory(inv);
    }

    private List<Entry> entries(String type) {
        List<Entry> out=new ArrayList<>();
        for(OfflinePlayer p:Bukkit.getOfflinePlayers()) {
            String name=p.getName(); if(name==null||name.isBlank()) continue;
            double value;
            String shown;
            switch(type) {
                case "kills" -> { value=p.getStatistic(Statistic.PLAYER_KILLS); shown=EXACT.format(value); }
                case "deaths" -> { value=p.getStatistic(Statistic.DEATHS); shown=EXACT.format(value); }
                case "playtime" -> { value=p.getStatistic(Statistic.PLAY_ONE_MINUTE)/20.0; long mins=(long)(value/60); shown=(mins/60)+"h "+(mins%60)+"m"; }
                case "kelvoras" -> { value=kelvoras.balance(p); shown="✦"+EXACT.format(value)+" Kelvoras"; }
                case "money" -> { value=money(p); shown=Double.isFinite(value)?"$"+EXACT.format(value):"Money unavailable"; }
                case "rank" -> { String rank=rank(p); value=rankScore(rank); shown=rank; }
                default -> { value=0; shown="0"; }
            }
            if(!Double.isFinite(value)) value=0;
            out.add(new Entry(p.getUniqueId(),name,value,shown));
        }
        out.sort(Comparator.comparingDouble(Entry::value).reversed().thenComparing(Entry::name,String.CASE_INSENSITIVE_ORDER).thenComparing(e->e.id.toString()));
        return out;
    }

    private double money(OfflinePlayer p) {
        try {
            Class<?> type=Class.forName("net.milkbowl.vault.economy.Economy");
            var reg=Bukkit.getServicesManager().getRegistration((Class)type);
            if(reg==null)return Double.NaN;
            Object amount=reg.getProvider().getClass().getMethod("getBalance",OfflinePlayer.class).invoke(reg.getProvider(),p);
            return ((Number)amount).doubleValue();
        } catch(Exception ex) { return Double.NaN; }
    }

    private String rank(OfflinePlayer p) {
        try {
            Class<?> provider=Class.forName("net.luckperms.api.LuckPermsProvider");
            Object lp=provider.getMethod("get").invoke(null);
            Object users=lp.getClass().getMethod("getUserManager").invoke(lp);
            Object user=users.getClass().getMethod("getUser",UUID.class).invoke(users,p.getUniqueId());
            if(user!=null) {
                String primary=String.valueOf(user.getClass().getMethod("getPrimaryGroup").invoke(user)).toLowerCase(Locale.ROOT);
                List<String> ranks=List.of("sprout","verdant","emerald","sovereign","ascendant");
                String best=ranks.contains(primary)?primary:"sprout";
                Object nodes=user.getClass().getMethod("getNodes").invoke(user);
                if(nodes instanceof Iterable<?> it) for(Object node:it) {
                    if(!node.getClass().getName().contains("InheritanceNode"))continue;
                    String group=String.valueOf(node.getClass().getMethod("getGroupName").invoke(node)).toLowerCase(Locale.ROOT);
                    if(ranks.contains(group)&&ranks.indexOf(group)>ranks.indexOf(best))best=group;
                }
                return best.substring(0,1).toUpperCase(Locale.ROOT)+best.substring(1);
            }
        } catch(Exception ignored) {}
        return "Sprout";
    }
    private double rankScore(String rank) { return switch(rank.toLowerCase(Locale.ROOT)){case "ascendant"->5;case "sovereign"->4;case "emerald"->3;case "verdant"->2;default->1;};}
    private String label(String type){return switch(type){case "kills"->"⚔ Kills";case "deaths"->"☠ Deaths";case "playtime"->"⏱ Playtime";case "money"->"💰 Money";case "kelvoras"->"💎 Kelvoras";default->"⭐ Rank";};}
    private NamedTextColor color(String type){return switch(type){case "kills"->NamedTextColor.RED;case "deaths"->NamedTextColor.DARK_RED;case "playtime"->NamedTextColor.AQUA;case "money"->NamedTextColor.GOLD;case "kelvoras"->NamedTextColor.LIGHT_PURPLE;default->NamedTextColor.GREEN;};}

    @EventHandler public void click(InventoryClickEvent e) {
        if(e.getView().getTitle()==null||!e.getView().getTitle().startsWith(TITLE))return;
        e.setCancelled(true);
        if(!(e.getWhoClicked() instanceof Player p)||e.getClickedInventory()!=e.getView().getTopInventory())return;
        String title=e.getView().getTitle();
        if(title.equals(TITLE)) {
            int slot=e.getRawSlot()-10;
            if(slot>=0&&slot<TYPES.size())openBoard(p,TYPES.get(slot),0);
            return;
        }
        String type=selected.get(p.getUniqueId()); if(type==null)return;
        int page=pages.getOrDefault(p.getUniqueId(),0);
        if(e.getRawSlot()==45)openMenu(p);
        else if(e.getRawSlot()==48&&page>0)openBoard(p,type,page-1);
        else if(e.getRawSlot()==50)openBoard(p,type,page+1);
    }
    @EventHandler public void drag(InventoryDragEvent e){if(e.getView().getTitle()!=null&&e.getView().getTitle().startsWith(TITLE))e.setCancelled(true);}
    @Override public List<String> onTabComplete(CommandSender s,Command c,String a,String[] args){if(args.length==1)return TYPES; if(args.length==2)return List.of("1","2","3");return List.of();}
    private record Entry(UUID id,String name,double value,String display) {}
}
