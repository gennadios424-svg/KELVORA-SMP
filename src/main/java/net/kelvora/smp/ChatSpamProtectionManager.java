package net.kelvora.smp;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Conservative chat anti-spam: repeated text and sustained rapid posting only.
 * It deliberately does not classify ordinary words as "gibberish".
 */
final class ChatSpamProtectionManager implements Listener {
    private static final long REPEAT_WINDOW_MS = 5_000L;
    private static final long RATE_WINDOW_MS = 4_000L;
    private static final int MAX_MESSAGES_IN_WINDOW = 5;
    private final JavaPlugin plugin;
    private final Map<UUID, String> lastMessage = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastMessageAt = new ConcurrentHashMap<>();
    private final Map<UUID, Deque<Long>> recentMessages = new ConcurrentHashMap<>();

    ChatSpamProtectionManager(JavaPlugin plugin) { this.plugin = plugin; }

    void register() { Bukkit.getPluginManager().registerEvents(this, plugin); }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onChat(AsyncPlayerChatEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        long now = System.currentTimeMillis();
        String message = normalize(event.getMessage());
        String previous = lastMessage.get(id);
        long previousAt = lastMessageAt.getOrDefault(id, 0L);

        if (message.isBlank()) return;
        if (previous != null && message.equals(previous) && now - previousAt <= REPEAT_WINDOW_MS) {
            warn(event, event.getPlayer(), "Please don't repeat the same message. Chat is protected by Kelvora SMP.");
            return;
        }

        Deque<Long> times = recentMessages.computeIfAbsent(id, ignored -> new ArrayDeque<>());
        synchronized (times) {
            while (!times.isEmpty() && now - times.peekFirst() > RATE_WINDOW_MS) times.removeFirst();
            if (times.size() >= MAX_MESSAGES_IN_WINDOW) {
                warn(event, event.getPlayer(), "You're chatting too quickly. Please slow down. Chat is protected by Kelvora SMP.");
                return;
            }
            times.addLast(now);
        }

        lastMessage.put(id, message);
        lastMessageAt.put(id, now);
    }

    private String normalize(String message) {
        return message.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
    }

    private void warn(AsyncPlayerChatEvent event, Player player, String message) {
        event.setCancelled(true);
        Bukkit.getScheduler().runTask(plugin, () -> player.sendMessage(ChatColor.RED + message));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        lastMessage.remove(id);
        lastMessageAt.remove(id);
        recentMessages.remove(id);
    }
}
