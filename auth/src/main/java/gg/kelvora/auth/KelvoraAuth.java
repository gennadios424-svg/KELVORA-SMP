package gg.kelvora.auth;

import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.*;
import org.bukkit.plugin.java.JavaPlugin;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.io.File;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class KelvoraAuth extends JavaPlugin implements Listener {
    private static final String GREEN = ChatColor.GREEN.toString();
    private static final String PREFIX = ChatColor.DARK_GREEN + "[Kelvora] " + ChatColor.GRAY;
    private static final int ITERATIONS = 210_000;
    private static final int KEY_BITS = 256;
    private static final int MIN_PASSWORD = 8;
    private static final int MAX_PASSWORD = 72;
    private final Set<UUID> authenticated = ConcurrentHashMap.newKeySet();
    private final SecureRandom random = new SecureRandom();
    private File accountsFile;
    private YamlConfiguration accounts;

    @Override public void onEnable() {
        if (!getDataFolder().exists() && !getDataFolder().mkdirs()) {
            getLogger().severe("Could not create plugin data folder; disabling.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        accountsFile = new File(getDataFolder(), "accounts.yml");
        accounts = YamlConfiguration.loadConfiguration(accountsFile);
        getServer().getPluginManager().registerEvents(this, this);
        Objects.requireNonNull(getCommand("register")).setExecutor((sender, command, label, args) -> {
            if (!(sender instanceof Player player)) { sender.sendMessage("Players only."); return true; }
            if (args.length != 2) { player.sendMessage(PREFIX + "Use " + ChatColor.GREEN + "/register <password> <confirmPassword>"); return true; }
            if (authenticated.contains(player.getUniqueId())) { player.sendMessage(PREFIX + "You are already logged in."); return true; }
            String key = key(player);
            if (accounts.contains(key + ".hash")) { player.sendMessage(PREFIX + "Account already registered. Use /login <password>."); return true; }
            if (!args[0].equals(args[1])) { player.sendMessage(PREFIX + "Passwords do not match."); return true; }
            if (!validPassword(args[0], player)) return true;
            byte[] salt = new byte[16]; random.nextBytes(salt);
            accounts.set(key + ".salt", Base64.getEncoder().encodeToString(salt));
            accounts.set(key + ".hash", hash(args[0].toCharArray(), salt));
            accounts.set(key + ".algorithm", "PBKDF2WithHmacSHA256");
            if (!saveAccounts()) { accounts.set(key, null); player.sendMessage(PREFIX + ChatColor.RED + "Registration could not be saved. Try again later."); return true; }
            authenticated.add(player.getUniqueId());
            player.sendTitle(ChatColor.GREEN + "REGISTERED", ChatColor.GRAY + "Welcome to Kelvora SMP", 5, 45, 15);
            player.sendMessage(PREFIX + "Registration successful. You are now logged in.");
            return true;
        });
        Objects.requireNonNull(getCommand("login")).setExecutor((sender, command, label, args) -> {
            if (!(sender instanceof Player player)) { sender.sendMessage("Players only."); return true; }
            if (args.length != 1) { player.sendMessage(PREFIX + "Use " + ChatColor.GREEN + "/login <password>"); return true; }
            if (authenticated.contains(player.getUniqueId())) { player.sendMessage(PREFIX + "You are already logged in."); return true; }
            String key = key(player);
            if (!accounts.contains(key + ".hash")) { player.sendMessage(PREFIX + "No account yet. Use /register <password> <confirmPassword>."); return true; }
            try {
                byte[] salt = Base64.getDecoder().decode(accounts.getString(key + ".salt", ""));
                byte[] expected = Base64.getDecoder().decode(accounts.getString(key + ".hash", ""));
                byte[] actual = Base64.getDecoder().decode(hash(args[0].toCharArray(), salt));
                if (!MessageDigest.isEqual(expected, actual)) { player.sendMessage(PREFIX + ChatColor.RED + "Incorrect password."); return true; }
            } catch (IllegalArgumentException ex) { player.sendMessage(PREFIX + ChatColor.RED + "Account data is invalid. Contact staff."); return true; }
            authenticated.add(player.getUniqueId());
            player.sendTitle(ChatColor.GREEN + "LOGGED IN", ChatColor.GRAY + "Welcome back to Kelvora SMP", 5, 35, 15);
            player.sendMessage(PREFIX + "Login successful. Enjoy your time on Kelvora!");
            return true;
        });
        Objects.requireNonNull(getCommand("changepassword")).setExecutor((sender, command, label, args) -> {
            if (!(sender instanceof Player player)) { sender.sendMessage("Players only."); return true; }
            if (!isAuthed(player)) { prompt(player); return true; }
            if (args.length != 3) { player.sendMessage(PREFIX + "Use /changepassword <currentPassword> <newPassword> <confirmNewPassword>"); return true; }
            String key = key(player);
            if (!accounts.contains(key + ".hash")) { player.sendMessage(PREFIX + "Register first with /register."); return true; }
            try {
                byte[] salt = Base64.getDecoder().decode(accounts.getString(key + ".salt", ""));
                byte[] expected = Base64.getDecoder().decode(accounts.getString(key + ".hash", ""));
                byte[] actual = Base64.getDecoder().decode(hash(args[0].toCharArray(), salt));
                if (!MessageDigest.isEqual(expected, actual)) { player.sendMessage(PREFIX + ChatColor.RED + "Current password is incorrect."); return true; }
            } catch (IllegalArgumentException ex) { player.sendMessage(PREFIX + ChatColor.RED + "Account data is invalid. Contact staff."); return true; }
            if (!args[1].equals(args[2])) { player.sendMessage(PREFIX + "New passwords do not match."); return true; }
            if (!validPassword(args[1], player)) return true;
            byte[] salt = new byte[16]; random.nextBytes(salt);
            accounts.set(key + ".salt", Base64.getEncoder().encodeToString(salt));
            accounts.set(key + ".hash", hash(args[1].toCharArray(), salt));
            if (!saveAccounts()) { player.sendMessage(PREFIX + ChatColor.RED + "Could not save password change. Contact staff."); return true; }
            player.sendMessage(PREFIX + ChatColor.GREEN + "Password changed successfully.");
            return true;
        });
        Objects.requireNonNull(getCommand("logout")).setExecutor((sender, command, label, args) -> {
            if (!(sender instanceof Player player)) { sender.sendMessage("Players only."); return true; }
            if (!authenticated.remove(player.getUniqueId())) { prompt(player); return true; }
            player.sendMessage(PREFIX + "You have logged out. Use /login <password> to continue.");
            showPrompt(player);
            return true;
        });
        getLogger().info("KelvoraAuth enabled. Passwords are stored as salted PBKDF2 hashes.");
    }

    @Override public void onDisable() { saveAccounts(); authenticated.clear(); }

    private String key(Player player) { return "accounts." + player.getUniqueId(); }
    private boolean isAuthed(Player player) { return player.hasPermission("kelvoraauth.bypass") || authenticated.contains(player.getUniqueId()); }
    private boolean validPassword(String password, Player player) {
        if (password.length() < MIN_PASSWORD || password.length() > MAX_PASSWORD) {
            player.sendMessage(PREFIX + "Password must be " + MIN_PASSWORD + "-" + MAX_PASSWORD + " characters.");
            return false;
        }
        if (password.equalsIgnoreCase(player.getName())) { player.sendMessage(PREFIX + "Do not use your Minecraft name as your password."); return false; }
        return true;
    }
    private String hash(char[] password, byte[] salt) {
        PBEKeySpec spec = new PBEKeySpec(password, salt, ITERATIONS, KEY_BITS);
        try {
            byte[] derived = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
            return Base64.getEncoder().encodeToString(derived);
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("PBKDF2 is unavailable", ex);
        } finally { spec.clearPassword(); Arrays.fill(password, '\0'); }
    }
    private boolean saveAccounts() {
        try { accounts.save(accountsFile); return true; }
        catch (IOException ex) { getLogger().severe("Unable to save accounts.yml: " + ex.getMessage()); return false; }
    }
    private void prompt(Player player) {
        if (!isAuthed(player)) {
            boolean registered = accounts.contains(key(player) + ".hash");
            player.sendMessage(PREFIX + (registered ? "Please /login <password> to play." : "Please /register <password> <confirmPassword> to play."));
            showPrompt(player);
        }
    }
    private void showPrompt(Player player) {
        boolean registered = accounts.contains(key(player) + ".hash");
        player.sendTitle(ChatColor.DARK_GREEN + "KELVORA SMP",
            registered ? ChatColor.WHITE + " /login <password> to play" : ChatColor.WHITE + " /register <password> <confirm> to play",
            0, 100, 10);
    }
    private boolean blocked(Player player) { return !isAuthed(player); }

    @EventHandler public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        authenticated.remove(player.getUniqueId());
        player.setFallDistance(0);
        Location safe = player.getLocation().clone();
        getServer().getScheduler().runTask(this, () -> {
            if (!player.isOnline() || !blocked(player)) return;
            player.setFallDistance(0);
            prompt(player);
        });
    }
    @EventHandler public void onQuit(PlayerQuitEvent event) { authenticated.remove(event.getPlayer().getUniqueId()); }

    // Freeze only horizontal movement. Vertical motion/gravity remains enabled to avoid floating and movement-related kicks.
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (!blocked(event.getPlayer()) || event.getTo() == null) return;
        Location from = event.getFrom(), to = event.getTo();
        if (from.getX() != to.getX() || from.getZ() != to.getZ()) {
            event.setTo(new Location(from.getWorld(), from.getX(), to.getY(), from.getZ(), to.getYaw(), to.getPitch()));
        }
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true) public void onTeleport(PlayerTeleportEvent e) {
        if (blocked(e.getPlayer()) && e.getCause() != PlayerTeleportEvent.TeleportCause.UNKNOWN) e.setCancelled(true);
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true) public void onBreak(BlockBreakEvent e) { if (blocked(e.getPlayer())) e.setCancelled(true); }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true) public void onPlace(BlockPlaceEvent e) { if (blocked(e.getPlayer())) e.setCancelled(true); }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true) public void onInteract(PlayerInteractEvent e) { if (blocked(e.getPlayer())) e.setCancelled(true); }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true) public void onInteractEntity(PlayerInteractEntityEvent e) { if (blocked(e.getPlayer())) e.setCancelled(true); }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true) public void onInteractAtEntity(PlayerInteractAtEntityEvent e) { if (blocked(e.getPlayer())) e.setCancelled(true); }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true) public void onDrop(PlayerDropItemEvent e) { if (blocked(e.getPlayer())) e.setCancelled(true); }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true) public void onSwap(PlayerSwapHandItemsEvent e) { if (blocked(e.getPlayer())) e.setCancelled(true); }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true) public void onConsume(PlayerItemConsumeEvent e) { if (blocked(e.getPlayer())) e.setCancelled(true); }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true) public void onPickup(EntityPickupItemEvent e) { if (e.getEntity() instanceof Player p && blocked(p)) e.setCancelled(true); }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true) public void onInventory(InventoryClickEvent e) { if (e.getWhoClicked() instanceof Player p && blocked(p)) e.setCancelled(true); }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true) public void onDamage(EntityDamageEvent e) { if (e.getEntity() instanceof Player p && blocked(p)) e.setCancelled(true); }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true) public void onChat(AsyncPlayerChatEvent e) { if (blocked(e.getPlayer())) { e.setCancelled(true); e.getPlayer().sendMessage(PREFIX + "Chat is locked until you authenticate."); } }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true) public void onCommand(PlayerCommandPreprocessEvent e) {
        Player p = e.getPlayer();
        if (p.hasPermission("kelvoraauth.bypass") || authenticated.contains(p.getUniqueId())) return;
        String root = e.getMessage().split(" ", 2)[0].toLowerCase(Locale.ROOT);
        if (!root.equals("/register") && !root.equals("/reg") && !root.equals("/login") && !root.equals("/l")) {
            e.setCancelled(true);
            p.sendMessage(PREFIX + "Authenticate first using /register or /login.");
        }
    }
}