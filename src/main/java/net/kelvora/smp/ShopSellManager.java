package net.kelvora.smp;

import java.text.DecimalFormat;
import java.util.*;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.*;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

final class ShopSellManager implements Listener {
    private static final String MAIN = "KELVORA  /  SHOP";
    private static final String SELL = "KELVORA  /  SELL";
    private static final String CATEGORY = "KELVORA  /  ";
    private static final DecimalFormat MONEY = new DecimalFormat("#,##0.##");
    private final JavaPlugin plugin;
    private final NamespacedKey actionKey;
    private Object economy;

    ShopSellManager(JavaPlugin plugin) {
        this.plugin = plugin;
        this.actionKey = new NamespacedKey(plugin, "shop-sell-action");
    }

    void register() {
        if (Bukkit.getPluginManager().isPluginEnabled("Vault")) {
            try {
                Class<?> economyType = Class.forName("net.milkbowl.vault.economy.Economy", true,
                    Bukkit.getPluginManager().getPlugin("Vault").getClass().getClassLoader());
                org.bukkit.plugin.RegisteredServiceProvider<?> registration =
                    Bukkit.getServicesManager().getRegistration((Class) economyType);
                if (registration != null) economy = registration.getProvider();
            } catch (ReflectiveOperationException | LinkageError ex) {
                plugin.getLogger().warning("Vault economy API unavailable; shop and sell money features are disabled.");
            }
        }
        if (plugin.getCommand("shop") != null) plugin.getCommand("shop").setExecutor((sender, command, label, args) -> {
            if (!(sender instanceof Player p)) { sender.sendMessage("Use this command in-game."); return true; }
            if (!p.hasPermission("kelvora.shop")) { p.sendMessage(Component.text("You do not have permission.", NamedTextColor.RED)); return true; }
            openShop(p); return true;
        });
        if (plugin.getCommand("sell") != null) plugin.getCommand("sell").setExecutor((sender, command, label, args) -> {
            if (!(sender instanceof Player p)) { sender.sendMessage("Use this command in-game."); return true; }
            if (!p.hasPermission("kelvora.sell")) { p.sendMessage(Component.text("You do not have permission.", NamedTextColor.RED)); return true; }
            openSell(p); return true;
        });
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    private void openShop(Player p) {
        Inventory inv = Bukkit.createInventory(null, 54, Component.text(MAIN, NamedTextColor.DARK_GREEN));
        fill(inv, Material.GRAY_STAINED_GLASS_PANE, " ");
        put(inv, 10, Material.STONE, "Blocks", "cat:blocks", "Building blocks and materials");
        put(inv, 12, Material.COOKED_BEEF, "Food", "cat:food", "Food and useful supplies");
        put(inv, 14, Material.IRON_SWORD, "PvP", "cat:pvp", "Combat equipment");
        put(inv, 16, Material.REDSTONE, "Redstone", "cat:redstone", "Redstone components");
        put(inv, 31, Material.WHEAT, "Farm", "cat:farm", "Farming and mob drops");
        put(inv, 49, Material.BARRIER, "Close", "close", "Close the shop");
        p.openInventory(inv);
    }

    private void openCategory(Player p, String cat) {
        Inventory inv = Bukkit.createInventory(null, 54, Component.text(CATEGORY + cat.toUpperCase(Locale.ROOT), NamedTextColor.DARK_GREEN));
        List<Material> items = categoryItems(cat);
        int slot = 0;
        for (Material m : items) {
            if (slot >= 45 || !m.isItem()) continue;
            double sell = price(m);
            double buy = sell * 2.5;
            ItemStack item = new ItemStack(m);
            ItemMeta meta = item.getItemMeta();
            meta.displayName(Component.text(pretty(m), NamedTextColor.WHITE));
            meta.lore(List.of(Component.text("Buy: $" + MONEY.format(buy), NamedTextColor.GREEN),
                Component.text("Sell value: $" + MONEY.format(sell), NamedTextColor.AQUA),
                Component.text("Left-click: buy 1", NamedTextColor.GRAY),
                Component.text("Shift-click: buy 64", NamedTextColor.GRAY)));
            meta.getPersistentDataContainer().set(actionKey, PersistentDataType.STRING, "buy:" + m.name());
            item.setItemMeta(meta);
            inv.setItem(slot++, item);
        }
        put(inv, 45, Material.ARROW, "Back", "back", "Return to categories");
        put(inv, 49, Material.EMERALD, "Balance", "balance", "Current balance");
        put(inv, 53, Material.BARRIER, "Close", "close", "Close the shop");
        p.openInventory(inv);
    }

    private List<Material> categoryItems(String cat) {
        return switch (cat) {
            case "blocks" -> mats("STONE,COBBLESTONE,DEEPSLATE,COBBLED_DEEPSLATE,DIRT,SAND,GRAVEL,OAK_LOG,SPRUCE_LOG,OAK_PLANKS,SPRUCE_PLANKS,GLASS,BRICKS,OBSIDIAN,END_STONE,BLACKSTONE,IRON_BLOCK,GOLD_BLOCK,DIAMOND_BLOCK,EMERALD_BLOCK,CHEST,BARREL,HOPPER");
            case "food" -> mats("BREAD,COOKED_BEEF,COOKED_PORKCHOP,COOKED_CHICKEN,COOKED_MUTTON,BAKED_POTATO,APPLE,GOLDEN_CARROT,GOLDEN_APPLE,COOKED_COD,COOKED_SALMON,PUMPKIN_PIE,COOKIE");
            case "pvp" -> mats("SHIELD,IRON_SWORD,DIAMOND_SWORD,IRON_AXE,DIAMOND_AXE,BOW,CROSSBOW,ARROW,TRIDENT,IRON_HELMET,IRON_CHESTPLATE,IRON_LEGGINGS,IRON_BOOTS,DIAMOND_HELMET,DIAMOND_CHESTPLATE,DIAMOND_LEGGINGS,DIAMOND_BOOTS,ENDER_PEARL");
            case "redstone" -> mats("REDSTONE,REDSTONE_BLOCK,REPEATER,COMPARATOR,OBSERVER,PISTON,STICKY_PISTON,DISPENSER,DROPPER,HOPPER,LEVER,STONE_BUTTON,REDSTONE_TORCH,TARGET");
            default -> mats("WHEAT,WHEAT_SEEDS,CARROT,POTATO,BEETROOT,BEETROOT_SEEDS,SUGAR_CANE,PUMPKIN,MELON,CACTUS,BAMBOO,EGG,BONE,BONE_MEAL,ROTTEN_FLESH,STRING,SPIDER_EYE,GUNPOWDER,SLIME_BALL,ENDER_PEARL,BLAZE_ROD,GHAST_TEAR,MAGMA_CREAM,PHANTOM_MEMBRANE,KELP,SEA_PICKLE,PINK_PETALS");
        };
    }

    private List<Material> mats(String csv) {
        List<Material> out = new ArrayList<>();
        for (String s : csv.split(",")) { Material m = Material.matchMaterial(s); if (m != null) out.add(m); }
        return out;
    }

    private void openSell(Player p) {
        Inventory inv = Bukkit.createInventory(null, 54, Component.text(SELL, NamedTextColor.DARK_GREEN));
        put(inv, 45, Material.BARRIER, "Cancel / Return Items", "cancel", "Close and return all deposited items");
        put(inv, 49, Material.GOLD_INGOT, "Calculate Total", "total", "Show the value of sellable items");
        put(inv, 53, Material.LIME_CONCRETE, "Sell Deposited Items", "confirm", "Sell everything sellable in the chest");
        p.openInventory(inv);
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        String title = stripColor(e.getView().getTitle());
        if (title.equals(MAIN)) {
            e.setCancelled(true);
            if (!(e.getWhoClicked() instanceof Player p) || e.getClickedInventory() != e.getView().getTopInventory()) return;
            ItemStack clicked = e.getCurrentItem();
            String action = action(clicked);
            if (action == null) return;
            if (action.startsWith("cat:")) openCategory(p, action.substring(4));
            else if (action.equals("close")) p.closeInventory();
            return;
        }
        if (title.startsWith(CATEGORY)) {
            e.setCancelled(true);
            if (!(e.getWhoClicked() instanceof Player p) || e.getClickedInventory() != e.getView().getTopInventory()) return;
            String action = action(e.getCurrentItem());
            if (action == null) return;
            if (action.equals("back")) openShop(p);
            else if (action.equals("close")) p.closeInventory();
            else if (action.equals("balance")) tellBalance(p);
            else if (action.startsWith("buy:")) buy(p, Material.matchMaterial(action.substring(4)), e.isShiftClick() ? 64 : 1);
            return;
        }
        if (!title.equals(SELL)) return;
        int raw = e.getRawSlot();
        if (raw >= 45 && raw < 54) {
            e.setCancelled(true);
            if (!(e.getWhoClicked() instanceof Player p)) return;
            String action = action(e.getCurrentItem());
            if ("cancel".equals(action)) p.closeInventory();
            else if ("total".equals(action)) tellSellTotal(p, e.getView().getTopInventory());
            else if ("confirm".equals(action)) confirmSell(p, e.getView().getTopInventory());
            return;
        }
        if (e.getClickedInventory() == e.getView().getBottomInventory() && e.isShiftClick()) {
            e.setCancelled(true);
            ItemStack stack = e.getCurrentItem();
            if (stack == null || stack.getType().isAir()) return;
            Inventory top = e.getView().getTopInventory();
            int remaining = stack.getAmount();
            for (int i = 0; i < 45 && remaining > 0; i++) {
                ItemStack existing = top.getItem(i);
                if (existing == null || existing.getType().isAir()) {
                    int moved = Math.min(remaining, stack.getMaxStackSize());
                    ItemStack placed = stack.clone(); placed.setAmount(moved); top.setItem(i, placed);
                    remaining -= moved;
                } else if (existing.isSimilar(stack) && existing.getAmount() < existing.getMaxStackSize()) {
                    int moved = Math.min(remaining, existing.getMaxStackSize() - existing.getAmount());
                    existing.setAmount(existing.getAmount() + moved);
                    remaining -= moved;
                }
            }
            int moved = stack.getAmount() - remaining;
            if (remaining == 0) e.getClickedInventory().setItem(e.getSlot(), null);
            else if (moved > 0) stack.setAmount(remaining);
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        String title = e.getView().getTitle();
        if (title.equals(MAIN) || title.startsWith(CATEGORY)) { e.setCancelled(true); return; }
        if (title.equals(SELL) && e.getRawSlots().stream().anyMatch(s -> s >= 45 && s < 54)) e.setCancelled(true);
    }

    @EventHandler
    public void onClose(InventoryCloseEvent e) {
        if (!stripColor(e.getView().getTitle()).equals(SELL)) return;
        Inventory top = e.getView().getTopInventory();
        Player p = (Player)e.getPlayer();
        for (int i=0;i<45;i++) {
            ItemStack item = top.getItem(i);
            if (item == null || item.getType().isAir()) continue;
            Map<Integer, ItemStack> leftovers = p.getInventory().addItem(item);
            leftovers.values().forEach(drop -> p.getWorld().dropItemNaturally(p.getLocation(), drop));
            top.setItem(i, null);
        }
    }

    private double balance(Player player) {
        Object value = economyCall("getBalance", player);
        return value instanceof Number ? ((Number) value).doubleValue() : 0.0;
    }

    private Object economyCall(String method, Player player, Object... args) {
        if (economy == null) return null;
        try {
            if (args.length == 0) {
                return economy.getClass().getMethod(method, org.bukkit.OfflinePlayer.class).invoke(economy, player);
            }
            return economy.getClass().getMethod(method, org.bukkit.OfflinePlayer.class, double.class)
                .invoke(economy, player, ((Number) args[0]).doubleValue());
        } catch (ReflectiveOperationException | LinkageError ex) {
            plugin.getLogger().warning("Economy operation failed: " + method);
            return null;
        }
    }

    private void buy(Player p, Material m, int amount) {
        if (m == null) return;
        if (economy == null) { p.sendMessage(Component.text("Shop is unavailable: install Vault and an economy provider.", NamedTextColor.RED)); return; }
        double unit = price(m) * 2.5;
        int qty = Math.min(amount, m.getMaxStackSize());
        double cost = unit * qty;
        if (economy.getBalance(p) + 0.00001 < cost) { p.sendMessage(Component.text("Not enough money. Cost: $" + MONEY.format(cost), NamedTextColor.RED)); return; }
        if (!p.getInventory().addItem(new ItemStack(m, qty)).isEmpty()) { p.sendMessage(Component.text("Make room in your inventory first.", NamedTextColor.RED)); return; }
        economyCall("withdrawPlayer", p, cost);
        p.playSound(p.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.5f, 1.2f);
        p.sendMessage(Component.text("Bought " + qty + "x " + pretty(m) + " for $" + MONEY.format(cost), NamedTextColor.GREEN));
    }

    private void confirmSell(Player p, Inventory inv) {
        if (economy == null) { p.sendMessage(Component.text("Selling is unavailable: install Vault and an economy provider.", NamedTextColor.RED)); return; }
        double total = 0; int sold = 0;
        for (int i=0;i<45;i++) {
            ItemStack item = inv.getItem(i);
            if (item == null || item.getType().isAir()) continue;
            double unit = price(item.getType());
            if (unit <= 0) continue;
            total += unit * item.getAmount(); sold += item.getAmount(); inv.setItem(i, null);
        }
        if (sold == 0) { p.sendMessage(Component.text("Put items into the chest first.", NamedTextColor.RED)); return; }
        economyCall("depositPlayer", p, total);
        p.sendMessage(Component.text("Sold " + sold + " items for $" + MONEY.format(total), NamedTextColor.GREEN));
        p.playSound(p.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.6f, 1.0f);
        p.closeInventory();
    }

    private void tellSellTotal(Player p, Inventory inv) {
        double total = 0; int count = 0;
        for (int i=0;i<45;i++) { ItemStack item=inv.getItem(i); if(item!=null&&!item.getType().isAir()){total+=price(item.getType())*item.getAmount();count+=item.getAmount();} }
        p.sendMessage(Component.text("Sell chest: " + count + " items worth $" + MONEY.format(total), NamedTextColor.GOLD));
    }

    private double price(Material m) {
        if (m == null || m.isAir()) return 0;
        double value = plugin.getConfig().getDouble("prices." + m.name(), 0);
        return value > 0 && Double.isFinite(value) ? value : 0;
    }

    private void tellBalance(Player p) {
        if (economy == null) p.sendMessage(Component.text("Economy unavailable: Vault provider not found.", NamedTextColor.RED));
        else p.sendMessage(Component.text("Balance: $" + MONEY.format(economy.getBalance(p)), NamedTextColor.GREEN));
    }

    private void put(Inventory inv, int slot, Material m, String label, String action, String lore) {
        ItemStack item = new ItemStack(m);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(label, NamedTextColor.WHITE));
        meta.lore(List.of(Component.text(lore, NamedTextColor.GRAY)));
        meta.getPersistentDataContainer().set(actionKey, PersistentDataType.STRING, action);
        item.setItemMeta(meta);
        inv.setItem(slot, item);
    }

    private void fill(Inventory inv, Material material, String name) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(name, NamedTextColor.GRAY));
        item.setItemMeta(meta);
        for (int i=0;i<inv.getSize();i++) inv.setItem(i,item);
    }

    private String action(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return null;
        return item.getItemMeta().getPersistentDataContainer().get(actionKey, PersistentDataType.STRING);
    }

    private String stripColor(String title) {
    return title.replaceAll("(?i)§[0-9A-FK-OR]", "");
}

private String pretty(Material m) {
        String[] words=m.name().toLowerCase(Locale.ROOT).split("_");
        StringBuilder b=new StringBuilder();
        for(String word:words){if(b.length()>0)b.append(' ');b.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));}
        return b.toString();
    }
}
