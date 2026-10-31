package net.kelvora.smp;

import java.text.Normalizer;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
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
 * Chat moderation for hate speech and repeated/near-identical messages.
 * Message checks run before chat is broadcast.
 */
final class ChatSpamProtectionManager implements Listener {
    private static final int RECENT_MESSAGE_LIMIT = 5;
    private static final long RATE_WINDOW_MS = 4_000L;
    private static final int MAX_MESSAGES_IN_WINDOW = 5;

    /*
     * Store normalized roots, not display spellings. Normalization removes
     * punctuation/spacing and maps common leetspeak substitutions first.
     * Keep this list focused on slurs and hateful abuse to reduce false positives.
     */
    private static final Set<String> BLOCKED_TERMS = Set.of(
        "nigger", "nigga", "niggers", "niggas",
        "faggot", "fag", "faggots", "fags",
        "tranny", "trannies",
        "kike", "kikes",
        "chink", "chinks",
        "spic", "spics",
        "wetback", "wetbacks",
        "coon", "coons",
        "retard", "retarded", "retards",
        "beaner", "beaners"
    );

    private final JavaPlugin plugin;
    private final Map<UUID, Deque<String>> recentAcceptedMessages = new ConcurrentHashMap<>();
    private final Map<UUID, Deque<Long>> recentMessageTimes = new ConcurrentHashMap<>();

    ChatSpamProtectionManager(JavaPlugin plugin) { this.plugin = plugin; }

    void register() { Bukkit.getPluginManager().registerEvents(this, plugin); }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onChat(AsyncPlayerChatEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        String original = event.getMessage();
        String moderationText = normalizeForModeration(original);
        if (moderationText.isBlank()) return;

        if (containsBlockedTerm(moderationText)) {
            warn(event, event.getPlayer(), "That language is not allowed on Kelvora SMP.");
            return;
        }

        String repeatKey = normalizeForRepeat(original);
        Deque<String> messages = recentAcceptedMessages.computeIfAbsent(id, ignored -> new ArrayDeque<>());
        synchronized (messages) {
            for (String previous : messages) {
                if (isNearDuplicate(previous, repeatKey)) {
                    warn(event, event.getPlayer(), "Don't repeat the same word or message. Say something different.");
                    return;
                }
            }
        }

        long now = System.currentTimeMillis();
        Deque<Long> times = recentMessageTimes.computeIfAbsent(id, ignored -> new ArrayDeque<>());
        synchronized (times) {
            while (!times.isEmpty() && now - times.peekFirst() > RATE_WINDOW_MS) times.removeFirst();
            if (times.size() >= MAX_MESSAGES_IN_WINDOW) {
                warn(event, event.getPlayer(), "You're chatting too quickly. Please slow down.");
                return;
            }
            times.addLast(now);
        }

        synchronized (messages) {
            messages.addFirst(repeatKey);
            while (messages.size() > RECENT_MESSAGE_LIMIT) messages.removeLast();
        }
    }

    private String normalizeForModeration(String message) {
        String value = Normalizer.normalize(message, Normalizer.Form.NFKD)
            .toLowerCase(Locale.ROOT)
            .replaceAll("\\p{M}+", "");
        value = value.replace('0', 'o').replace('1', 'i').replace('!', 'i')
            .replace('3', 'e').replace('4', 'a').replace('@', 'a')
            .replace('5', 's').replace('$', 's').replace('7', 't');
        return value.replaceAll("[^a-z]", "");
    }

    private boolean containsBlockedTerm(String normalized) {
        for (String term : BLOCKED_TERMS) {
            if (normalized.contains(term)) return true;
        }
        return false;
    }

    private String normalizeForRepeat(String message) {
        String value = Normalizer.normalize(message, Normalizer.Form.NFKC)
            .toLowerCase(Locale.ROOT)
            .replaceAll("[^\\p{L}\\p{N}]+", "");
        // Treat w, ww, www and similar character stretching as the same message.
        return value.replaceAll("(.)\\1{1,}", "$1");
    }

    private boolean isNearDuplicate(String previous, String current) {
        if (previous.equals(current)) return true;
        if (previous.isEmpty() || current.isEmpty()) return false;
        // Catch a repeated word/message with small additions, punctuation or spacing.
        if (previous.contains(current) || current.contains(previous)) {
            int shorter = Math.min(previous.length(), current.length());
            int longer = Math.max(previous.length(), current.length());
            return shorter >= 2 && (double) shorter / longer >= 0.70;
        }
        return false;
    }

    private void warn(AsyncPlayerChatEvent event, Player player, String message) {
        event.setCancelled(true);
        Bukkit.getScheduler().runTask(plugin, () -> player.sendMessage(ChatColor.RED + message));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        recentAcceptedMessages.remove(id);
        recentMessageTimes.remove(id);
    }
}
