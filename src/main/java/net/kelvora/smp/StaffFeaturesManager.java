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
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
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
    private final Set<UUID> vanished = new HashSet<>();

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
            save();
        }
        Bukkit.getPluginManager().registerEvents(this, plugin);
        for (String command : List.of("live", "role", "suslist", "vanish")) {
            if (plugin.getCommand(command) != null) {
                plugin.getCommand(command).setExecutor(this);
                plugin.getCommand(command).setTabCompleter(this);
            }
        }
        for (Player player : Bukkit.getOnlinePlayers()) applyVanishVisibility(player);
    }

    private boolean manage(CommandSender sender) {
        return sender.isOp() || sender.hasPermission("kelvora.roles.manage") || sender.hasPermission("kelvora.staff.manager");
    }
    private boolean moderator(CommandSender sender) {
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
        return p.hasPermission("group." + wanted) || p.hasPermission("kelvora.staff." + wanted);
    }
    String tabLabels(Player player) {
        List<String> labels = new ArrayList<>();
        for (String role : roles(player)) if (roleExists(role)) labels.add(ChatColor.translateAlternateColorCodes('&', roleColor(role) + "[" + roleLabel(role) + ChatColor.RESET + ChatColor.translateAlternateColorCodes('&', roleColor(role)) + "]"));
        if (isStaffOrMediaGroup(player, "staff") && labels.stream().noneMatch(x -> ChatColor.stripColor(x).equalsIgnoreCase("[Staff]"))) labels.add(ChatColor.RED + ChatColor.BOLD.toString() + "[Staff]" + ChatColor.RESET);
        if (isStaffOrMediaGroup(player, "media") && labels.stream().noneMatch(x -> ChatColor.stripColor(x).equalsIgnoreCase("[Media]"))) labels.add(ChatColor.LIGHT_PURPLE.toString() + ChatColor.BOLD + "[Media]" + ChatColor.RESET);
        return String.join(" ", labels);
    }
    ComponentHolder chatRoleLabels(Player player) { return new ComponentHolder(tabLabels(player)); }
    static final class ComponentHolder { final String text; ComponentHolder(String text) { this.text=text; } }
    String liveTabLabel(Player player) {
        if (!data.getBoolean("live." + player.getUniqueId() + ".active", false)) return "";
        String link = data.getString("live." + player.getUniqueId() + ".link", "");
        return ChatColor.RED + ChatColor.BOLD.toString() + "[LIVE]" + ChatColor.RESET + (link.isBlank() ? "" : ChatColor.GRAY + " " + link);
    }
    boolean isVanished(Player player) { return vanished.contains(player.getUniqueId()); }

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String cmd = command.getName().toLowerCase(Locale.ROOT);
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
                if (!link.matches("(?i)^https?://\\S{1,240}$")) { p.sendMessage(ChatColor.RED + "Use a full http(s) link, e.g. /live https://twitch.tv/yourname"); return true; }
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
            if (!moderator(p) && !p.hasPermission("kelvora.vanish")) { p.sendMessage(ChatColor.RED+"You do not have permission."); return true; }
            if (vanished.remove(p.getUniqueId())) { p.removePotionEffect(PotionEffectType.INVISIBILITY); for (Player other:Bukkit.getOnlinePlayers()) other.showPlayer(plugin,p); p.sendMessage(ChatColor.YELLOW+"Vanish disabled."); }
            else { vanished.add(p.getUniqueId()); applyVanishVisibility(p); p.sendMessage(ChatColor.GREEN+"Vanish enabled."); }
            return true;
        }
        if (cmd.equals("suslist")) {
            if (!moderator(sender)) { sender.sendMessage(ChatColor.RED + "Only moderators and above can use /suslist."); return true; }
            ConfigurationSection suspects=data.getConfigurationSection("suspects");
            if (args.length >= 2 && args[0].equalsIgnoreCase("tp")) {
                Player target=Bukkit.getPlayerExact(args[1]);
                if (target==null) { sender.sendMessage(ChatColor.RED+"That player is not online."); return true; }
                if (!(sender instanceof Player staff)) { sender.sendMessage("Console cannot teleport."); return true; }
                if (!vanished.contains(staff.getUniqueId())) { vanished.add(staff.getUniqueId()); applyVanishVisibility(staff); }
                staff.teleport(target.getLocation()); staff.sendMessage(ChatColor.GREEN+"Teleported to "+target.getName()+" in vanish. Check evidence before taking action."); return true;
            }
            sender.sendMessage(ChatColor.GOLD+"Suspicious mining flags:");
            if (suspects==null || suspects.getKeys(false).isEmpty()) { sender.sendMessage(ChatColor.GRAY+"No players currently flagged."); return true; }
            for (String uuidText:suspects.getKeys(false)) {
                String name=suspects.getString(uuidText+".name",uuidText);
                String reason=suspects.getString(uuidText+".reason","Suspicious mining pattern");
                sender.sendMessage(ChatColor.YELLOW+name+ChatColor.GRAY+" — "+reason+ChatColor.DARK_GRAY+" | /suslist tp "+name);
            }
            return true;
        }
        return false;
    }

    @EventHandler public void onMine(BlockBreakEvent event) {
        Player p=event.getPlayer(); UUID id=p.getUniqueId(); long now=System.currentTimeMillis();
        Deque<Long> times=minedBlocks.computeIfAbsent(id, x->new ArrayDeque<>()); times.addLast(now);
        while (!times.isEmpty() && now-times.peekFirst()>10_000L) times.removeFirst();
        if (times.size()>=45) flag(p,"Breaking "+times.size()+" blocks in 10 seconds");
        Material type=event.getBlock().getType();
        if (type==Material.ANCIENT_DEBRIS || type==Material.DIAMOND_ORE || type==Material.DEEPSLATE_DIAMOND_ORE) {
            List<String> ores=recentOres.computeIfAbsent(id,x->new ArrayList<>());
            Long last=recentOreTimes.get(id);
            if (last==null || now-last>120_000L) ores.clear();
            ores.add(type.name()); recentOreTimes.put(id,now);
            long diamonds=ores.stream().filter(o->o.contains("DIAMOND")).count();
            long debris=ores.stream().filter(o->o.equals("ANCIENT_DEBRIS")).count();
            if (diamonds>=6) flag(p,"Mined "+diamonds+" diamond ores within 2 minutes");
            if (debris>=4) flag(p,"Mined "+debris+" ancient debris within 2 minutes");
        }
    }
    private void flag(Player p,String reason) {
        String path="suspects."+p.getUniqueId();
        data.set(path+".name",p.getName()); data.set(path+".reason",reason); data.set(path+".world",p.getWorld().getName());
        data.set(path+".x",p.getLocation().getBlockX()); data.set(path+".y",p.getLocation().getBlockY()); data.set(path+".z",p.getLocation().getBlockZ());
        data.set(path+".time",System.currentTimeMillis()); save();
        for (Player staff:Bukkit.getOnlinePlayers()) if (moderator(staff)) staff.sendMessage(ChatColor.RED+"[SUS] "+p.getName()+": "+reason+" — /suslist tp "+p.getName());
    }
    @EventHandler public void onJoin(PlayerJoinEvent e) { applyVanishVisibility(e.getPlayer()); }
    @EventHandler public void onQuit(PlayerQuitEvent e) { minedBlocks.remove(e.getPlayer().getUniqueId()); recentOres.remove(e.getPlayer().getUniqueId()); recentOreTimes.remove(e.getPlayer().getUniqueId()); }
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
        if (command.getName().equalsIgnoreCase("suslist") && args.length==1) return List.of("tp");
        if (command.getName().equalsIgnoreCase("suslist") && args.length==2 && args[0].equalsIgnoreCase("tp")) return Bukkit.getOnlinePlayers().stream().map(Player::getName).filter(x->x.toLowerCase(Locale.ROOT).startsWith(args[1].toLowerCase(Locale.ROOT))).toList();
        if (command.getName().equalsIgnoreCase("live") && args.length==1) return List.of("on","off");
        return Collections.emptyList();
    }
}
