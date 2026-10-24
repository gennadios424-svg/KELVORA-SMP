package net.kelvora.smp;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.*;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

/** Safe, one-use player teleport requests. */
final class TpaManager implements Listener {
    private final JavaPlugin plugin;
    private final BountyCombatManager combat;
    private final Map<UUID, Request> byTarget = new ConcurrentHashMap<>();
    private final Map<UUID, UUID> bySender = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastRequest = new ConcurrentHashMap<>();

    TpaManager(JavaPlugin plugin, BountyCombatManager combat) { this.plugin = plugin; this.combat = combat; }

    void register() {
        Bukkit.getPluginManager().registerEvents(this, plugin);
        bind("tpa", this::tpa, "kelvora.tpa");
        bind("tpahere", this::tpahere, "kelvora.tpa");
        bind("tpaccept", this::accept, "kelvora.tpa");
        bind("tpdeny", this::deny, "kelvora.tpa");
        bind("tpacancel", this::cancel, "kelvora.tpa");
    }

    private interface Handler { boolean run(Player p, String[] args); }
    private void bind(String name, Handler handler, String permission) {
        PluginCommand cmd = plugin.getCommand(name);
        if (cmd == null) { plugin.getLogger().warning("Missing command registration: " + name); return; }
        cmd.setExecutor((sender, command, label, args) -> {
            if (!(sender instanceof Player p)) { sender.sendMessage("Use this command in-game."); return true; }
            if (!p.hasPermission(permission)) { p.sendMessage(Component.text("You do not have permission.", NamedTextColor.RED)); return true; }
            return handler.run(p, args);
        });
    }

    private boolean tpa(Player p, String[] args) { return request(p, args, false); }
    private boolean tpahere(Player p, String[] args) { return request(p, args, true); }

