package net.kelvora.smp;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

final class ShopManager implements Listener {
    private static final Component HOME_TITLE = Component.text("KELVORA  /  SHOP", NamedTextColor.GREEN);
    private static final String[] CATEGORIES = {"Blocks", "Food", "PvP", "Redstone", "Nether Shop", "End Shop"};
    private final JavaPlugin plugin;
    private final NamespacedKey actionKey;
    private final Map<Material, Double> prices = new LinkedHashMap<>();

    ShopManager(JavaPlugin plugin) {
        this.plugin = plugin;
        this.actionKey = new NamespacedKey(plugin, "shop-action");
        loadCatalog();
    }

    void register() {
        if (plugin.getCommand("shop") != null) plugin.getCommand("shop").setExecutor((sender, command, label, args) -> {
            if (!(sender instanceof Player player)) { sender.sendMessage("This command can only be used in-game."); return true; }
            if (!player.hasPermission("kelvora.shop")) { player.sendMessage(Component.text("You do not have permission.", NamedTextColor.RED)); return true; }
            openHome(player); return true;
        });
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    private void loadCatalog() {
        // All shop stock is intentionally curated; rare grind items and decorative farming drops are not stocked.
        add(Material.STONE, 8); add(Material.COBBLESTONE, 7); add(Material.DEEPSLATE, 9);
        add(Material.OAK_LOG, 45); add(Material.SPRUCE_LOG, 45); add(Material.OAK_PLANKS, 12);
        add(Material.GLASS, 20); add(Material.SAND, 8); add(Material.GRAVEL, 8);
        add(Material.OBSIDIAN, 80); add(Material.CRYING_OBSIDIAN, 150); add(Material.CHEST, 100);
        add(Material.BREAD, 15); add(Material.COOKED_BEEF, 30); add(Material.COOKED_CHICKEN, 24);
        add(Material.GOLDEN_CARROT, 55); add(Material.GOLDEN_APPLE, 300); add(Material.COOKED_PORKCHOP, 28);
        add(Material.END_CRYSTAL, 750); add(Material.OBSIDIAN, 80); add(Material.RESPAWN_ANCHOR, 650);
        add(Material.GLOWSTONE, 35); add(Material.TOTEM_OF_UNDYING, 18000);
        add(Material.REDSTONE, 25); add(Material.REPEATER, 75); add(Material.COMPARATOR, 90);
        add(Material.PISTON, 100); add(Material.STICKY_PISTON, 180); add(Material.OBSERVER, 110);
        add(Material.HOPPER, 250); add(Material.DISPENSER, 100); add(Material.DROPPER, 85);
        add(Material.LEVER, 12); add(Material.REDSTONE_TORCH, 15); add(Material.COMPARATOR, 90);
        add(Material.NETHERRACK, 10); add(Material.SOUL_SAND, 30); add(Material.BLAZE_ROD, 120);
        add(Material.MAGMA_BLOCK, 35); add(Material.NETHER_BRICK, 35); add(Material.WARPED_STEM, 60);
        add(Material.END_STONE, 25); add(Material.PURPUR_BLOCK, 75); add(Material.CHORUS_FRUIT, 35);
        add(Material.ENDER_PEARL, 90); add(Material.SHULKER_SHELL, 3000); add(Material.PHANTOM_MEMBRANE, 150);
    }

    private void add(Material material, double configuredPrice) {
        double worth = plugin.getConfig().getDouble("prices." + material.name(), 0.0);
        // Where /worth has a sellable reference, shop price must be strictly higher.
        prices.put(material, Math.max(configuredPrice, worth >= 0 ? worth + Math.max(1.0, worth * 0.25) : configuredPrice));
    }

    private Economy economy() {
        RegisteredServiceProvider<Economy> provider = Bukkit.getServicesManager().getRegistration(Economy.class);
        return provider == null ? null : provider.getProvider();
    }

    private void openHome(Player player) {
        Inventory inv = Bukkit.createInventory(null, 27, HOME_TITLE);
        Material[] icons = {Material.BRICKS, Material.COOKED_BEEF, Material.END_CRYSTAL, Material.REDSTONE, Material.NETHERRACK, Material.END_STONE};
        for (int i=0;i<CATEGORIES.length;i++) {
            ItemStack item = new ItemStack(icons[i]); ItemMeta meta=item.getItemMeta();
            meta.displayName(Component.text(CATEGORIES[i], NamedTextColor.GREEN));
            meta.lore(List.of(Component.text("Click to browse stock", NamedTextColor.GRAY)));
            meta.getPersistentDataContainer().set(actionKey, PersistentDataType.STRING, "category:" + i);
            item.setItemMeta(meta); inv.setItem(10+i, item);
        }
        player.openInventory(inv);
    }

    private void openCategory(Player player, int category) {
        Inventory inv = Bukkit.createInventory(null, 54, Component.text("KELVORA  /  " + CATEGORIES[category].toUpperCase(Locale.ROOT), NamedTextColor.GREEN));
        int slot=0;
        for (Map.Entry<Material,Double> entry: prices.entrySet()) {
            if (categoryOf(entry.getKey()) != category || slot>=45) continue;
            ItemStack item=new ItemStack(entry.getKey()); ItemMeta meta=item.getItemMeta();
            meta.displayName(Component.text(pretty(entry.getKey()), NamedTextColor.WHITE));
            meta.lore(List.of(Component.text("Price: $" + money(entry.getValue()), NamedTextColor.GREEN),
                Component.text("Click to buy 1", NamedTextColor.GRAY),
                Component.text("Shift-click to buy a stack", NamedTextColor.GRAY)));
            meta.getPersistentDataContainer().set(actionKey, PersistentDataType.STRING, "buy:" + entry.getKey().name());
            item.setItemMeta(meta); inv.setItem(slot++,item);
        }
        ItemStack back=new ItemStack(Material.ARROW); ItemMeta bm=back.getItemMeta();
        bm.displayName(Component.text("Back",NamedTextColor.YELLOW));
        bm.getPersistentDataContainer().set(actionKey,PersistentDataType.STRING,"back");back.setItemMeta(bm);inv.setItem(49,back);
        player.openInventory(inv);
    }

    private int categoryOf(Material m) {
        String n=m.name();
        if (List.of("BREAD","COOKED_BEEF","COOKED_CHICKEN","GOLDEN_CARROT","GOLDEN_APPLE","COOKED_PORKCHOP").contains(n)) return 1;
        if (List.of("END_CRYSTAL","OBSIDIAN","CRYING_OBSIDIAN","RESPAWN_ANCHOR","GLOWSTONE","TOTEM_OF_UNDYING").contains(n)) return 2;
        if (List.of("REDSTONE","REPEATER","COMPARATOR","PISTON","STICKY_PISTON","OBSERVER","HOPPER","DISPENSER","DROPPER","LEVER","REDSTONE_TORCH").contains(n)) return 3;
        if (List.of("NETHERRACK","SOUL_SAND","BLAZE_ROD","MAGMA_BLOCK","NETHER_BRICK","WARPED_STEM").contains(n)) return 4;
        if (List.of("END_STONE","PURPUR_BLOCK","CHORUS_FRUIT","ENDER_PEARL","SHULKER_SHELL","PHANTOM_MEMBRANE").contains(n)) return 5;
        return 0;
    }

    @EventHandler public void onClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player player)) return;
        String title=e.getView().getTitle();
        if (!title.startsWith("KELVORA  /  SHOP")) return;
        e.setCancelled(true);
        if (e.getClickedInventory()!=e.getView().getTopInventory() || e.getCurrentItem()==null || !e.getCurrentItem().hasItemMeta()) return;
        String action=e.getCurrentItem().getItemMeta().getPersistentDataContainer().get(actionKey,PersistentDataType.STRING);
        if(action==null)return;
        if(action.startsWith("category:")){openCategory(player,Integer.parseInt(action.substring(9)));return;}
        if(action.equals("back")){openHome(player);return;}
        if(!action.startsWith("buy:"))return;
        Material material=Material.matchMaterial(action.substring(4)); if(material==null)return;
        Economy eco=economy(); if(eco==null){player.sendMessage(Component.text("Money economy is unavailable. Please contact staff.",NamedTextColor.RED));return;}
        int amount=e.isShiftClick()?material.getMaxStackSize():1;
        double price=prices.getOrDefault(material,0.0)*amount;
        if(eco.getBalance(player)<price){player.sendMessage(Component.text("You need $" + money(price) + " to buy that.",NamedTextColor.RED));return;}
        ItemStack purchase=new ItemStack(material,amount);
        if(player.getInventory().firstEmpty()==-1 && !canFit(player,purchase)){player.sendMessage(Component.text("Your inventory is full.",NamedTextColor.RED));return;}
        if(!eco.withdrawPlayer(player,price).transactionSuccess()){player.sendMessage(Component.text("Purchase failed; your Money was not taken.",NamedTextColor.RED));return;}
        Map<Integer,ItemStack> overflow=player.getInventory().addItem(purchase);
        if(!overflow.isEmpty()){eco.depositPlayer(player,price);player.sendMessage(Component.text("Purchase cancelled: inventory full.",NamedTextColor.RED));return;}
        player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP,0.5f,1.2f);
        player.sendMessage(Component.text("Purchased " + amount + "x " + pretty(material) + " for $" + money(price) + ".",NamedTextColor.GREEN);
    }
    private boolean canFit(Player p, ItemStack item) {
        int remaining=item.getAmount();
        for(ItemStack slot:p.getInventory().getStorageContents()){
            if(slot==null){remaining-=item.getMaxStackSize(); if(remaining<=0)return true;}
            else if(slot.isSimilar(item))remaining-=Math.max(0,slot.getMaxStackSize()-slot.getAmount());
        }
        return remaining<=0;
    }
    @EventHandler public void onDrag(InventoryDragEvent e) {
        if(e.getView().getTitle().startsWith("KELVORA  /  SHOP") && e.getRawSlots().stream().anyMatch(s->s<e.getView().getTopInventory().getSize()))e.setCancelled(true);
    }
    private static String pretty(Material m){String[] a=m.name().toLowerCase(Locale.ROOT).split("_");StringBuilder b=new StringBuilder();for(String w:a){if(b.length()>0)b.append(' ');b.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1));}return b.toString();}
    private static String money(double n){return n==Math.rint(n)?String.format(Locale.US,"%.0f",n):String.format(Locale.US,"%.2f",n);}
}