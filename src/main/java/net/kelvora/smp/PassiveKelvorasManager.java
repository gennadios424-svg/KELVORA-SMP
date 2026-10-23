package net.kelvora.smp;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Tracks online seconds in-memory so offline time and partial time across restarts are never paid. */
final class PassiveKelvorasManager {
    private final JavaPlugin plugin;
    private final Map<UUID, Long> seconds = new HashMap<>();
    PassiveKelvorasManager(JavaPlugin plugin) { this.plugin = plugin; }
    void register() {
        if (!plugin.getConfig().getBoolean("passive-kelvoras.enabled", true)) return;
        long interval = Math.max(60L, plugin.getConfig().getLong("passive-kelvoras.interval-seconds", 300L));
        double amount = plugin.getConfig().getDouble("passive-kelvoras.amount", 1.0);
        boolean afkEligible = plugin.getConfig().getBoolean("passive-kelvoras.afk-eligible", true);
        if (!Double.isFinite(amount) || amount <= 0) { plugin.getLogger().warning("Passive Kelvoras disabled: amount must be positive."); return; }
        for (Player p : Bukkit.getOnlinePlayers()) seconds.put(p.getUniqueId(), 0L);
        Bukkit.getPluginManager().registerEvents(new org.bukkit.event.Listener() {
            @org.bukkit.event.EventHandler public void join(org.bukkit.event.player.PlayerJoinEvent e) { seconds.put(e.getPlayer().getUniqueId(), 0L); }
            @org.bukkit.event.EventHandler public void quit(org.bukkit.event.player.PlayerQuitEvent e) { seconds.remove(e.getPlayer().getUniqueId()); }
        }, plugin);
        Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            for (Player p : Bukkit.getOnlinePlayers()) {
                long n = seconds.getOrDefault(p.getUniqueId(), 0L) + 1L;
                if (n >= interval) {
                    seconds.put(p.getUniqueId(), 0L);
                    KelvorasManager manager = ((KelvoraSMP) plugin).passiveKelvorasManager();
                    if (manager != null && manager.deposit(p, amount))
                        p.sendActionBar(Component.text("💎 +" + KelvorasManager.format(amount) + " Kelvoras", NamedTextColor.AQUA));
                    else plugin.getLogger().warning("Passive Kelvoras payment failed for " + p.getName() + ".");
                } else seconds.put(p.getUniqueId(), n);
            }
            seconds.keySet().removeIf(id -> Bukkit.getPlayer(id) == null);
        }, 20L, 20L);
        plugin.getLogger().info("Passive Kelvoras: " + amount + " every " + interval + " online seconds; AFK eligible=" + afkEligible + ". Bukkit has no universal AFK API, so AFK detection is not available without a server-specific integration.");
    }
}
