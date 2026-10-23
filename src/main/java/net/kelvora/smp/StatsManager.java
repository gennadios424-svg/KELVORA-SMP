package net.kelvora.smp;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.*;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.*;
import org.bukkit.command.*;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
final class StatsManager implements CommandExecutor, TabCompleter {
 private final KelvorasManager kelvoras; private final DecimalFormat fmt=new DecimalFormat("#,##0.##",DecimalFormatSymbols.getInstance(Locale.US));
 StatsManager(JavaPlugin plugin,KelvorasManager k){kelvoras=k;}
 void register(JavaPlugin plugin){if(plugin.getCommand("stats")!=null){plugin.getCommand("stats").setExecutor(this);plugin.getCommand("stats").setTabCompleter(this);}}
 public boolean onCommand(CommandSender s,Command c,String label,String[] a){
  if(a.length>1){s.sendMessage("Usage: /stats [player]");return true;}
  OfflinePlayer p;if(a.length==0){if(!(s instanceof Player)){s.sendMessage("Usage: /stats <player>");return true;}p=(Player)s;}else{p=Bukkit.getOfflinePlayer(a[0]);if(!p.isOnline()&&!p.hasPlayedBefore()){s.sendMessage(Component.text("No saved data found for that player.",NamedTextColor.RED));return true;}}
  String n=p.getName()==null?"Unknown":p.getName();long m=stat(p,Statistic.PLAY_ONE_MINUTE)/1200;
  s.sendMessage(Component.text("━━━━━━━━ KELVORA | "+n+" STATS ━━━━━━━━",NamedTextColor.GREEN));
  line(s,"Rank",rank(p),NamedTextColor.LIGHT_PURPLE);line(s,"Kills",fmt.format(stat(p,Statistic.PLAYER_KILLS)),NamedTextColor.RED);line(s,"Deaths",fmt.format(stat(p,Statistic.DEATHS)),NamedTextColor.DARK_RED);
  line(s,"Playtime",(m/1440)+"d "+((m%1440)/60)+"h "+(m%60)+"m",NamedTextColor.AQUA);
  double money=money(p);line(s,"Money",Double.isFinite(money)?"$"+fmt.format(money):"Unavailable",NamedTextColor.GOLD);line(s,"Kelvoras","✦"+fmt.format(kelvoras.balance(p)),NamedTextColor.GREEN);
  line(s,"Blocks mined",fmt.format(blocks(p)),NamedTextColor.YELLOW);line(s,"Mob kills",fmt.format(mobs(p)),NamedTextColor.GRAY);return true;
 }
 private void line(CommandSender s,String k,String v,NamedTextColor c){s.sendMessage(Component.text("  "+k+": ",NamedTextColor.GRAY).append(Component.text(v,c)));}
 private int stat(OfflinePlayer p,Statistic st){try{return p.getStatistic(st);}catch(Exception e){return 0;}}
 private long blocks(OfflinePlayer p){long n=0;for(Material m:Material.values())if(m.isBlock())try{n+=p.getStatistic(Statistic.MINE_BLOCK,m);}catch(Exception ignored){}return n;}
 private long mobs(OfflinePlayer p){long n=0;for(EntityType t:EntityType.values())try{n+=p.getStatistic(Statistic.KILL_ENTITY,t);}catch(Exception ignored){}return n;}
 private double money(OfflinePlayer p){try{Class<?> t=Class.forName("net.milkbowl.vault.economy.Economy");var r=Bukkit.getServicesManager().getRegistration((Class)t);if(r==null)return Double.NaN;Object v=r.getProvider().getClass().getMethod("getBalance",OfflinePlayer.class).invoke(r.getProvider(),p);return v instanceof Number x?x.doubleValue():Double.NaN;}catch(Exception e){return Double.NaN;}}
 private String rank(OfflinePlayer p){try{Class<?> cl=Class.forName("net.luckperms.api.LuckPermsProvider");Object lp=cl.getMethod("get").invoke(null),um=lp.getClass().getMethod("getUserManager").invoke(lp),u=um.getClass().getMethod("getUser",UUID.class).invoke(um,p.getUniqueId());if(u!=null){String g=String.valueOf(u.getClass().getMethod("getPrimaryGroup").invoke(u));return g.substring(0,1).toUpperCase(Locale.ROOT)+g.substring(1);}}catch(Exception ignored){}return "Sprout";}
 public List<String> onTabComplete(CommandSender s,Command c,String a,String[] args){if(args.length==1)return Bukkit.getOnlinePlayers().stream().map(Player::getName).filter(n->n.toLowerCase(Locale.ROOT).startsWith(args[0].toLowerCase(Locale.ROOT))).toList();return List.of();}
}