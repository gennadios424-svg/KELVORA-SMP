package net.kelvora.smp;

import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.model.group.Group;
import net.luckperms.api.model.user.User;
import net.luckperms.api.node.types.InheritanceNode;
import net.luckperms.api.node.types.PrefixNode;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.OfflinePlayer;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import io.papermc.paper.event.player.AsyncChatEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.RegisteredServiceProvider;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.format.NamedTextColor;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

final class RankKitManager implements Listener {
    private static final List<String> PROGRESSION = List.of("i_sprout", "verdant", "emerald", "sovereign", "ascendant");
    private static final Map<String, String> LABELS = Map.ofEntries(
        Map.entry("i_sprout", "I. Sprout"), Map.entry("verdant", "Verdant"),
        Map.entry("emerald", "Emerald"), Map.entry("sovereign", "Sovereign"),
        Map.entry("ascendant", "Ascendant"), Map.entry("helper", "Helper"),
        Map.entry("moderator", "Moderator"), Map.entry("mod", "Moderator"),
        Map.entry("admin", "Admin"), Map.entry("owner", "Owner"), Map.entry("creator", "Creator"),
        Map.entry("default", "I. Sprout"), Map.entry("member", "I. Sprout")
    );
    private final KelvoraSMP plugin;
    private final NamespacedKey actionKey;
    private final File cooldownFile;
    private final File rankCacheFile;
    private final YamlConfiguration cooldowns;
    private final YamlConfiguration rankCache;
    private final Set<UUID> claiming = ConcurrentHashMap.newKeySet();
    private final Map<UUID, String> currentViews = new HashMap<>();
    private LuckPerms luckPerms;

    RankKitManager(KelvoraSMP plugin) {
        this.plugin = plugin;
        actionKey = new NamespacedKey(plugin, "rankkit-action");
        cooldownFile = new File(plugin.getDataFolder(), "kit-cooldowns.yml");
        rankCacheFile = new File(plugin.getDataFolder(), "rank-cache.yml");
        cooldowns = YamlConfiguration.loadConfiguration(cooldownFile);
        rankCache = YamlConfiguration.loadConfiguration(rankCacheFile);
    }

    void register() {
        if (Bukkit.getPluginManager().isPluginEnabled("LuckPerms")) {
            try { luckPerms = LuckPermsProvider.get(); }
            catch (IllegalStateException ex) { plugin.getLogger().warning("RankKit: LuckPerms API unavailable; rank management is disabled."); }
        }
        Bukkit.getPluginManager().registerEvents(this, plugin);
        if (plugin.getCommand("rank") != null) plugin.getCommand("rank").setExecutor(this::rankCommand);
        if (plugin.getCommand("rankup") != null) plugin.getCommand("rankup").setExecutor(this::rankupCommand);
        if (plugin.getCommand("kit") != null) plugin.getCommand("kit").setExecutor(this::kitCommand);
        if (plugin.getCommand("kits") != null) plugin.getCommand("kits").setExecutor(this::kitCommand);
        cacheOnlineRanks();
        if (luckPerms != null && plugin.getConfig().getBoolean("ranks.bootstrap-groups", true)) {
            Bukkit.getScheduler().runTaskAsynchronously(plugin, this::bootstrapGroups);
        }
    }

    private void bootstrapGroups() {
        for (String key : List.of("i_sprout","verdant","emerald","sovereign","ascendant","helper","moderator","admin","owner","creator")) {
            try {
                Group group = luckPerms.getGroupManager().getGroup(key);
                if (group == null) {
                    try { group = luckPerms.getGroupManager().createAndLoadGroup(key).join(); }
                    catch (Exception ex) { plugin.getLogger().warning("Could not create LuckPerms group '" + key + "': " + ex.getMessage()); continue; }
                }
                String prefix = prefixFor(key);
                boolean changed = group.data().add(PrefixNode.builder(prefix, priorityFor(key)).build()).wasSuccessful();
                if (changed) luckPerms.getGroupManager().saveGroup(group).join();
            } catch (Exception ex) {
                plugin.getLogger().warning("Could not configure LuckPerms group '" + key + "': " + ex.getMessage());
            }
        }
    }

