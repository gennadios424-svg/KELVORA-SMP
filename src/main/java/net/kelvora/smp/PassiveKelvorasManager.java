package net.kelvora.smp;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Pays only for online time, with an optional lightweight activity-based AFK policy. */
final class PassiveKelvorasManager implements Listener {
    private final JavaPlugin plugin;
    private final Map<UUID, Long> seconds = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastActivity = new ConcurrentHashMap<>();

    PassiveKelvorasManager(JavaPlugin plugin) { this.plugin = plugin; }

    void register() {
        // Migrate the old built-in five-minute default once; preserve any custom interval other than 300 seconds.
        if (plugin.getConfig().getLong("passive-kelvoras.interval-seconds", 1200L) == 300L) {
            plugin.getConfig().set("passive-kelvoras.interval-seconds", 1200L);
            plugin.saveConfig();
        }
        if (!plugin.getConfig().getBoolean("passive-kelvoras.enabled", true)) return;
        long interval = Math.max(1L, plugin.getConfig().getLong("passive-kelvoras.interval-seconds", 1200L));
        double amount = plugin.getConfig().getDouble("passive-kelvoras.amount", 1.0);
        boolean afkEligible = plugin.getConfig().getBoolean("passive-kelvoras.afk-eligible", true);
        long afkTimeout = Math.max(30L, plugin.getConfig().getLong("passive-kelvoras.afk-timeout-seconds", 60L));
        if (!Double.isFinite(amount) || amount <= 0) {
            plugin.getLogger().warning("Passive Kelvoras disabled: amount must be positive.");
            return;
        }
        long now = System.currentTimeMillis();
        for (Player p : Bukkit.getOnlinePlayers()) {
            seconds.put(p.getUniqueId(), 0L);
            lastActivity.put(p.getUniqueId(), now);
        }
        Bukkit.getPluginManager().registerEvents(this, plugin);
        Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            long currentTime = System.currentTimeMillis();
            for (Player p : Bukkit.getOnlinePlayers()) {
                UUID id = p.getUniqueId();
                if (!afkEligible && currentTime - lastActivity.getOrDefault(id, currentTime) >= afkTimeout * 1000L) {
                    seconds.put(id, 0L);
                    continue;
                }
                long elapsed = seconds.getOrDefault(id, 0L) + 1L;
                if (elapsed >= interval) {
                    seconds.put(id, 0L);
                    KelvorasManager manager = ((KelvoraSMP) plugin).passiveKelvorasManager();
                    if (manager != null && manager.deposit(p, amount)) {
                        p.sendActionBar(Component.text("💎 +" + KelvorasManager.format(amount) + " Kelvoras", NamedTextColor.AQUA));
                    } else {
                        plugin.getLogger().warning("Passive Kelvoras payment failed for " + p.getName() + ".");
                    }
                } else seconds.put(id, elapsed);
            }
            seconds.keySet().removeIf(id -> Bukkit.getPlayer(id) == null);
            lastActivity.keySet().removeIf(id -> Bukkit.getPlayer(id) == null);
        }, 20L, 20L);
        plugin.getLogger().info("Passive Kelvoras: " + amount + " every " + interval + " online seconds; AFK eligible=" + afkEligible + (afkEligible ? "." : "; activity timeout=" + afkTimeout + " seconds."));
    }

    private void activity(Player player) { lastActivity.put(player.getUniqueId(), System.currentTimeMillis()); }
    @EventHandler public void onJoin(PlayerJoinEvent e) { seconds.put(e.getPlayer().getUniqueId(), 0L); activity(e.getPlayer()); }
    @EventHandler public void onQuit(PlayerQuitEvent e) { seconds.remove(e.getPlayer().getUniqueId()); lastActivity.remove(e.getPlayer().getUniqueId()); }
    @EventHandler public void onMove(PlayerMoveEvent e) {
        if (e.getTo() != null && (e.getFrom().getBlockX() != e.getTo().getBlockX() || e.getFrom().getBlockY() != e.getTo().getBlockY() || e.getFrom().getBlockZ() != e.getTo().getBlockZ())) activity(e.getPlayer());
    }
    @EventHandler public void onInteract(PlayerInteractEvent e) { activity(e.getPlayer()); }
    @EventHandler public void onBreak(BlockBreakEvent e) { activity(e.getPlayer()); }
    @EventHandler public void onPlace(BlockPlaceEvent e) { activity(e.getPlayer()); }
    @EventHandler public void onCombat(EntityDamageByEntityEvent e) { if (e.getDamager() instanceof Player p) activity(p); }
}
