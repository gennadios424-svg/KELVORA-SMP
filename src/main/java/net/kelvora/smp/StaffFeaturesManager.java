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
        if (!data.contains("media.follower-count")) data.set("media.follower-count", "Set by staff");
        if (!data.contains("media.content")) data.set("media.content", "Minecraft content, no stolen clips");
        if (!data.contains("media.activity")) data.set("media.activity", "Consistent recent uploads or streams");
        if (!data.contains("media.requirements")) data.set("media.requirements", List.of("Minimum follower / view requirement: set by staff", "Content quality and activity are reviewed"));
        if (!data.contains("media.application")) data.set("media.application", "Open a ticket in the Kelvora Discord");
        if (!data.contains("media.discord-url")) data.set("media.discord-url", "https://discord.gg/gUt2uGcwe");
        save();
        Bukkit.getPluginManager().registerEvents(this, plugin);
        for (String command : List.of("live", "media", "role", "suslist", "vanish")) {
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
        return sender.isOp() || sender.hasPermission("kelvora.suslist") || sender.hasPermission("kelvora.staff.mod") || sender.hasPermission("kelvora.staff.admin") || (sender instanceof Player p && (hasRole(p, "moderator") || hasRole(p, "mod") || hasRole(p, "manager") || hasRole(p, "admin") || hasRole(p, "owner")));
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
        List<String> labels = new ArrayList<>();
        for (String role : roles(player)) if (roleExists(role)) labels.add(roleColor(role) + "&l[" + roleLabel(role) + "]&r");
        if (isStaffOrMediaGroup(player, "staff") && labels.stream().noneMatch(x -> x.replace("&l", "").replace("&r", "").equalsIgnoreCase("&c[Staff]"))) labels.add("&c&l[Staff]&r");
        if (isStaffOrMediaGroup(player, "media") && labels.stream().noneMatch(x -> x.replace("&l", "").replace("&r", "").equalsIgnoreCase("&d[Media]"))) labels.add("&d&l[Media]&r");
        return String.join(" ", labels);
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
            mediaLore.add(ChatColor.GRAY + "Application: " + data.getString("media.application", "Open a Discord ticket"));
            mediaLore.add(ChatColor.YELLOW + "Media is never granted automatically.");
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
        if (cmd.equals("suslist")) {
            if (!moderator(sender)) { sender.sendMessage(ChatColor.RED + "Only moderators and above can use /suslist."); return true; }
            ConfigurationSection suspects=data.getConfigurationSection("suspects");
            if (args.length == 1 && args[0].equalsIgnoreCase("reload")) { sender.sendMessage(ChatColor.GREEN + "Suslist uses the current configuration and saved evidence."); return true; }
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
        String path="suspects."+p.getUniqueId();
        data.set(path+".name",p.getName()); data.set(path+".reason",reason); data.set(path+".world",ore.getWorld().getName());
        data.set(path+".x",ore.getX()); data.set(path+".y",ore.getY()); data.set(path+".z",ore.getZ());
        data.set(path+".tool",p.getInventory().getItemInMainHand().getType().name());
        data.set(path+".time",System.currentTimeMillis()); data.set(path+".count",reason.replaceAll("[^0-9]", "")); save();
        for (Player staff:Bukkit.getOnlinePlayers()) if (moderator(staff)) staff.sendMessage(ChatColor.RED+"[SUS] "+p.getName()+": "+reason+" — /suslist tp "+p.getName());
    }
    private void openSuspectGui(CommandSender sender, ConfigurationSection suspects) {
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
            for (String uuidText:suspects.getKeys(false)) {
                if(slot>=45) break;
                UUID id; try { id=UUID.fromString(uuidText); } catch(IllegalArgumentException ex) { continue; }
                String name=suspects.getString(uuidText+".name",uuidText);
                ItemStack head=new ItemStack(Material.PLAYER_HEAD); SkullMeta meta=(SkullMeta)head.getItemMeta();
                Player online=Bukkit.getPlayer(id); if(online!=null) meta.setOwningPlayer(online);
                String reason=suspects.getString(uuidText+".reason","Suspicious mining pattern");
                meta.setDisplayName((reason.contains("ancient debris")?ChatColor.LIGHT_PURPLE:reason.contains("diamond")?ChatColor.AQUA:ChatColor.RED).toString()+ChatColor.BOLD+name);
                meta.setLore(List.of(ChatColor.GRAY+reason,ChatColor.GRAY+"World: "+suspects.getString(uuidText+".world","unknown"),ChatColor.GRAY+"Coords: "+suspects.getInt(uuidText+".x")+", "+suspects.getInt(uuidText+".y")+", "+suspects.getInt(uuidText+".z"),ChatColor.GRAY+"Tool: "+suspects.getString(uuidText+".tool","unknown"),ChatColor.YELLOW+"Left-click: teleport in vanish",ChatColor.YELLOW+"Right-click: inspect details"));
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
        if (command.getName().equalsIgnoreCase("suslist") && args.length==1) return List.of("tp");
        if (command.getName().equalsIgnoreCase("suslist") && args.length==2 && args[0].equalsIgnoreCase("tp")) return Bukkit.getOnlinePlayers().stream().map(Player::getName).filter(x->x.toLowerCase(Locale.ROOT).startsWith(args[1].toLowerCase(Locale.ROOT))).toList();
        if (command.getName().equalsIgnoreCase("live") && args.length==1) return List.of("on","off");
        return Collections.emptyList();
    }
}
