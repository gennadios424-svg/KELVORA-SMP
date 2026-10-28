package net.kelvora.smp;

import java.io.File;
import java.io.IOException;
import java.util.*;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.command.*;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.GameMode;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.Location;

final class StaffFeaturesManager implements Listener, CommandExecutor, TabCompleter {
    private final JavaPlugin plugin;
    private final File file;
    private final YamlConfiguration data;
    private final Map<UUID, Deque<Long>> minedBlocks = new HashMap<>();
    private final Map<UUID, List<String>> recentOres = new HashMap<>();
    private final Map<UUID, Long> recentOreTimes = new HashMap<>();
    private final Map<UUID, Deque<Long>> debrisTimes = new HashMap<>();
    private final Map<UUID, Deque<Long>> diamondTimes = new HashMap<>();
    private final Set<String> playerPlacedBlocks = new HashSet<>();
    private final Map<UUID, UUID> suspectGuiTargets = new HashMap<>();
    private static final String SUS_TITLE = ChatColor.DARK_GREEN + "Kelvora | Suslist";
    private final Set<UUID> vanished = new HashSet<>();
    private final Map<String, Long> recentGrimFlags = new HashMap<>();
    private static final List<String> BANNED_WORDS = List.of("fuck", "fucker", "fucking", "motherfucker", "shit", "shitty", "bullshit", "bitch", "bastard", "asshole", "arsehole", "dick", "dickhead", "cock", "pussy", "cunt", "slut", "whore", "nigger", "nigga", "retard", "faggot");