    private int priorityFor(String key) {
        return switch (key) {
            case "owner" -> 100; case "admin" -> 90; case "moderator" -> 80; case "helper" -> 70;
            case "creator" -> 60; case "ascendant" -> 50; case "sovereign" -> 40;
            case "emerald" -> 30; case "verdant" -> 20; default -> 10;
        };
    }

    private String prefixFor(String key) {
        String label = LABELS.getOrDefault(key, key);
        String color = plugin.getConfig().getString("ranks." + key + ".color", defaultColor(key));
        return color + "[" + label + "] ";
    }

    private String defaultColor(String key) {
        return switch (key) {
            case "i_sprout" -> "&7"; case "verdant", "helper" -> "&a";
            case "emerald" -> "&3"; case "sovereign" -> "&3";
            case "ascendant" -> "&6"; case "moderator" -> "&9";
            case "admin" -> "&5"; case "owner" -> "&c"; case "creator" -> "&d"; default -> "&7";
        };
    }

    private boolean rankCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) { sender.sendMessage("Use this command in-game."); return true; }
        if (!player.hasPermission("kelvora.rank.view")) { player.sendMessage(Component.text("You do not have permission.", NamedTextColor.RED)); return true; }
        openRanks(player);
        return true;
    }

    private boolean rankupCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) { sender.sendMessage("Use this command in-game."); return true; }
        if (!player.hasPermission("kelvora.rankup")) { player.sendMessage(Component.text("You do not have permission.", NamedTextColor.RED)); return true; }
        if (args.length != 0) { player.sendMessage(Component.text("Usage: /rankup", NamedTextColor.RED)); return true; }
        String current = progressionRank(player.getUniqueId());
        int index = PROGRESSION.indexOf(current);
        if (index < 0 || index >= PROGRESSION.size() - 1) {
            player.sendMessage(Component.text(index < 0 ? "Your progression rank is not configured." : "You have reached the highest progression rank.", NamedTextColor.RED));
            return true;
        }
        String next = PROGRESSION.get(index + 1);
        double cost = plugin.getConfig().getDouble("rank-up.costs." + next, -1);
        if (cost < 0) {
            player.sendMessage(Component.text("Rank-up is not configured yet. Ask an administrator to set rank-up.costs." + next + " in config.yml.", NamedTextColor.RED));
            return true;
        }
        Object economy = economyProvider();
        if (economy == null) { player.sendMessage(Component.text("Money economy is unavailable; no charge was made.", NamedTextColor.RED)); return true; }
        try {
            double balance = ((Number)economy.getClass().getMethod("getBalance", OfflinePlayer.class).invoke(economy, player)).doubleValue();
            if (balance < cost) { player.sendMessage(Component.text("You need $" + money(cost) + " Money to upgrade.", NamedTextColor.RED)); return true; }
            // Never charge until LuckPerms confirms that the rank change can be saved.
            if (!setProgressionRank(player, next)) { player.sendMessage(Component.text("Rank upgrade failed; no Money was charged.", NamedTextColor.RED)); return true; }
            Object response = economy.getClass().getMethod("withdrawPlayer", OfflinePlayer.class, double.class).invoke(economy, player, cost);
            boolean success = (boolean) response.getClass().getMethod("transactionSuccess").invoke(response);
            if (!success) {
                setProgressionRank(player, current);
                player.sendMessage(Component.text("Money transaction failed; your previous rank was restored.", NamedTextColor.RED));
                return true;
            }
            player.sendMessage(Component.text("Rank upgraded to " + LABELS.get(next) + "!", NamedTextColor.GREEN));
            cacheRank(player.getUniqueId(), next);
        } catch (ReflectiveOperationException ex) {
            player.sendMessage(Component.text("Money transaction failed safely; contact an administrator.", NamedTextColor.RED));
            plugin.getLogger().warning("Rank-up economy call failed: " + ex.getMessage());
        }
        return true;
    }

    private boolean kitCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) { sender.sendMessage("Use this command in-game."); return true; }
        if (!player.hasPermission("kelvora.kit.use")) { player.sendMessage(Component.text("You do not have permission.", NamedTextColor.RED)); return true; }
        if (args.length == 0) { openKits(player); return true; }
        if (args.length == 2 && args[0].equalsIgnoreCase("preview")) {
            String key = normalize(args[1]);
            if (!plugin.getConfig().isConfigurationSection("kits." + key)) { player.sendMessage(Component.text("Unknown kit.", NamedTextColor.RED)); return true; }
            showPreview(player, key);
            return true;
        }
        if (args.length == 1) { claimKit(player, normalize(args[0])); return true; }
        player.sendMessage(Component.text("Usage: /kit [name|preview <name>]", NamedTextColor.RED));
        return true;
    }

    private void openRanks(Player player) {
        Inventory inv = Bukkit.createInventory(null, 45, Component.text("Kelvora | Ranks", NamedTextColor.DARK_GREEN));
        int[] slots = {10,11,12,13,14};
        for (int i=0;i<PROGRESSION.size();i++) {
            String key=PROGRESSION.get(i);
            String desc=plugin.getConfig().getString("ranks." + key + ".description", "Survival progression rank");
            put(inv, slots[i], rankMaterial(i), prefixFor(key) + LABELS.get(key), List.of(desc, "Progression " + (i+1) + "/5", "Use /rankup to upgrade"), "rank:" + key);
        }
        put(inv, 28, Material.LIME_DYE, "&aHelper", List.of("Answers questions and helps players", "Assigned by authorized staff"), "info");
        put(inv, 29, Material.BLUE_DYE, "&9Moderator", List.of("Handles chat and rule enforcement", "Assigned by authorized staff"), "info");
        put(inv, 30, Material.PURPLE_DYE, "&5Admin", List.of("Manages server operations", "Assigned by authorized staff"), "info");
        put(inv, 31, Material.RED_DYE, "&cOwner", List.of("Server leadership", "Assigned by authorized staff"), "info");
        put(inv, 33, Material.MAGENTA_DYE, "&dCreator", List.of("For YouTubers, TikTokers and streamers", "Assigned by authorized staff"), "info");
        put(inv, 40, Material.BARRIER, "&cClose", List.of("Close menu"), "close");
        player.openInventory(inv);
    }

    private Material rankMaterial(int i) {
        return switch(i) { case 0 -> Material.OAK_SAPLING; case 1 -> Material.MOSS_BLOCK; case 2 -> Material.EMERALD;
            case 3 -> Material.PRISMARINE_SHARD; default -> Material.GOLD_INGOT; };
    }

    private void openKits(Player player) {
        Inventory inv = Bukkit.createInventory(null, 27, Component.text("Kelvora | Kits", NamedTextColor.DARK_GREEN));
        int[] slots={10,11,12,13,14};
        for (int i=0;i<PROGRESSION.size();i++) {
            String key=PROGRESSION.get(i);
            String kit=plugin.getConfig().getString("kits." + key + ".display", LABELS.get(key) + " Kit");
            boolean allowed=canClaimRank(player,key);
            long readyAt=cooldowns.getLong(player.getUniqueId()+"."+key,0);
            List<String> lore=new ArrayList<>();
            lore.add("Required rank: " + LABELS.get(key));
            lore.add(plugin.getConfig().getString("kits." + key + ".summary","Combat loadout"));
            lore.add(allowed ? (readyAt>System.currentTimeMillis() ? "Cooldown: "+duration(readyAt-System.currentTimeMillis()) : "Ready to claim") : "Locked — rank required");
            put(inv,slots[i],allowed?Material.CHEST:Material.BARRIER,(allowed?"&a":"&c")+kit,lore,"kit:"+key);
        }
        put(inv,22,Material.BOOK,"&ePreview kit",List.of("Use /kit preview <name>"),"info");
        put(inv,26,Material.BARRIER,"&cClose",List.of("Close menu"),"close");
        player.openInventory(inv);
    }

    private void showPreview(Player player, String key) {
        ConfigurationSection kit=plugin.getConfig().getConfigurationSection("kits."+key);
        if (kit==null) return;
        player.sendMessage(Component.text("Kit preview: "+kit.getString("display",LABELS.getOrDefault(key,key)), NamedTextColor.GREEN));
        for (Map<?,?> item : kit.getMapList("items")) {
            player.sendMessage(Component.text("- "+(item.containsKey("material") ? item.get("material") : "STONE")+" x"+(item.containsKey("amount") ? item.get("amount") : 1), NamedTextColor.GRAY));
        }
    }

    private void claimKit(Player player, String key) {
        if (!PROGRESSION.contains(key) || !plugin.getConfig().isConfigurationSection("kits."+key)) {
            player.sendMessage(Component.text("Unknown kit.", NamedTextColor.RED)); return;
        }
        if (!canClaimRank(player,key)) { player.sendMessage(Component.text("Your rank cannot claim this kit.", NamedTextColor.RED)); return; }
        long now=System.currentTimeMillis(), readyAt=cooldowns.getLong(player.getUniqueId()+"."+key,0);
        if (readyAt>now) { player.sendMessage(Component.text("Kit available in "+duration(readyAt-now)+".", NamedTextColor.RED)); return; }
        if (!claiming.add(player.getUniqueId())) { player.sendMessage(Component.text("Your kit claim is already processing.", NamedTextColor.RED)); return; }
        try {
            ConfigurationSection kit=plugin.getConfig().getConfigurationSection("kits."+key);
            List<ItemStack> items=buildItems(kit);
            if (items.isEmpty()) { player.sendMessage(Component.text("This kit has no valid items configured.", NamedTextColor.RED)); return; }
            // Preflight delivery capacity. Do not grant or start cooldown if there is not room.
            int empty=0; for (ItemStack item:player.getInventory().getStorageContents()) if (item==null || item.getType().isAir()) empty++;
            int needed=0; for(ItemStack item:items) needed += (item.getAmount()+item.getMaxStackSize()-1)/item.getMaxStackSize();
            if (empty<needed) { player.sendMessage(Component.text("Please empty at least "+(needed-empty)+" inventory slot(s) before claiming this kit. No items were given.", NamedTextColor.RED)); return; }
            for(ItemStack item:items) player.getInventory().addItem(item).values().forEach(left -> player.getWorld().dropItemNaturally(player.getLocation(),left));
            long cooldown=plugin.getConfig().getLong("kits."+key+".cooldown-seconds",86400);
            cooldowns.set(player.getUniqueId()+"."+key, now+Math.max(0,cooldown)*1000L);
            saveYaml(cooldowns,cooldownFile);
            player.sendMessage(Component.text("Claimed "+kit.getString("display",LABELS.get(key)+" Kit")+".",NamedTextColor.GREEN));
            player.playSound(player.getLocation(),Sound.ENTITY_PLAYER_LEVELUP,0.35f,1.2f);
        } catch (Exception ex) {
            player.sendMessage(Component.text("Kit could not be processed. Contact an administrator.",NamedTextColor.RED));
            plugin.getLogger().warning("Kit claim error for "+key+": "+ex.getMessage());
        } finally { claiming.remove(player.getUniqueId()); }
    }

    private List<ItemStack> buildItems(ConfigurationSection kit) {
        List<ItemStack> result=new ArrayList<>();
        for(Map<?,?> row:kit.getMapList("items")) {
            Object raw=row.get("material"); if(raw==null) continue;
            Material material=Material.matchMaterial(String.valueOf(raw));
            if(material==null || !material.isItem()) { plugin.getLogger().warning("Invalid kit material: "+raw); continue; }
            int amount=1;
            try { amount=Integer.parseInt(String.valueOf((row.containsKey("amount") ? row.get("amount") : 1))); } catch(NumberFormatException ignored) {}
            if(amount<1) continue;
            int remaining=amount;
            while(remaining>0) {
                int count=Math.min(remaining,material.getMaxStackSize());
                ItemStack stack=new ItemStack(material,count);
                Object enchants=row.get("enchants");
                if(enchants instanceof Map<?,?> map) for(var e:map.entrySet()) {
                    Enchantment enchant=Enchantment.getByName(String.valueOf(e.getKey()).toUpperCase(Locale.ROOT));
                    int level=1; try { level=Integer.parseInt(String.valueOf(e.getValue())); } catch(NumberFormatException ignored) {}
                    if(enchant!=null && level>=1 && level<=enchant.getMaxLevel()) stack.addUnsafeEnchantment(enchant,level);
                }
                Object display=row.get("name");
                if(display!=null) { ItemMeta meta=stack.getItemMeta(); meta.displayName(Component.text(ChatColor.stripColor(ChatColor.translateAlternateColorCodes('&',String.valueOf(display))))); stack.setItemMeta(meta); }
                result.add(stack); remaining-=count;
            }
        }
        return result;
    }

    private boolean canClaimRank(Player player,String kit) {
        int playerRank=PROGRESSION.indexOf(progressionRank(player.getUniqueId()));
        int required=PROGRESSION.indexOf(kit);
        return playerRank>=required && (playerRank==required || plugin.getConfig().getBoolean("kits.allow-lower-rank-kits",false));
    }

    private String progressionRank(UUID id) {
        String cached=rankCache.getString(id.toString());
        if(luckPerms!=null) {
            User user=luckPerms.getUserManager().getUser(id);
            if(user!=null) {
                String primary=user.getPrimaryGroup().toLowerCase(Locale.ROOT);
                if(PROGRESSION.contains(primary)) { cacheRank(id,primary); return primary; }
                for(int i=PROGRESSION.size()-1;i>=0;i--) {
                    String candidate = PROGRESSION.get(i);
                    if(user.getNodes().stream().anyMatch(n -> n instanceof InheritanceNode in && in.getGroupName().equalsIgnoreCase(candidate))) {
                        cacheRank(id,candidate); return candidate;
                    }
                }
            }
        }
        return cached==null ? "i_sprout" : cached;
    }

    private boolean setProgressionRank(Player player,String target) {
        if(luckPerms==null) return false;
        User user=luckPerms.getUserManager().getUser(player.getUniqueId());
        if(user==null) return false;
        String oldPrimary = user.getPrimaryGroup() == null ? "" : user.getPrimaryGroup().toLowerCase(Locale.ROOT);
        for(String key:PROGRESSION) user.data().remove(InheritanceNode.builder(key).build());
        user.data().add(InheritanceNode.builder(target).build());
        if (PROGRESSION.contains(oldPrimary)) user.setPrimaryGroup(target);
        try { luckPerms.getUserManager().saveUser(user).join(); cacheRank(player.getUniqueId(),target); return true; }
        catch(Exception ex) { return false; }
    }

    private void cacheOnlineRanks() { for(Player p:Bukkit.getOnlinePlayers()) cacheRank(p.getUniqueId(),progressionRank(p.getUniqueId())); }
    private void cacheRank(UUID id,String key) { rankCache.set(id.toString(),key); saveYaml(rankCache,rankCacheFile); }
    private void saveYaml(YamlConfiguration yaml,File file) {
        try { if(!plugin.getDataFolder().exists()) plugin.getDataFolder().mkdirs(); yaml.save(file); }
        catch(IOException ex) { plugin.getLogger().warning("Could not save "+file.getName()+": "+ex.getMessage()); }
    }
    @EventHandler public void onJoin(PlayerJoinEvent event) { cacheRank(event.getPlayer().getUniqueId(),progressionRank(event.getPlayer().getUniqueId())); }

    @EventHandler public void onChat(AsyncChatEvent event) {
        String prefix = null;
        if (luckPerms != null) {
            User user = luckPerms.getUserManager().getUser(event.getPlayer().getUniqueId());
            if (user != null) prefix = user.getCachedData().getMetaData().getPrefix();
        }
        if (prefix == null || prefix.isBlank()) prefix = "&7[I. Sprout] ";
        final Component rankPrefix = LegacyComponentSerializer.legacySection().deserialize(ChatColor.translateAlternateColorCodes('&', prefix));
        event.renderer((source, displayName, message, viewer) -> rankPrefix.append(displayName).append(Component.text(": ")).append(message));
    }

    @EventHandler public void onClick(InventoryClickEvent event) {
        String title=event.getView().title().toString();
        if(!title.contains("Kelvora | Kits") && !title.contains("Kelvora | Ranks")) return;
        event.setCancelled(true);
        if(!(event.getWhoClicked() instanceof Player p) || event.getClickedInventory()!=event.getView().getTopInventory()) return;
        ItemStack item=event.getCurrentItem(); if(item==null || !item.hasItemMeta()) return;
        String action=item.getItemMeta().getPersistentDataContainer().get(actionKey,PersistentDataType.STRING);
        if(action==null) return;
        if(action.equals("close")) { p.closeInventory(); return; }
        if(action.startsWith("kit:")) { claimKit(p,action.substring(4)); p.playSound(p.getLocation(),Sound.UI_BUTTON_CLICK,0.2f,1.1f); }
        else if(action.startsWith("rank:")) { p.sendMessage(Component.text("Use /rankup to see and purchase your next configured progression rank.",NamedTextColor.GREEN)); }
    }
    @EventHandler public void onDrag(InventoryDragEvent event) {
        String title=event.getView().title().toString();
        if(title.contains("Kelvora | Kits") || title.contains("Kelvora | Ranks")) event.setCancelled(true);
    }

    private void put(Inventory inv,int slot,Material material,String title,List<String> lore,String action) {
        ItemStack item=new ItemStack(material); ItemMeta meta=item.getItemMeta();
        meta.displayName(Component.text(ChatColor.stripColor(ChatColor.translateAlternateColorCodes('&',title))));
        List<Component> lines=new ArrayList<>(); for(String line:lore) lines.add(Component.text(line,NamedTextColor.GRAY));
        meta.lore(lines); meta.getPersistentDataContainer().set(actionKey,PersistentDataType.STRING,action); item.setItemMeta(meta); inv.setItem(slot,item);
    }
    private String normalize(String s) { String key = s.toLowerCase(Locale.ROOT).trim().replace(" ","_"); if (key.equals("sprout") || key.equals("i.sprout") || key.equals("i_sprout")) return "i_sprout"; return key; }
    private String duration(long millis) {
        long seconds=Math.max(0,millis/1000); long h=seconds/3600, m=(seconds%3600)/60;
        return h>0 ? h+"h "+m+"m" : m+"m "+(seconds%60)+"s";
    }
    private String money(double value) { return String.format(Locale.US,"%,.2f",value); }
    private Object economyProvider() {
        if(!Bukkit.getPluginManager().isPluginEnabled("Vault")) return null;
        try {
            Class<?> type=Class.forName("net.milkbowl.vault.economy.Economy",true,Bukkit.getPluginManager().getPlugin("Vault").getClass().getClassLoader());
            RegisteredServiceProvider<?> registration=Bukkit.getServicesManager().getRegistration((Class)type);
            return registration==null?null:registration.getProvider();
        } catch(ReflectiveOperationException|LinkageError ex) { return null; }
    }
}