    private boolean request(Player sender, String[] args, boolean here) {
        if (args.length != 1) {
            sender.sendMessage(Component.text(here ? "Usage: /tpahere <player> — ask them to teleport to you." : "Usage: /tpa <player> — ask to teleport to them.", NamedTextColor.YELLOW));
            return true;
        }
        Player target = Bukkit.getPlayerExact(args[0]);
        if (target == null) { fail(sender, "That player is not online."); return true; }
        if (target.equals(sender)) { fail(sender, "You cannot send a teleport request to yourself."); return true; }
        if (combat != null && (combat.isInCombat(sender) || combat.isInCombat(target))) { fail(sender, "Teleport requests are unavailable while either player is in combat."); return true; }
        long now = System.currentTimeMillis();
        long cooldown = Math.max(0, plugin.getConfig().getLong("tpa.cooldown-seconds", 10));
        long previous = lastRequest.getOrDefault(sender.getUniqueId(), 0L);
        if (now - previous < cooldown * 1000L) { fail(sender, "Wait " + Math.max(1, (cooldown * 1000L - (now - previous) + 999) / 1000) + "s before sending another request."); return true; }
        UUID prior = bySender.get(sender.getUniqueId());
        if (prior != null) { fail(sender, "You already have a pending teleport request. Use /tpacancel first."); return true; }
        if (byTarget.containsKey(target.getUniqueId())) { fail(sender, "That player already has a pending teleport request."); return true; }

        Request request = new Request(UUID.randomUUID(), sender.getUniqueId(), target.getUniqueId(), sender.getName(), target.getName(), here, now);
        byTarget.put(target.getUniqueId(), request);
        bySender.put(sender.getUniqueId(), request.id);
        lastRequest.put(sender.getUniqueId(), now);
        long timeout = Math.max(1, plugin.getConfig().getLong("tpa.expiry-seconds", 60));
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (consume(request)) {
                Player s = Bukkit.getPlayer(request.sender);
                Player t = Bukkit.getPlayer(request.target);
                if (s != null) fail(s, "Your teleport request expired.");
                if (t != null) fail(t, "The teleport request from " + request.senderName + " expired.");
            }
        }, timeout * 20L);

        sender.sendMessage(Component.text(here
            ? "Teleport-here request sent to " + target.getName() + ". They must accept before they teleport to you. Use /tpacancel to cancel."
            : "Teleport request sent to " + target.getName() + ". They must accept before you teleport to them. Use /tpacancel to cancel.", NamedTextColor.GREEN));
        target.sendMessage(Component.text(here
            ? request.senderName + " wants you to teleport to them. "
            : request.senderName + " wants to teleport to you. ", NamedTextColor.AQUA)
            .append(Component.text("Use ", NamedTextColor.GRAY))
            .append(Component.text("/tpaccept", NamedTextColor.GREEN).clickEvent(ClickEvent.runCommand("/tpaccept")))
            .append(Component.text(" to accept or ", NamedTextColor.GRAY))
            .append(Component.text("/tpdeny", NamedTextColor.RED).clickEvent(ClickEvent.runCommand("/tpdeny")))
            .append(Component.text(". Expires in " + timeout + " seconds.", NamedTextColor.GRAY)));
        sender.playSound(sender.getLocation(), Sound.UI_BUTTON_CLICK, .25f, 1.1f);
        target.playSound(target.getLocation(), Sound.UI_BUTTON_CLICK, .25f, 1.1f);
        return true;
    }

    private boolean accept(Player target, String[] args) {
        if (args.length != 0) { target.sendMessage(Component.text("Usage: /tpaccept", NamedTextColor.YELLOW)); return true; }
        Request r = byTarget.get(target.getUniqueId());
        if (r == null) { fail(target, "You have no pending teleport request."); return true; }
        if (!consume(r)) { fail(target, "That request is no longer available."); return true; }
        Player sender = Bukkit.getPlayer(r.sender);
        if (sender == null || !sender.isOnline()) { fail(target, "The requester is no longer online."); return true; }
        Player traveler = r.here ? target : sender;
        Player destinationPlayer = r.here ? sender : target;
        if (combat != null && (combat.isInCombat(traveler) || combat.isInCombat(destinationPlayer))) {
            fail(target, "Teleport cancelled because a player is in combat.");
            fail(sender, "Teleport cancelled because a player is in combat.");
            return true;
        }
        Location safe = safeLocation(destinationPlayer);
        if (safe == null) { fail(target, "The destination is unsafe. Request another teleport when the destination is safe."); return true; }
        int seconds = Math.max(0, Math.min(30, plugin.getConfig().getInt("tpa.countdown-seconds", 5)));
        Location start = traveler.getLocation().clone();
        traveler.sendMessage(Component.text("Teleporting in " + seconds + " seconds. Do not move or enter combat.", NamedTextColor.GREEN));
        target.sendMessage(Component.text("Teleport request accepted.", NamedTextColor.GREEN));
        traveler.playSound(traveler.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, .4f, 1.2f);
        target.playSound(target.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, .3f, 1.15f);
        Bukkit.getScheduler().runTaskTimer(plugin, new Runnable() {
            int remaining = seconds;
            BukkitTask task;
            @Override public void run() {
                if (task == null) task = Bukkit.getScheduler().runTaskLater(plugin, () -> {}, 1L);
                if (!traveler.isOnline() || !destinationPlayer.isOnline()) { task.cancel(); return; }
                if (traveler.getLocation().getBlockX() != start.getBlockX() || traveler.getLocation().getBlockY() != start.getBlockY() || traveler.getLocation().getBlockZ() != start.getBlockZ()) {
                    task.cancel(); fail(traveler, "Teleport cancelled because you moved."); return;
                }
                if (combat != null && (combat.isInCombat(traveler) || combat.isInCombat(destinationPlayer))) {
                    task.cancel(); fail(traveler, "Teleport cancelled because combat started."); return;
                }
                if (remaining <= 0) {
                    Location finalDestination = safeLocation(destinationPlayer);
                    task.cancel();
                    if (finalDestination == null) { fail(traveler, "Teleport cancelled: destination is no longer safe."); return; }
                    traveler.teleportAsync(finalDestination).thenAccept(ok -> Bukkit.getScheduler().runTask(plugin, () -> {
                        if (ok) { traveler.playSound(traveler.getLocation(), Sound.ENTITY_ENDERMAN_TELEPORT, .45f, 1.1f); traveler.sendMessage(Component.text("Teleport complete.", NamedTextColor.GREEN)); }
                        else fail(traveler, "Teleport failed.");
                    }));
                    return;
                }
                if (remaining > 0) traveler.sendMessage(Component.text("Teleporting in " + remaining + "...", NamedTextColor.YELLOW));
                remaining--;
            }
        }, 0L, 20L);
        return true;
    }

    private Location safeLocation(Player player) {
        Location at = player.getLocation();
        World world = at.getWorld();
        if (world == null) return null;
        int x = at.getBlockX(), z = at.getBlockZ();
        int y = world.getHighestBlockYAt(x, z) + 1;
        if (y <= world.getMinHeight() || y + 1 >= world.getMaxHeight()) return null;
        Location feet = new Location(world, x + .5, y, z + .5, at.getYaw(), at.getPitch());
        Material ground = world.getBlockAt(x, y - 1, z).getType();
        if (!ground.isSolid() || ground == Material.LAVA || ground == Material.MAGMA_BLOCK || ground == Material.CAMPFIRE || ground == Material.SOUL_CAMPFIRE) return null;
        if (!world.getBlockAt(x, y, z).isPassable() || !world.getBlockAt(x, y + 1, z).isPassable()) return null;
        return feet;
    }

    private boolean deny(Player target, String[] args) {
        if (args.length != 0) { target.sendMessage(Component.text("Usage: /tpdeny", NamedTextColor.YELLOW)); return true; }
        Request r = byTarget.get(target.getUniqueId());
        if (r == null || !consume(r)) { fail(target, "You have no pending teleport request."); return true; }
        Player sender = Bukkit.getPlayer(r.sender);
        target.sendMessage(Component.text("Teleport request denied.", NamedTextColor.RED));
        if (sender != null) fail(sender, target.getName() + " denied your teleport request.");
        return true;
    }

    private boolean cancel(Player sender, String[] args) {
        if (args.length != 0) { sender.sendMessage(Component.text("Usage: /tpacancel", NamedTextColor.YELLOW)); return true; }
        UUID id = bySender.get(sender.getUniqueId());
        if (id == null) { fail(sender, "You have no pending request to cancel."); return true; }
        Request r = byTarget.get(sender.getUniqueId());
        if (r == null || !r.id.equals(id)) r = byTarget.values().stream().filter(q -> q.id.equals(id)).findFirst().orElse(null);
        if (r == null || !consume(r)) { bySender.remove(sender.getUniqueId()); fail(sender, "That request is no longer available."); return true; }
        Player target = Bukkit.getPlayer(r.target);
        sender.sendMessage(Component.text("Teleport request cancelled.", NamedTextColor.YELLOW));
        if (target != null) fail(target, sender.getName() + " cancelled the teleport request.");
        return true;
    }

    private boolean consume(Request r) {
        if (!byTarget.remove(r.target, r)) return false;
        bySender.remove(r.sender, r.id);
        return true;
    }

    @EventHandler public void onQuit(PlayerQuitEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        Request received = byTarget.get(id);
        if (received != null && consume(received)) {
            Player other = Bukkit.getPlayer(received.sender);
            if (other != null) fail(other, "Teleport request cancelled because " + e.getPlayer().getName() + " disconnected.");
        }
        UUID sent = bySender.get(id);
        if (sent != null) {
            Request request = byTarget.values().stream().filter(r -> r.id.equals(sent)).findFirst().orElse(null);
            if (request != null && consume(request)) {
                Player other = Bukkit.getPlayer(request.target);
                if (other != null) fail(other, "Teleport request cancelled because " + e.getPlayer().getName() + " disconnected.");
            }
        }
    }

    private void fail(Player p, String message) { p.sendMessage(Component.text(message, NamedTextColor.RED)); p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, .3f, .8f); }
    private record Request(UUID id, UUID sender, UUID target, String senderName, String targetName, boolean here, long createdAt) {}
}