    StaffFeaturesManager(JavaPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "staff-features.yml");
        this.data = YamlConfiguration.loadConfiguration(file);
    }

    void register() {
        ConfigurationSection roles = data.getConfigurationSection("roles");
        if (roles == null) {
            data.set("roles.staff.label", "Staff"); data.set("roles.staff.color", "&c&l");
            data.set("roles.media.label", "Media"); data.set("roles.media.color", "&d&l");
        }
        if (!data.contains("media.follower-count") || data.getString("media.follower-count", "").equalsIgnoreCase("Set by staff")) data.set("media.follower-count", "300 followers");
        if (!data.contains("media.content")) data.set("media.content", "Minecraft content, no stolen clips");
        if (!data.contains("media.activity")) data.set("media.activity", "Consistent recent uploads or streams");
        if (!data.contains("media.requirements") || data.getStringList("media.requirements").stream().anyMatch(x -> x.toLowerCase(Locale.ROOT).contains("set by staff"))) data.set("media.requirements", List.of("At least 2,000 views on a video featuring Kelvora SMP", "Live streams average at least 5 viewers", "At least 300 followers", "If all requirements are met, open a ticket in the Kelvora Discord."));
        if (!data.contains("media.application")) data.set("media.application", "Open a ticket in the Kelvora Discord");
        if (!data.contains("media.discord-url")) data.set("media.discord-url", "https://discord.gg/gUt2uGcwe");
        save();
        Bukkit.getPluginManager().registerEvents(this, plugin);
        registerGrimFlagHook();
        for (String command : List.of("live", "media", "role", "suslist", "vanish", "report")) {
            if (plugin.getCommand(command) != null) {
                plugin.getCommand(command).setExecutor(this);
                plugin.getCommand(command).setTabCompleter(this);
            }
        }
        for (Player player : Bukkit.getOnlinePlayers()) applyVanishVisibility(player);
    }

    private boolean manage(CommandSender sender) {
        return sender.isOp() || sender.hasPermission("kelvora.roles.manage") || sender.hasPermission("kelvora.staff.manager") || (sender instanceof Player p && hasRole(p, "manager"));
    }
    private boolean moderator(CommandSender sender) {
        if (sender instanceof Player p) return p.isOp() || p.hasPermission("kelvora.suslist") || p.hasPermission("kelvora.staff.mod")
            || p.hasPermission("kelvora.staff.admin") || hasRole(p, "moderator") || hasRole(p, "mod")
            || hasRole(p, "manager") || hasRole(p, "admin") || hasRole(p, "owner") || hasRole(p, "staff");
        return sender.isOp() || sender.hasPermission("kelvora.suslist") || sender.hasPermission("kelvora.staff.mod") || sender.hasPermission("kelvora.staff.admin");
    }
    private String key(String s) { return s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]", ""); }
    private boolean roleExists(String role) { return data.isConfigurationSection("roles." + key(role)); }
    private String roleLabel(String role) { return data.getString("roles." + key(role) + ".label", key(role)); }
    private String roleColor(String role) { return data.getString("roles." + key(role) + ".color", "&7"); }
    private List<String> roles(Player player) { return data.getStringList("players." + player.getUniqueId() + ".roles"); }
    private void setRoles(Player player, List<String> roles) { data.set("players." + player.getUniqueId() + ".roles", roles); save(); }
    private boolean hasRole(Player p, String role) {
        if (roles(p).stream().anyMatch(r -> r.equalsIgnoreCase(role))) return true;
        return p.hasPermission("kelvora.role." + key(role)) || (role.equalsIgnoreCase("media") && p.hasPermission("kelvora.live"));
    }
    private boolean isStaffOrMediaGroup(Player p, String wanted) {
        if (hasRole(p, wanted)) return true;
        return p.hasPermission("group." + wanted) || p.hasPermission("kelvora.staff." + wanted) || (plugin instanceof KelvoraSMP k && k.hasRoleGroup(p, wanted));
    }
    String tabLabels(Player player) {
        boolean staff = isStaffOrMediaGroup(player, "staff") || isStaffOrMediaGroup(player, "helper") || isStaffOrMediaGroup(player, "manager")
            || isStaffOrMediaGroup(player, "moderator") || isStaffOrMediaGroup(player, "admin")
            || isStaffOrMediaGroup(player, "owner") || roles(player).stream().anyMatch(r ->
                Set.of("helper", "manager", "moderator", "mod", "admin", "owner", "staff").contains(key(r)));
        boolean media = isStaffOrMediaGroup(player, "media")
            || roles(player).stream().anyMatch(r -> Set.of("media", "creator").contains(key(r)));
        // Show the actual operational rank, not a generic [Staff] label.
        if (isStaffOrMediaGroup(player, "owner")) return "&a&l[Owner]&r";
        if (isStaffOrMediaGroup(player, "manager")) return "&c&l[Manager]&r";
        if (isStaffOrMediaGroup(player, "admin")) return "&c&l[Admin]&r";
        if (isStaffOrMediaGroup(player, "moderator") || isStaffOrMediaGroup(player, "mod")) return "&9&l[Moderator]&r";
        if (isStaffOrMediaGroup(player, "helper")) return "&a&l[Helper]&r";
        if (isStaffOrMediaGroup(player, "staff") || staff) return "&9&l[Moderator]&r";
        if (media) return "&d&l[Media]&r";
        return "";
    }
    String liveTabLabel(Player player) {
        if (!data.getBoolean("live." + player.getUniqueId() + ".active", false)) return "";
        return ChatColor.RED + ChatColor.BOLD.toString() + "[LIVE]" + ChatColor.RESET;
    }
    boolean isVanished(Player player) { return vanished.contains(player.getUniqueId()); }

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String cmd = command.getName().toLowerCase(Locale.ROOT);
        if (cmd.equals("media")) {
            if (!(sender instanceof Player p)) { sender.sendMessage("Use /media in-game."); return true; }
            Inventory inv = Bukkit.createInventory(null, 27, ChatColor.DARK_GREEN + "Kelvora | Media");
            org.bukkit.inventory.ItemStack requirements = new org.bukkit.inventory.ItemStack(Material.WRITABLE_BOOK);
            org.bukkit.inventory.meta.ItemMeta rm = requirements.getItemMeta();
            rm.setDisplayName(ChatColor.LIGHT_PURPLE + "" + ChatColor.BOLD + "Media Requirements");
            List<String> mediaLore = new ArrayList<>();
            mediaLore.add(ChatColor.GRAY + "Follower / view target: " + data.getString("media.follower-count", "Set by staff"));
            for (String requirement : data.getStringList("media.requirements")) mediaLore.add(ChatColor.GRAY + requirement);
            mediaLore.add(ChatColor.GRAY + "Content: " + data.getString("media.content", "Reviewed by staff"));
            mediaLore.add(ChatColor.GRAY + "Activity: " + data.getString("media.activity", "Reviewed by staff"));
            mediaLore.add(ChatColor.GRAY + "Application: Open a ticket if all requirements are met.");
            mediaLore.add(ChatColor.YELLOW + "If you meet all requirements, open a Discord ticket.");
            rm.setLore(mediaLore);
            requirements.setItemMeta(rm); inv.setItem(11, requirements);
            org.bukkit.inventory.ItemStack apply = new org.bukkit.inventory.ItemStack(Material.BOOK);
            org.bukkit.inventory.meta.ItemMeta am = apply.getItemMeta();
            am.setDisplayName(ChatColor.GREEN + "" + ChatColor.BOLD + "Apply for Media");
            am.setLore(List.of(ChatColor.GRAY + "Join the Discord and contact staff.", ChatColor.AQUA + data.getString("media.discord-url", "https://discord.gg/gUt2uGcwe"), ChatColor.YELLOW + "Click to open Discord"));
            am.setCustomModelData(null); apply.setItemMeta(am); inv.setItem(15, apply);
            org.bukkit.inventory.ItemStack close = new org.bukkit.inventory.ItemStack(Material.BARRIER);
            org.bukkit.inventory.meta.ItemMeta cm = close.getItemMeta(); cm.setDisplayName(ChatColor.RED + "Close"); close.setItemMeta(cm); inv.setItem(22, close);
            p.openInventory(inv); return true;
        }
        if (cmd.equals("live")) {
            if (!(sender instanceof Player p)) { sender.sendMessage("Players only."); return true; }
            if (!isStaffOrMediaGroup(p, "media")) { p.sendMessage(ChatColor.RED + "Only Media can use /live."); return true; }
            if (args.length == 0 || args[0].equalsIgnoreCase("on")) {
                data.set("live." + p.getUniqueId() + ".active", true);
                p.sendMessage(ChatColor.LIGHT_PURPLE + "Live status enabled. Use /live <link> to add your stream/video link.");
            } else if (args[0].equalsIgnoreCase("off")) {
                data.set("live." + p.getUniqueId() + ".active", false);
                data.set("live." + p.getUniqueId() + ".link", null);
                p.sendMessage(ChatColor.YELLOW + "Live status disabled.");
            } else {
                String link = args[0].trim();
                if (!link.matches("(?i)^https?://\\S{1,120}$")) { p.sendMessage(ChatColor.RED + "Use a full http(s) link, e.g. /live https://twitch.tv/yourname"); return true; }
                data.set("live." + p.getUniqueId() + ".active", true);
                data.set("live." + p.getUniqueId() + ".link", link);
                p.sendMessage(ChatColor.LIGHT_PURPLE + "Live status enabled and link saved.");
            }
            save(); if (plugin instanceof KelvoraSMP k) k.refreshSidebar(p); return true;
        }
        if (cmd.equals("role")) {
            if (!manage(sender)) { sender.sendMessage(ChatColor.RED + "You need the Manager role to manage roles."); return true; }
            if (args.length == 0 || args[0].equalsIgnoreCase("list")) {
                sender.sendMessage(ChatColor.GREEN + "Kelvora roles: " + String.join(", ", Objects.requireNonNull(data.getConfigurationSection("roles")).getKeys(false))); return true;
            }
            if (args[0].equalsIgnoreCase("create")) {
                if (args.length < 3) { sender.sendMessage("Usage: /role create <name> <color-code>"); return true; }
                String role=key(args[1]); String colour=args[2];
                if (role.isBlank() || role.length()>16 || !colour.matches("&[0-9a-fk-or]")) { sender.sendMessage(ChatColor.RED + "Name must be 1-16 letters/numbers/underscores and color must be a code like &b."); return true; }
                if (roleExists(role)) { sender.sendMessage(ChatColor.RED + "That role already exists."); return true; }
                data.set("roles."+role+".label", args[1]); data.set("roles."+role+".color", colour + "&l"); save();
                sender.sendMessage(ChatColor.GREEN + "Created role " + args[1] + ". Assign with /role add <player> " + role + "."); return true;
            }
            if ((args[0].equalsIgnoreCase("add") || args[0].equalsIgnoreCase("remove")) && args.length >= 3) {
                Player target=Bukkit.getPlayerExact(args[1]); String role=key(args[2]);
                if (target==null || !roleExists(role)) { sender.sendMessage(ChatColor.RED + "Player must be online and role must exist."); return true; }
                List<String> owned=new ArrayList<>(roles(target));
                if (args[0].equalsIgnoreCase("add")) { if (!owned.contains(role)) owned.add(role); }
                else owned.removeIf(r -> r.equalsIgnoreCase(role));
                setRoles(target, owned); sender.sendMessage(ChatColor.GREEN + (args[0].equalsIgnoreCase("add")?"Added ":"Removed ")+role+" "+(args[0].equalsIgnoreCase("add")?"to ":"from ")+target.getName()+".");
                target.sendMessage(ChatColor.GREEN + "Your roles were updated: " + tabLabels(target)); if (plugin instanceof KelvoraSMP k) k.refreshSidebar(target); return true;
            }
            sender.sendMessage("Usage: /role list | create <name> <&color> | add <player> <role> | remove <player> <role>"); return true;
        }
        if (cmd.equals("vanish")) {
            if (!(sender instanceof Player p)) { sender.sendMessage("Players only."); return true; }
            if (!moderator(p) && !hasRole(p, "staff") && !hasRole(p, "manager") && !p.hasPermission("kelvora.vanish")) { p.sendMessage(ChatColor.RED+"You do not have permission."); return true; }
            if (vanished.remove(p.getUniqueId())) { p.removePotionEffect(PotionEffectType.INVISIBILITY); for (Player other:Bukkit.getOnlinePlayers()) other.showPlayer(plugin,p); p.sendMessage(ChatColor.YELLOW+"Vanish disabled."); }
            else { vanished.add(p.getUniqueId()); applyVanishVisibility(p); p.sendMessage(ChatColor.GREEN+"Vanish enabled."); }
            return true;
        }
        if (cmd.equals("report")) {
            if (!(sender instanceof Player reporter)) { sender.sendMessage("Players only."); return true; }
            if (args.length < 2) { reporter.sendMessage(ChatColor.RED + "Usage: /report <user> <reason> (reason must be at least 10 characters)"); return true; }
            String target = args[0];
            if (!target.matches("[A-Za-z0-9_]{3,16}")) { reporter.sendMessage(ChatColor.RED + "Enter a valid Minecraft username."); return true; }
            if (target.equalsIgnoreCase(reporter.getName())) { reporter.sendMessage(ChatColor.RED + "You cannot report yourself."); return true; }
            String reason = String.join(" ", Arrays.copyOfRange(args, 1, args.length)).trim();
            if (reason.length() < 10) { reporter.sendMessage(ChatColor.RED + "Your reason must be at least 10 characters."); return true; }
            long now = System.currentTimeMillis();
            String reportPath = "reports." + now;
            data.set(reportPath + ".by", reporter.getName());
            data.set(reportPath + ".target", target);
            data.set(reportPath + ".reason", reason);
            data.set(reportPath + ".time", now);
            save();
            reporter.sendMessage(ChatColor.GREEN + "Report submitted. Staff have been notified.");
            String notice = ChatColor.RED + "[REPORT] Report by " + reporter.getName() + " against " + target + ": " + reason + " — /suslist reports";
            for (Player staff : Bukkit.getOnlinePlayers()) if (moderator(staff)) staff.sendMessage(notice);
            plugin.getLogger().info("[REPORT] Report by " + reporter.getName() + " against " + target + ": " + reason);
            return true;
        }
        if (cmd.equals("suslist")) {
            if (!moderator(sender)) { sender.sendMessage(ChatColor.RED + "Only moderators and above can use /suslist."); return true; }
            ConfigurationSection suspects=data.getConfigurationSection("suspects");
            if (args.length == 1 && args[0].equalsIgnoreCase("alerts")) {
                ConfigurationSection all = data.getConfigurationSection("suspects");
                sender.sendMessage(ChatColor.GOLD + "Kelvora Suslist | Active alerts");
                if (all == null || all.getKeys(false).isEmpty()) { sender.sendMessage(ChatColor.GRAY + "No active alerts."); return true; }
                List<String> ids = new ArrayList<>(all.getKeys(false));
                ids.sort((a,b) -> Integer.compare(all.getInt(b + ".score"), all.getInt(a + ".score")));
                for (String id : ids.stream().limit(20).toList()) sender.sendMessage(scoreColour(all.getInt(id + ".score"))
                    + all.getString(id + ".name", id) + ChatColor.GRAY + " — " + all.getInt(id + ".score") + "/100 | "
                    + all.getString(id + ".reason", "Suspicious activity")
                    + (all.getBoolean(id + ".reviewed") ? ChatColor.GREEN + " [REVIEWED]" : ChatColor.YELLOW + " [UNREVIEWED]"));
                return true;
            }
            if (args.length == 2 && args[0].equalsIgnoreCase("history")) {
                String id = findSuspectId(args[1]);
                if (id == null) { sender.sendMessage(ChatColor.RED + "No saved evidence for that player."); return true; }
                ConfigurationSection history = data.getConfigurationSection("suspects." + id + ".history");
                sender.sendMessage(ChatColor.GOLD + "Evidence history: " + data.getString("suspects." + id + ".name", args[1]));
                if (history == null || history.getKeys(false).isEmpty()) { sender.sendMessage(ChatColor.GRAY + "No detailed history saved."); return true; }
                List<String> entries = new ArrayList<>(history.getKeys(false));
                entries.sort((a,b) -> Long.compare(parseLongSafe(b), parseLongSafe(a)));
                for (String entry : entries.stream().limit(10).toList()) sender.sendMessage(ChatColor.GRAY
                    + new java.text.SimpleDateFormat("MM-dd HH:mm:ss").format(new java.util.Date(parseLongSafe(entry)))
                    + ChatColor.YELLOW + " — " + history.getString(entry + ".reason", "Alert")
                    + ChatColor.GRAY + " | score " + history.getInt(entry + ".score") + "/100");
                return true;
            }
            if (args.length == 2 && args[0].equalsIgnoreCase("review")) {
                String id = findSuspectId(args[1]);
                if (id == null) { sender.sendMessage(ChatColor.RED + "No saved evidence for that player."); return true; }
                String base = "suspects." + id;
                data.set(base + ".reviewed", true); data.set(base + ".reviewed-by", sender.getName());
                data.set(base + ".reviewed-at", System.currentTimeMillis()); save();
                sender.sendMessage(ChatColor.GREEN + "Marked " + data.getString(base + ".name", args[1]) + "'s alert as reviewed.");
                return true;
            }
            if (args.length >= 3 && args[0].equalsIgnoreCase("note")) {
                String id = findSuspectId(args[1]);
                if (id == null) { sender.sendMessage(ChatColor.RED + "No saved evidence for that player."); return true; }
                String note = String.join(" ", Arrays.copyOfRange(args, 2, args.length));
                data.set("suspects." + id + ".staff-note", note); data.set("suspects." + id + ".note-by", sender.getName());
                data.set("suspects." + id + ".note-at", System.currentTimeMillis()); save();
                sender.sendMessage(ChatColor.GREEN + "Saved staff note for " + data.getString("suspects." + id + ".name", args[1]) + ".");
                return true;
            }
            if (args.length == 1 && args[0].equalsIgnoreCase("reports")) {
                ConfigurationSection reports = data.getConfigurationSection("reports");
                if (reports == null || reports.getKeys(false).isEmpty()) { sender.sendMessage(ChatColor.GRAY + "No player reports saved."); return true; }
                sender.sendMessage(ChatColor.GOLD + "Recent player reports:");
                List<String> ids = new ArrayList<>(reports.getKeys(false));
                ids.sort((a,b) -> Long.compare(parseLongSafe(b), parseLongSafe(a)));
                for (String id : ids.stream().limit(15).toList()) sender.sendMessage(ChatColor.RED + "Report by " + reports.getString(id + ".by", "Unknown") + ChatColor.GRAY + " → " + ChatColor.YELLOW + reports.getString(id + ".target", "Unknown") + ChatColor.GRAY + ": " + reports.getString(id + ".reason", "No reason"));
                return true;
            }
            if (args.length == 1 && args[0].equalsIgnoreCase("reload")) { try { data.load(file); sender.sendMessage(ChatColor.GREEN + "Suslist evidence and staff settings reloaded."); } catch (Exception ex) { sender.sendMessage(ChatColor.RED + "Could not reload staff-features.yml: " + ex.getMessage()); } return true; }
            if (args.length == 1 && args[0].equalsIgnoreCase("tp")) { sender.sendMessage(ChatColor.YELLOW + "Usage: /suslist tp <player>"); return true; }
            if (args.length == 1) {
                ConfigurationSection all = data.getConfigurationSection("suspects");
                String found = null;
                if (all != null) for (String id : all.getKeys(false)) if (all.getString(id + ".name", "").equalsIgnoreCase(args[0])) { found = id; break; }
                if (found == null) { sender.sendMessage(ChatColor.RED + "No saved suspicious-mining evidence for that player."); return true; }
                sender.sendMessage(ChatColor.GOLD + "Suspect: " + all.getString(found + ".name", args[0]) + ChatColor.GRAY + " | Flags: " + all.getInt(found + ".flags", 1));
                sender.sendMessage(scoreColour(all.getInt(found + ".score")) + "Suspicion score: " + all.getInt(found + ".score") + "/100"
                    + (all.getBoolean(found + ".reviewed") ? ChatColor.GREEN + " | Reviewed by " + all.getString(found + ".reviewed-by", "staff") : ChatColor.YELLOW + " | Needs review"));
                sender.sendMessage(ChatColor.YELLOW + "Reason: " + all.getString(found + ".reason", "Suspicious mining pattern"));
                if (!all.getString(found + ".staff-note", "").isBlank()) sender.sendMessage(ChatColor.GRAY + "Staff note: " + all.getString(found + ".staff-note"));
                long expiresAt = all.getLong(found + ".expires", all.getLong(found + ".time", 0) + 5L * 60L * 60L * 1000L);
                long remaining = Math.max(0, expiresAt - System.currentTimeMillis());
                sender.sendMessage(ChatColor.GRAY + "Evidence expires in " + (remaining / 3600000L) + "h " + ((remaining % 3600000L) / 60000L) + "m. Teleport: /suslist tp " + all.getString(found + ".name", args[0]));
                return true;
            }
            if (args.length >= 2 && args[0].equalsIgnoreCase("tp")) {
                Player target=Bukkit.getPlayerExact(args[1]);
                if (target==null) { sender.sendMessage(ChatColor.RED+"That player is not online."); return true; }
                if (!(sender instanceof Player staff)) { sender.sendMessage("Console cannot teleport."); return true; }
                if (!vanished.contains(staff.getUniqueId())) { vanished.add(staff.getUniqueId()); applyVanishVisibility(staff); }
                staff.teleport(target.getLocation()); staff.sendMessage(ChatColor.GREEN+"Teleported to "+target.getName()+" in vanish. Check evidence before taking action."); return true;
            }
            openSuspectGui(sender, suspects);
            return true;
        }
        return false;
    }

    private long parseLongSafe(String value) { try { return Long.parseLong(value); } catch (NumberFormatException ignored) { return 0L; } }

    private void purgeExpiredFlags() {
        ConfigurationSection suspects=data.getConfigurationSection("suspects"); if(suspects==null) return;
        long now=System.currentTimeMillis();
        for(String id:new ArrayList<>(suspects.getKeys(false))) {
            long expires=suspects.getLong(id+".expires",suspects.getLong(id+".time",0)+5L*60L*60L*1000L);
            if(expires<=now) data.set("suspects."+id,null);
        }
        save();
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void filterProfanity(AsyncPlayerChatEvent event) {
        if (!containsBannedWord(event.getMessage())) return;
        event.setCancelled(true);
        Player player = event.getPlayer();
        Bukkit.getScheduler().runTask(plugin, () -> player.sendMessage(ChatColor.RED + "That word is blocked on Kelvora SMP. Keep chat respectful."));
        plugin.getLogger().info("[CHAT FILTER] Blocked profanity from " + player.getName());
    }

    private boolean containsBannedWord(String message) {
        String normalized = message.toLowerCase(Locale.ROOT).replace("0","o").replace("1","i").replace("!","i").replace("3","e").replace("4","a").replace("@","a").replace("5","s").replace("$","s").replace("7","t").replace("8","b");
        String[] tokens = normalized.split("[^a-z]+");
        for (String token : tokens) for (String banned : BANNED_WORDS)
            if (token.equals(banned) || (token.length() <= banned.length() + 3 && token.startsWith(banned))) return true;
        String compact = normalized.replaceAll("[^a-z0-9]", "");
        for (String banned : BANNED_WORDS) if (compact.contains(banned)) return true;
        return false;
    }

    private void registerGrimFlagHook() {
        for (String className : List.of("ac.grim.grimac.api.events.FlagEvent", "ac.grim.grimac.api.events.GrimFlagEvent", "ac.grim.grimac.events.FlagEvent")) {
            try {
                Class<?> raw = Class.forName(className, false, plugin.getClass().getClassLoader());
                if (!Event.class.isAssignableFrom(raw)) continue;
                @SuppressWarnings("unchecked") Class<? extends Event> eventClass = (Class<? extends Event>) raw;
                Bukkit.getPluginManager().registerEvent(eventClass, this, EventPriority.MONITOR, (listener, event) -> handleGrimFlag(event), plugin, true);
                plugin.getLogger().info("Connected to GrimAC flag events via " + className);
                return;
            } catch (ClassNotFoundException ignored) { }
            catch (Exception ex) { plugin.getLogger().warning("Could not register GrimAC flag hook: " + ex.getMessage()); }
        }
        plugin.getLogger().info("GrimAC flag API not detected; mining and player-report evidence still feeds /suslist.");
    }

    private void handleGrimFlag(Event event) {
        try {
            Object playerObject = invokeNoArg(event, "getPlayer", "getFlaggedPlayer", "getTarget");
            if (!(playerObject instanceof Player player)) return;
            String check = stringResult(event, "getCheckName", "getCheck", "getCheckType", "getName");
            String detail = stringResult(event, "getVerbose", "getDescription", "getMessage", "getCheckData");
            String reason = "GrimAC flag: " + (check.isBlank() ? event.getClass().getSimpleName() : check);
            if (!detail.isBlank() && !detail.equalsIgnoreCase(check)) reason += " (" + detail + ")";
            reason = reason.replace("\n", " ").replace("\r", " ");
            if (reason.length() > 180) reason = reason.substring(0, 180);
            String dedupeKey = player.getUniqueId() + ":" + check.toLowerCase(Locale.ROOT);
            long now = System.currentTimeMillis(); Long previous = recentGrimFlags.put(dedupeKey, now);
            if (previous != null && now - previous < 10_000L) return;
            flag(player, reason, player.getLocation().getBlock());
        } catch (Exception ex) { plugin.getLogger().fine("Ignored unreadable GrimAC flag event: " + ex.getMessage()); }
    }

    private Object invokeNoArg(Object target, String... methods) {
        for (String name : methods) try { return target.getClass().getMethod(name).invoke(target); } catch (ReflectiveOperationException ignored) { }
        return null;
    }

    private String stringResult(Object target, String... methods) { Object value = invokeNoArg(target, methods); return value == null ? "" : String.valueOf(value).trim(); }
    @EventHandler public void onBlockPlace(BlockPlaceEvent e) { if (e.getBlockPlaced().getWorld() != null) playerPlacedBlocks.add(blockKey(e.getBlockPlaced())); }
    private String blockKey(Block b) { return b.getWorld().getName()+":"+b.getX()+":"+b.getY()+":"+b.getZ(); }
    @EventHandler public void onMine(BlockBreakEvent event) {
        if (event.isCancelled()) return;
        Player p=event.getPlayer(); if (p.getGameMode()!=GameMode.SURVIVAL && p.getGameMode()!=GameMode.ADVENTURE) return;
        if (moderator(p) || hasRole(p,"staff") || hasRole(p,"manager")) return;
        Block block=event.getBlock(); String blockKey=blockKey(block); if (playerPlacedBlocks.remove(blockKey)) return;
        UUID id=p.getUniqueId(); long now=System.currentTimeMillis();
        Deque<Long> times=minedBlocks.computeIfAbsent(id, x->new ArrayDeque<>()); times.addLast(now);
        while (!times.isEmpty() && now-times.peekFirst()>10_000L) times.removeFirst();
        if (times.size()>=45) flag(p,"Breaking "+times.size()+" blocks in 10 seconds",block);
        Material type=block.getType();
        if (type==Material.ANCIENT_DEBRIS) {
            Deque<Long> found=debrisTimes.computeIfAbsent(id,x->new ArrayDeque<>()); found.addLast(now);
            while(!found.isEmpty() && now-found.peekFirst()>180_000L) found.removeFirst();
            if(found.size()==2) flag(p,"Mined "+found.size()+" ancient debris within 3 minutes",block);
        } else if (type==Material.DIAMOND_ORE || type==Material.DEEPSLATE_DIAMOND_ORE) {
            Deque<Long> found=diamondTimes.computeIfAbsent(id,x->new ArrayDeque<>()); found.addLast(now);
            while(!found.isEmpty() && now-found.peekFirst()>30_000L) found.removeFirst();
            if(found.size()==3) flag(p,"Mined "+found.size()+" diamond ores within 30 seconds",block);
        }
    }
    private void flag(Player p,String reason, Block ore) {
        long now=System.currentTimeMillis(); String path="suspects."+p.getUniqueId();
        int flags=data.getInt(path+".flags",0)+1;
        data.set(path+".name",p.getName()); data.set(path+".reason",reason); data.set(path+".world",ore.getWorld().getName());
        data.set(path+".x",ore.getX()); data.set(path+".y",ore.getY()); data.set(path+".z",ore.getZ());
        data.set(path+".tool",p.getInventory().getItemInMainHand().getType().name());
        data.set(path+".ore", reason.toLowerCase(Locale.ROOT).contains("ancient debris") ? "ANCIENT_DEBRIS" : reason.toLowerCase(Locale.ROOT).contains("diamond") ? "DIAMOND_ORE" : "BLOCK_RATE");
        java.util.regex.Matcher countMatcher = java.util.regex.Pattern.compile("(\\d+)").matcher(reason);
        data.set(path+".count", countMatcher.find() ? Integer.parseInt(countMatcher.group(1)) : 0);
        long previousTime = data.getLong(path + ".last-score-time", now);
        int previousScore = Math.max(0, data.getInt(path + ".score", 0) - (int)(Math.max(0, now - previousTime) / 3_600_000L) * 5);
        int points = reason.toLowerCase(Locale.ROOT).contains("ancient debris") ? 30
            : reason.toLowerCase(Locale.ROOT).contains("diamond") ? 22 : 18;
        int score = Math.min(100, previousScore + points);
        data.set(path + ".score", score); data.set(path + ".last-score-time", now);
        data.set(path+".flags",flags); data.set(path+".time",now); data.set(path+".expires",now+5L*60L*60L*1000L);
        data.set(path + ".reviewed", false);
        String historyPath = path + ".history." + now;
        data.set(historyPath + ".reason", reason); data.set(historyPath + ".score", score);
        data.set(historyPath + ".world", ore.getWorld().getName()); data.set(historyPath + ".x", ore.getX());
        data.set(historyPath + ".y", ore.getY()); data.set(historyPath + ".z", ore.getZ());
        data.set(historyPath + ".tool", p.getInventory().getItemInMainHand().getType().name());
        ConfigurationSection history = data.getConfigurationSection(path + ".history");
        if (history != null) for (String key : new ArrayList<>(history.getKeys(false))) {
            if (parseLongSafe(key) > 0 && now - parseLongSafe(key) > 7L * 24L * 60L * 60L * 1000L) data.set(path + ".history." + key, null);
        }
        save();
        for (Player staff:Bukkit.getOnlinePlayers()) if (moderator(staff) && score >= 30)
            staff.sendMessage(scoreColour(score) + "[SUS] " + p.getName() + ": " + reason + " | Score: " + score + "/100 | /suslist " + p.getName());
    }
    private String findSuspectId(String name) {
        ConfigurationSection all = data.getConfigurationSection("suspects");
        if (all == null) return null;
        for (String id : all.getKeys(false)) if (all.getString(id + ".name", "").equalsIgnoreCase(name) || id.equalsIgnoreCase(name)) return id;
        return null;
    }
    private ChatColor scoreColour(int score) {
        if (score >= 80) return ChatColor.DARK_RED;
        if (score >= 60) return ChatColor.RED;
        if (score >= 30) return ChatColor.GOLD;
        return ChatColor.GREEN;
    }
    private void openSuspectGui(CommandSender sender, ConfigurationSection suspects) {
        purgeExpiredFlags(); suspects=data.getConfigurationSection("suspects");
        if (!(sender instanceof Player staff)) {
            sender.sendMessage(ChatColor.GOLD+"Suspicious mining flags:");
            if (suspects==null || suspects.getKeys(false).isEmpty()) { sender.sendMessage(ChatColor.GRAY+"No players currently flagged."); return; }
            for (String id:suspects.getKeys(false)) sender.sendMessage(ChatColor.YELLOW+suspects.getString(id+".name",id)+ChatColor.GRAY+" — "+suspects.getString(id+".reason","Suspicious mining pattern")+" | /suslist tp "+suspects.getString(id+".name",id));
            return;
        }
        Inventory inv=Bukkit.createInventory(null,54,SUS_TITLE);
        suspectGuiTargets.clear();
        if (suspects!=null) {
            int slot=0;
            List<String> ordered = new ArrayList<>(suspects.getKeys(false));
            final ConfigurationSection sortedSuspects = suspects;
            ordered.sort((a,b) -> Integer.compare(sortedSuspects.getInt(b + ".score"), sortedSuspects.getInt(a + ".score")));
            for (String uuidText:ordered) {
                if(slot>=45) break;
                UUID id; try { id=UUID.fromString(uuidText); } catch(IllegalArgumentException ex) { continue; }
                String name=suspects.getString(uuidText+".name",uuidText);
                ItemStack head=new ItemStack(Material.PLAYER_HEAD); SkullMeta meta=(SkullMeta)head.getItemMeta();
                Player online=Bukkit.getPlayer(id); if(online!=null) meta.setOwningPlayer(online);
                String reason=suspects.getString(uuidText+".reason","Suspicious mining pattern");
                long expires=suspects.getLong(uuidText+".expires",suspects.getLong(uuidText+".time",0)+5L*60L*60L*1000L);
                long remaining=Math.max(0,expires-System.currentTimeMillis());
                String left=(remaining/3600000L)+"h "+((remaining%3600000L)/60000L)+"m";
                int flags=suspects.getInt(uuidText+".flags",1);
                meta.setDisplayName((reason.toLowerCase(Locale.ROOT).contains("ancient debris")?ChatColor.LIGHT_PURPLE:reason.toLowerCase(Locale.ROOT).contains("diamond")?ChatColor.AQUA:ChatColor.RED).toString()+ChatColor.BOLD+name);
                int score = suspects.getInt(uuidText + ".score", 0);
                String note = suspects.getString(uuidText + ".staff-note", "");
                meta.setLore(List.of(scoreColour(score) + "Suspicion: " + score + "/100", ChatColor.GRAY+reason,
                    ChatColor.RED+"Flags: "+flags,
                    suspects.getBoolean(uuidText + ".reviewed") ? ChatColor.GREEN + "Reviewed by " + suspects.getString(uuidText + ".reviewed-by", "staff") : ChatColor.YELLOW + "Needs review",
                    ChatColor.GRAY+"Expires in: "+left,ChatColor.GRAY+"World: "+suspects.getString(uuidText+".world","unknown"),
                    ChatColor.GRAY+"Coords: "+suspects.getInt(uuidText+".x")+", "+suspects.getInt(uuidText+".y")+", "+suspects.getInt(uuidText+".z"),
                    ChatColor.GRAY+"Tool: "+suspects.getString(uuidText+".tool","unknown"), ChatColor.GRAY+"Note: "+(note.isBlank()?"None":note),
                    ChatColor.YELLOW+"Left-click: teleport in vanish",ChatColor.YELLOW+"Right-click: inspect details"));
                head.setItemMeta(meta); inv.setItem(slot,head); suspectGuiTargets.put(id,id); slot++;
            }
        }
        ItemStack info=new ItemStack(Material.BOOK); org.bukkit.inventory.meta.ItemMeta im=info.getItemMeta(); im.setDisplayName(ChatColor.GOLD+"Investigation only"); im.setLore(List.of(ChatColor.GRAY+"Flags are evidence for staff review.",ChatColor.GRAY+"They never cause automatic punishment.")); info.setItemMeta(im); inv.setItem(49,info);
        staff.openInventory(inv);
    }
    @EventHandler public void onMediaClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player p) || ! (ChatColor.DARK_GREEN + "Kelvora | Media").equals(e.getView().getTitle())) return;
        e.setCancelled(true);
        if (e.getClickedInventory() == null || e.getClickedInventory() != e.getView().getTopInventory()) return;
        if (e.getSlot() == 15) p.closeInventory();
        if (e.getSlot() == 15) p.sendMessage(ChatColor.AQUA + "Apply for Media on Discord: " + data.getString("media.discord-url", "https://discord.gg/gUt2uGcwe"));
        if (e.getSlot() == 22) p.closeInventory();
    }
    @EventHandler public void onSuspectClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player staff) || !SUS_TITLE.equals(e.getView().getTitle())) return;
        e.setCancelled(true); if(e.getClickedInventory()==null || e.getClickedInventory()!=e.getView().getTopInventory()) return;
        ItemStack item=e.getCurrentItem(); if(item==null || item.getType()!=Material.PLAYER_HEAD || !(item.getItemMeta() instanceof SkullMeta meta)) return;
        String name=ChatColor.stripColor(meta.getDisplayName()); Player target=Bukkit.getPlayerExact(name); if(target==null){staff.sendMessage(ChatColor.RED+"That player is offline.");return;}
        if(e.isRightClick()) { ConfigurationSection s=data.getConfigurationSection("suspects."+target.getUniqueId()); if(s!=null) staff.sendMessage(ChatColor.GOLD+target.getName()+": "+s.getString("reason","Suspicious mining pattern")+ChatColor.GRAY+" at "+s.getString("world","?")+" "+s.getInt("x")+", "+s.getInt("y")+", "+s.getInt("z")+" using "+s.getString("tool","unknown")+". Review only; no automatic punishment."); return; }
        boolean already=vanished.contains(staff.getUniqueId()); if(!already){vanished.add(staff.getUniqueId());applyVanishVisibility(staff);} staff.teleport(target.getLocation()); staff.sendMessage(ChatColor.GREEN+"Teleported to "+target.getName()+" in vanish. Review evidence before action.");
    }
    @EventHandler public void onJoin(PlayerJoinEvent e) { applyVanishVisibility(e.getPlayer()); }
    @EventHandler public void onQuit(PlayerQuitEvent e) { UUID id=e.getPlayer().getUniqueId(); minedBlocks.remove(id); recentOres.remove(id); recentOreTimes.remove(id); debrisTimes.remove(id); diamondTimes.remove(id); data.set("live."+id+".active",false); data.set("live."+id+".link",null); save(); }
    private void applyVanishVisibility(Player joining) {
        for (UUID id:new HashSet<>(vanished)) {
            Player hidden=Bukkit.getPlayer(id);
            if (hidden!=null && !joining.equals(hidden)) joining.hidePlayer(plugin,hidden);
        }
        if (vanished.contains(joining.getUniqueId())) for (Player other:Bukkit.getOnlinePlayers()) if (!other.equals(joining)) other.hidePlayer(plugin,joining);
    }
    private void save() { try { data.save(file); } catch(IOException ex) { plugin.getLogger().severe("Could not save staff features: "+ex.getMessage()); } }
    @Override public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (command.getName().equalsIgnoreCase("role") && args.length==1) return List.of("list","create","add","remove").stream().filter(x->x.startsWith(args[0].toLowerCase(Locale.ROOT))).toList();
        if (command.getName().equalsIgnoreCase("role") && args.length==2 && (args[0].equalsIgnoreCase("add")||args[0].equalsIgnoreCase("remove"))) return Bukkit.getOnlinePlayers().stream().map(Player::getName).filter(x->x.toLowerCase(Locale.ROOT).startsWith(args[1].toLowerCase(Locale.ROOT))).toList();
        if (command.getName().equalsIgnoreCase("role") && args.length==3) { ConfigurationSection sec=data.getConfigurationSection("roles"); return sec==null?List.of():sec.getKeys(false).stream().filter(x->x.startsWith(args[2].toLowerCase(Locale.ROOT))).toList(); }
        if (command.getName().equalsIgnoreCase("suslist") && args.length==1) {
            List<String> choices = new ArrayList<>(List.of("alerts", "history", "review", "note", "reload", "tp", "reports"));
            ConfigurationSection suspects = data.getConfigurationSection("suspects");
            if (suspects != null) for (String id : suspects.getKeys(false)) choices.add(suspects.getString(id + ".name", id));
            return choices.stream().filter(x -> x.toLowerCase(Locale.ROOT).startsWith(args[0].toLowerCase(Locale.ROOT))).toList();
        }
        if (command.getName().equalsIgnoreCase("suslist") && args.length==2 && args[0].equalsIgnoreCase("tp")) return Bukkit.getOnlinePlayers().stream().map(Player::getName).filter(x->x.toLowerCase(Locale.ROOT).startsWith(args[1].toLowerCase(Locale.ROOT))).toList();
        if (command.getName().equalsIgnoreCase("suslist") && args.length==2 && List.of("history","review","note").contains(args[0].toLowerCase(Locale.ROOT))) {
            ConfigurationSection all = data.getConfigurationSection("suspects");
            if (all == null) return List.of();
            return all.getKeys(false).stream().map(id -> all.getString(id + ".name", id))
                .filter(x -> x.toLowerCase(Locale.ROOT).startsWith(args[1].toLowerCase(Locale.ROOT))).toList();
        }
        if (command.getName().equalsIgnoreCase("live") && args.length==1) return List.of("on","off");
        return Collections.emptyList();
    }
}
