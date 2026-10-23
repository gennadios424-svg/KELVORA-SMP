package net.kelvora.smp;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.*;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

/** Random Teleport Queue 1v1 matchmaking. Arena spawns must be configured explicitly. */
final class RtqManager implements Listener, CommandExecutor, TabCompleter {
    private final JavaPlugin plugin;
    private final BountyCombatManager combat;
    private final LinkedHashSet<UUID> queue = new LinkedHashSet<>();
    private final Map<UUID, UUID> opponents = new ConcurrentHashMap<>();
    private final Set<UUID> active = ConcurrentHashMap.newKeySet();
    private final Set<UUID> fighting = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Location> returnLocations = new HashMap<>();
    private boolean matching;

    RtqManager(JavaPlugin plugin, BountyCombatManager combat) {
        this.plugin = plugin;
        this.combat = combat;
        plugin.getConfig().addDefault("rtq.enabled", true);
        plugin.getConfig().addDefault("rtq.arena.world", "world");
        plugin.getConfig().addDefault("rtq.arena.spawn1.x", 0.5);
        plugin.getConfig().addDefault("rtq.arena.spawn1.y", 100.0);
        plugin.getConfig().addDefault("rtq.arena.spawn1.z", 0.5);
        plugin.getConfig().addDefault("rtq.arena.spawn1.yaw", 0.0);
        plugin.getConfig().addDefault("rtq.arena.spawn1.pitch", 0.0);
        plugin.getConfig().addDefault("rtq.arena.spawn2.x", 10.5);
        plugin.getConfig().addDefault("rtq.arena.spawn2.y", 100.0);
        plugin.getConfig().addDefault("rtq.arena.spawn2.z", 0.5);
        plugin.getConfig().addDefault("rtq.arena.spawn2.yaw", 180.0);
        plugin.getConfig().addDefault("rtq.arena.spawn2.pitch", 0.0);
        plugin.getConfig().addDefault("rtq.countdown-seconds", 5);
        plugin.getConfig().addDefault("rtq.announce-queue", true);
        plugin.getConfig().addDefault("rtq.disconnect-winner", true);
        plugin.getConfig().options().copyDefaults(true);
        plugin.saveConfig();
    }

