package net.kelvora.smp;

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

/** Prevents repeated chat messages and obvious random-letter spam. */
final class ChatSpamProtectionManager implements Listener {
    private static final long REPEAT_WINDOW_MS = 8_000L;
    private final JavaPlugin plugin;
    private final Map<UUID, String> lastMessage = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastMessageAt = new ConcurrentHashMap<>();

    ChatSpamProtectionManager(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    void register() {
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onChat(AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();
        UUID id = player.getUniqueId();
        String message = normalize(event.getMessage());
        long now = System.currentTimeMillis();
        String previous = lastMessage.get(id);
        long previousAt = lastMessageAt.getOrDefault(id, 0L);

        if (previous != null && now - previousAt <= REPEAT_WINDOW_MS
                && (message.equals(previous) || hasGibberishSuffix(message, previous))) {
            cancel(event, player, ChatColor.RED + "Please don't repeat the same message. Chat is protected by Kelvora SMP.");
            return;
        }

        if (containsRandomLetterSpam(message)) {
            cancel(event, player, ChatColor.RED + "Please don't spam random letters. Chat is protected by Kelvora SMP.");
            return;
        }

        lastMessage.put(id, message);
        lastMessageAt.put(id, now);
    }

    private void cancel(AsyncPlayerChatEvent event, Player player, String warning) {
        event.setCancelled(true);
        Bukkit.getScheduler().runTask(plugin, () -> player.sendMessage(warning));
    }

    private String normalize(String message) {
        return message.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9 ]", " ")
                .replaceAll("\\s+", " ").trim();
    }

    private boolean hasGibberishSuffix(String message, String previous) {
        if (!message.startsWith(previous + " ")) return false;
        String suffix = message.substring(previous.length()).trim();
        return containsRandomLetterSpam(suffix);
    }

    private boolean containsRandomLetterSpam(String message) {
        for (String token : message.split(" ")) {
            String letters = token.replaceAll("[^a-z]", "");
            if (letters.length() < 6) continue;
            int vowels = 0;
            for (int i = 0; i < letters.length(); i++) {
                if ("aeiouy".indexOf(letters.charAt(i)) >= 0) vowels++;
            }
            if (vowels == 0 || (letters.length() >= 8 && vowels * 10 < letters.length() * 2)) return true;
        }
        return false;
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        lastMessage.remove(id);
        lastMessageAt.remove(id);
    }
}
