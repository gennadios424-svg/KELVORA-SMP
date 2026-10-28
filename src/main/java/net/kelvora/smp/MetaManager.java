package net.kelvora.smp;

import java.util.List;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.plugin.java.JavaPlugin;

final class MetaManager implements Listener, CommandExecutor {
    private static final String TITLE = "KELVORA // META";
    private final JavaPlugin plugin;

    MetaManager(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    void register() {
        Bukkit.getPluginManager().registerEvents(this, plugin);
        if (plugin.getCommand("meta") != null) {
            plugin.getCommand("meta").setExecutor(this);
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Use this command in-game.");
            return true;
        }
        Inventory inventory = Bukkit.createInventory(null, 27, Component.text(TITLE, NamedTextColor.GREEN));
        ItemStack pane = named(Material.GREEN_STAINED_GLASS_PANE, " ");
        for (int slot = 0; slot < 27; slot++) {
            if (slot < 9 || slot >= 18 || slot % 9 == 0 || slot % 9 == 8) {
                inventory.setItem(slot, pane.clone());
            }
        }
        inventory.setItem(11, pricedItem(Material.KELP, "Kelp", 200));
        inventory.setItem(13, pricedItem(Material.PINK_PETALS, "Pink Petals", 150));
        inventory.setItem(15, pricedItem(Material.SEA_PICKLE, "Sea Pickle", 125));
        inventory.setItem(22, named(Material.BARRIER, "Close"));
        player.openInventory(inventory);
        return true;
    }

    private ItemStack pricedItem(Material material, String displayName, int price) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(displayName, NamedTextColor.WHITE));
        meta.lore(List.of(
            Component.text("Worth: $" + price + " each", NamedTextColor.GREEN),
            Component.text("Stack of 64: $" + (price * 64), NamedTextColor.AQUA),
            Component.text("Value shown from /worth", NamedTextColor.GRAY)
        ));
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack named(Material material, String name) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(name, NamedTextColor.GRAY));
        item.setItemMeta(meta);
        return item;
    }

    private boolean isMetaView(org.bukkit.inventory.InventoryView view) {
        return TITLE.equals(ChatColor.stripColor(view.getTitle()));
    }

    @EventHandler
    public void onMetaClick(InventoryClickEvent event) {
        if (!isMetaView(event.getView())) return;
        event.setCancelled(true);
        if (event.getWhoClicked() instanceof Player player
                && event.getClickedInventory() == event.getView().getTopInventory()
                && event.getCurrentItem() != null
                && event.getCurrentItem().getType() == Material.BARRIER) {
            player.closeInventory();
        }
    }

    @EventHandler
    public void onMetaDrag(InventoryDragEvent event) {
        if (isMetaView(event.getView())) event.setCancelled(true);
    }
}
