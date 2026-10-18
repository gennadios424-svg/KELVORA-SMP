package net.kelvora.smp;

import java.util.ArrayList;
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
import org.bukkit.block.ShulkerBox;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

final class SellManager implements Listener {
    private static final Component TITLE = Component.text("KELVORA  /  SELL", NamedTextColor.GREEN);
    private final JavaPlugin plugin;
    private final NamespacedKey actionKey;

    SellManager(JavaPlugin plugin) {
        this.plugin = plugin;
        this.actionKey = new NamespacedKey(plugin, "sell-action");
    }

    void register() {
        if (plugin.getCommand("sell") != null) plugin.getCommand("sell").setExecutor((sender, command, label, args) -> {
            if (!(sender instanceof Player player)) { sender.sendMessage("This command can only be used in-game."); return true; }
            if (!player.hasPermission("kelvora.sell")) { player.sendMessage(Component.text("You do not have permission.", NamedTextColor.RED)); return true; }
            open(player); return true;
        });
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    private void open(Player player) {
        Inventory inv = Bukkit.createInventory(player, 54, TITLE);
        ItemStack sell = new ItemStack(Material.LIME_STAINED_GLASS_PANE);
        ItemMeta meta = sell.getItemMeta();
        meta.displayName(Component.text("Sell Contents", NamedTextColor.GREEN));
        meta.lore(List.of(Component.text("Place items in the top 45 slots", NamedTextColor.GRAY),
                Component.text("Click here to sell everything inside", NamedTextColor.GRAY),
                Component.text("Unpriced items cancel the whole sale", NamedTextColor.RED)));
        meta.getPersistentDataContainer().set(actionKey, PersistentDataType.STRING, "sell");
        sell.setItemMeta(meta);
        inv.setItem(49, sell);
        ItemStack cancel = new ItemStack(Material.BARRIER);
        ItemMeta cm = cancel.getItemMeta();
        cm.displayName(Component.text("Cancel", NamedTextColor.RED));
        cm.getPersistentDataContainer().set(actionKey, PersistentDataType.STRING, "cancel");
        cancel.setItemMeta(cm);
        inv.setItem(53, cancel);
        player.openInventory(inv);
    }

    private Economy economy() {
        RegisteredServiceProvider<Economy> provider = Bukkit.getServicesManager().getRegistration(Economy.class);
        return provider == null ? null : provider.getProvider();
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false) public void onClick(InventoryClickEvent e) {
        String viewTitle = e.getView().getTitle().replaceAll("\\s+", " ").trim();
        if (!viewTitle.startsWith("KELVORA / SELL")) return;
        if (!(e.getWhoClicked() instanceof Player player)) { e.setCancelled(true); return; }
        if (e.getClickedInventory() == null) { e.setCancelled(true); return; }
        if (e.getClickedInventory() != e.getView().getTopInventory()) {
            if (e.isShiftClick() || e.getClick().isKeyboardClick()) e.setCancelled(true);
            return;
        }
        int slot = e.getRawSlot();
        if (slot >= 45) {
            e.setCancelled(true);
            ItemStack clicked=e.getCurrentItem();
            if(clicked==null || !clicked.hasItemMeta())return;
            String action=clicked.getItemMeta().getPersistentDataContainer().get(actionKey,PersistentDataType.STRING);
            if ("cancel".equals(action)) player.closeInventory();
            else if ("sell".equals(action)) sellAll(player,e.getView().getTopInventory());
            return;
        }
        if (e.getClick().isKeyboardClick() || e.getClick().isCreativeAction()) e.setCancelled(true);
    }

    private void sellAll(Player player, Inventory inv) {
        double total=0.0;
        List<String> missing=new ArrayList<>();
        for(int slot=0;slot<45;slot++) {
            ItemStack item=inv.getItem(slot);
            if(item==null || item.getType().isAir())continue;
            double value=valueOf(item,missing);
            if(!Double.isFinite(value))continue;
            total+=value;
        }
        if(!missing.isEmpty()) {
            player.sendMessage(Component.text("Sale cancelled. No items were removed. Unpriced contents: " + String.join(", ",missing.stream().distinct().limit(8).toList()),NamedTextColor.RED));
            return;
        }
        if(total<=0.0) { player.sendMessage(Component.text("Place items in the sell chest first.",NamedTextColor.YELLOW));return; }
        Economy eco=economy();
        if(eco==null){player.sendMessage(Component.text("Money economy is unavailable. Nothing was sold.",NamedTextColor.RED));return;}
        if(!eco.depositPlayer(player,total).transactionSuccess()){player.sendMessage(Component.text("Payout failed. Nothing was removed.",NamedTextColor.RED));return;}
        for(int slot=0;slot<45;slot++)inv.setItem(slot,null);
        player.sendMessage(Component.text("Sold the sell-chest contents for $" + money(total) + ".",NamedTextColor.GREEN));
        player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP,0.6f,1.1f);
        player.closeInventory();
    }

    private double valueOf(ItemStack stack,List<String> missing) {
        double unit=plugin.getConfig().getDouble("prices."+stack.getType().name(),-1.0);
        if(unit<0.0 || !Double.isFinite(unit)){missing.add(stack.getType().name());return Double.NaN;}
        double total=unit*stack.getAmount();
        if(stack.getItemMeta() instanceof BlockStateMeta meta && meta.getBlockState() instanceof ShulkerBox shulker) {
            for(ItemStack content:shulker.getInventory().getContents()) {
                if(content==null || content.getType().isAir())continue;
                double contentUnit=plugin.getConfig().getDouble("prices."+content.getType().name(),-1.0);
                if(contentUnit<0.0 || !Double.isFinite(contentUnit)){missing.add(content.getType().name());continue;}
                total+=contentUnit*content.getAmount();
            }
        }
        return total;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false) public void onDrag(InventoryDragEvent e) {
        if(!e.getView().getTitle().replaceAll("\\s+", " ").trim().startsWith("KELVORA / SELL"))return;
        if(e.getRawSlots().stream().anyMatch(s->s>=45))e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false) public void onClose(InventoryCloseEvent e) {
        if(!e.getView().getTitle().replaceAll("\\s+", " ").trim().startsWith("KELVORA / SELL"))return;
        Inventory inv=e.getView().getTopInventory();
        Player player=(Player)e.getPlayer();
        for(int slot=0;slot<45;slot++) {
            ItemStack item=inv.getItem(slot);
            if(item==null || item.getType().isAir())continue;
            Map<Integer,ItemStack> overflow=player.getInventory().addItem(item.clone());
            overflow.values().forEach(left->player.getWorld().dropItemNaturally(player.getLocation(),left));
            inv.setItem(slot,null);
        }
    }

    private static String money(double n){return String.format(Locale.US,"%,.2f",n).replaceAll("\\.00$","");}
}