    void register() {
        Bukkit.getPluginManager().registerEvents(this, plugin);
        if (plugin.getCommand("rtq") != null) {
            plugin.getCommand("rtq").setExecutor(this);
            plugin.getCommand("rtq").setTabCompleter(this);
        }
        plugin.getLogger().info("RTQ registered. Configure rtq.arena.world and both spawn coordinates before matchmaking.");
    }

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length > 0 && args[0].equalsIgnoreCase("admin")) {
            if (!sender.isOp()) { sender.sendMessage("Only server operators can use RTQ admin commands."); return true; }
            if (args.length == 2 && args[1].equalsIgnoreCase("reload")) {
                plugin.reloadConfig();
                sender.sendMessage(Component.text("RTQ configuration reloaded. Arena spawn safety is checked when matching.", NamedTextColor.GREEN));
            } else sender.sendMessage("Usage: /rtq admin reload");
            return true;
        }
        if (!(sender instanceof Player p)) { sender.sendMessage("Use this command in-game."); return true; }
        if (args.length == 1 && args[0].equalsIgnoreCase("status")) {
            if (active.contains(p.getUniqueId())) p.sendMessage(Component.text("You are currently in an RTQ duel.", NamedTextColor.RED));
            else if (queue.contains(p.getUniqueId())) p.sendMessage(Component.text("You are waiting in the RTQ queue. Position: " + position(p.getUniqueId()), NamedTextColor.YELLOW));
            else p.sendMessage(Component.text("You are not queued for RTQ.", NamedTextColor.GRAY));
            return true;
        }
        if (args.length > 0 && args[0].equalsIgnoreCase("leave")) {
            if (active.contains(p.getUniqueId())) { p.sendMessage(Component.text("You cannot leave an active RTQ duel.", NamedTextColor.RED)); return true; }
            if (queue.remove(p.getUniqueId())) p.sendMessage(Component.text("You left the RTQ queue.", NamedTextColor.YELLOW));
            else p.sendMessage(Component.text("You are not in the RTQ queue.", NamedTextColor.GRAY));
            return true;
        }
        if (args.length != 0) { p.sendMessage(Component.text("Usage: /rtq [leave|status|admin reload]", NamedTextColor.YELLOW)); return true; }
        if (!plugin.getConfig().getBoolean("rtq.enabled", true)) { p.sendMessage(Component.text("RTQ is currently disabled.", NamedTextColor.RED)); return true; }
        if (active.contains(p.getUniqueId()) || opponents.containsKey(p.getUniqueId())) { p.sendMessage(Component.text("You are already in an RTQ match.", NamedTextColor.RED)); return true; }
        if (combat.isInCombat(p)) { p.sendMessage(Component.text("You cannot join RTQ while combat-tagged.", NamedTextColor.RED)); return true; }
        if (!queue.add(p.getUniqueId())) { queue.remove(p.getUniqueId()); p.sendMessage(Component.text("You left the RTQ queue.", NamedTextColor.YELLOW)); return true; }
        p.sendMessage(Component.text("You joined the RTQ queue. Waiting for an opponent…", NamedTextColor.GREEN));
        if (plugin.getConfig().getBoolean("rtq.announce-queue", true)) Bukkit.broadcast(Component.text("⚔ " + p.getName() + " is waiting for an RTQ duel!", NamedTextColor.YELLOW));
        matchNext();
        return true;
    }

    private int position(UUID id) { int i=1; for (UUID queued:queue) { if (queued.equals(id)) return i; i++; } return -1; }

    private Location spawn(int n) {
        String root = "rtq.arena.spawn" + n;
        World w = Bukkit.getWorld(plugin.getConfig().getString("rtq.arena.world", "world"));
        if (w == null) return null;
        Location l = new Location(w, plugin.getConfig().getDouble(root+".x"), plugin.getConfig().getDouble(root+".y"), plugin.getConfig().getDouble(root+".z"),
                (float)plugin.getConfig().getDouble(root+".yaw"), (float)plugin.getConfig().getDouble(root+".pitch"));
        return safe(l) ? l : null;
    }

    private boolean safe(Location l) {
        if (l == null || l.getWorld() == null || l.getY() < l.getWorld().getMinHeight() || l.getY()+1 >= l.getWorld().getMaxHeight()) return false;
        if (!l.getWorld().getWorldBorder().isInside(l)) return false;
        if (!l.getBlock().isPassable() || !l.clone().add(0,1,0).getBlock().isPassable()) return false;
        Material ground = l.clone().add(0,-1,0).getBlock().getType();
        return ground.isSolid() && !ground.name().contains("LAVA") && !ground.name().contains("WATER")
                && !l.getBlock().isLiquid() && !l.clone().add(0,1,0).getBlock().isLiquid();
    }

    private void matchNext() {
        if (matching) return;
        while (queue.size() >= 2) {
            List<UUID> waiting=new ArrayList<>(queue); UUID a=waiting.get(0), b=waiting.get(1);
            Player p1=Bukkit.getPlayer(a), p2=Bukkit.getPlayer(b);
            if (p1==null || !p1.isOnline() || active.contains(a) || combat.isInCombat(p1)) { queue.remove(a); continue; }
            if (p2==null || !p2.isOnline() || active.contains(b) || combat.isInCombat(p2)) { queue.remove(b); continue; }
            Location s1=spawn(1), s2=spawn(2);
            if (s1==null || s2==null || s1.getWorld()!=s2.getWorld() || s1.distanceSquared(s2)<16) {
                p1.sendMessage(Component.text("RTQ arena is not configured safely. Ask an operator to set both spawn points.", NamedTextColor.RED));
                p2.sendMessage(Component.text("RTQ arena is not configured safely. Ask an operator to set both spawn points.", NamedTextColor.RED));
                queue.remove(a); queue.remove(b); return;
            }
            queue.remove(a); queue.remove(b); matching=true;
            opponents.put(a,b); opponents.put(b,a); active.add(a); active.add(b);
            returnLocations.put(a,p1.getLocation().clone()); returnLocations.put(b,p2.getLocation().clone());
            p1.sendMessage(Component.text("Opponent found: "+p2.getName()+". Preparing duel…",NamedTextColor.GREEN));
            p2.sendMessage(Component.text("Opponent found: "+p1.getName()+". Preparing duel…",NamedTextColor.GREEN));
            boolean one=p1.teleport(s1, org.bukkit.event.player.PlayerTeleportEvent.TeleportCause.PLUGIN);
            boolean two=one && p2.teleport(s2, org.bukkit.event.player.PlayerTeleportEvent.TeleportCause.PLUGIN);
            if (!one || !two) { cancelMatch(a,b,"Teleport failed; duel cancelled."); matching=false; return; }
            p1.setFallDistance(0); p2.setFallDistance(0);
            Bukkit.getScheduler().runTask(plugin, () -> { matching=false; countdown(a,b,plugin.getConfig().getInt("rtq.countdown-seconds",5)); });
            return;
        }
    }

    private void countdown(UUID a, UUID b, int seconds) {
        int duration=Math.max(3,Math.min(15,seconds));
        for (int i=duration;i>=1;i--) {
            final int count=i;
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                Player p=Bukkit.getPlayer(a), q=Bukkit.getPlayer(b);
                if (!active.contains(a)||!active.contains(b)||p==null||q==null) { cancelMatch(a,b,"Duel cancelled because a player became unavailable."); return; }
                Component title=Component.text(Integer.toString(count),NamedTextColor.GOLD);
                p.showTitle(net.kyori.adventure.title.Title.title(title,Component.text("RTQ duel starts soon",NamedTextColor.YELLOW)));
                q.showTitle(net.kyori.adventure.title.Title.title(title,Component.text("RTQ duel starts soon",NamedTextColor.YELLOW)));
            }, (long)(duration-i)*20L);
        }
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            Player p=Bukkit.getPlayer(a), q=Bukkit.getPlayer(b);
            if (!active.contains(a)||!active.contains(b)||p==null||q==null) { cancelMatch(a,b,"Duel cancelled because a player became unavailable."); return; }
            p.showTitle(net.kyori.adventure.title.Title.title(Component.text("FIGHT!",NamedTextColor.RED),Component.text("Good luck",NamedTextColor.WHITE)));
            q.showTitle(net.kyori.adventure.title.Title.title(Component.text("FIGHT!",NamedTextColor.RED),Component.text("Good luck",NamedTextColor.WHITE)));
            fighting.add(a); fighting.add(b);
            p.sendMessage(Component.text("RTQ duel started against "+q.getName()+".",NamedTextColor.GREEN));
            q.sendMessage(Component.text("RTQ duel started against "+p.getName()+".",NamedTextColor.GREEN));
        }, (long)duration*20L);
    }

    private void cancelMatch(UUID a, UUID b, String reason) {
        Player p=Bukkit.getPlayer(a), q=Bukkit.getPlayer(b);
        Location pr=returnLocations.get(a), qr=returnLocations.get(b);
        if(p!=null) p.sendMessage(Component.text(reason,NamedTextColor.RED));
        if(q!=null) q.sendMessage(Component.text(reason,NamedTextColor.RED));
        finish(a); finish(b);
        if(p!=null && p.isOnline() && pr!=null) p.teleport(pr);
        if(q!=null && q.isOnline() && qr!=null) q.teleport(qr);
        matchNext();
    }
    private void finish(UUID id) {
        active.remove(id); fighting.remove(id); queue.remove(id);
        UUID other=opponents.remove(id); if(other!=null) opponents.remove(other,id);
        returnLocations.remove(id);
    }
    private void endFor(UUID loser, String reason) {
        UUID winner=opponents.get(loser); Player w=winner==null?null:Bukkit.getPlayer(winner);
        Player l=Bukkit.getPlayer(loser);
        if(w!=null) w.sendMessage(Component.text(reason+" You won the RTQ duel.",NamedTextColor.GREEN));
        if(l!=null) l.sendMessage(Component.text(reason+" Your RTQ duel has ended.",NamedTextColor.RED));
        Location lr=returnLocations.get(loser), wr=winner==null?null:returnLocations.get(winner);
        finish(loser); if(winner!=null) finish(winner);
        if(l!=null && lr!=null && l.isOnline()) l.teleport(lr);
        if(w!=null && wr!=null && w.isOnline()) w.teleport(wr);
        matchNext();
    }

    @EventHandler public void onDamage(EntityDamageByEntityEvent e) {
        if (!(e.getEntity() instanceof Player victim)) return;
        Player attacker=null;
        if (e.getDamager() instanceof Player p) attacker=p;
        else if (e.getDamager() instanceof org.bukkit.entity.Projectile projectile && projectile.getShooter() instanceof Player p) attacker=p;
        if ((active.contains(victim.getUniqueId()) && !fighting.contains(victim.getUniqueId())) || (attacker!=null && active.contains(attacker.getUniqueId()) && !fighting.contains(attacker.getUniqueId()))) e.setCancelled(true);
    }
    @EventHandler public void onDeath(PlayerDeathEvent e) {
        UUID id=e.getEntity().getUniqueId();
        if(active.contains(id)) Bukkit.getScheduler().runTask(plugin,()->endFor(id,"Duel finished."));
        else queue.remove(id);
    }
    @EventHandler public void onQuit(PlayerQuitEvent e) {
        UUID id=e.getPlayer().getUniqueId();
        queue.remove(id);
        if(active.contains(id)) {
            UUID other=opponents.get(id);
            if(plugin.getConfig().getBoolean("rtq.disconnect-winner",true) && other!=null) Bukkit.getScheduler().runTask(plugin,()->endFor(id,"Opponent disconnected."));
            else { finish(id); if(other!=null) finish(other); }
        }
    }
    @Override public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if(args.length==1) return List.of("leave","status","admin").stream().filter(s->s.startsWith(args[0].toLowerCase(Locale.ROOT))).toList();
        if(args.length==2 && args[0].equalsIgnoreCase("admin")) return List.of("reload");
        return List.of();
    }
}
