package net.kelvora.smp;

import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.List;
import java.util.Locale;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
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
import org.bukkit.plugin.java.JavaPlugin;

/** Read-only showcase backed by the same configured prices used by /worth and /sell. */
final class MetaManager implements Listener, CommandExecutor {
    private static final String TITLE = "KELVORA | META";
    private static final List<Material> SHOWCASE = List.of(Material.KELP, Material.PINK_PETALS, Material.SEA_PICKLE);
    private static final DecimalFormat PRICE = new DecimalFormat("#,##0.##", DecimalFormatSymbols.getInstance(Locale.US));
    private final JavaPlugin plugin;
    private final WorthManager worth;

    MetaManager(JavaPlugin plugin, WorthManager worth) {
        this.plugin = plugin;
        this.worth = worth;
    }

    void register() {
        if (plugin.getCommand("meta") != null) plugin.getCommand("meta").setExecutor(this);
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Use this command in-game.");
            return true;
        }
        Inventory inv = Bukkit.createInventory(null, 27, Component.text(TITLE, NamedTextColor.DARK_GREEN));
        for (int i = 0; i < SHOWCASE.size(); i++) {
            Material material = SHOWCASE.get(i);
            double price = worth.priceOf(material);
            ItemStack display = new ItemStack(material);
            ItemMeta itemMeta = display.getItemMeta();
            itemMeta.displayName(Component.text(pretty(material), NamedTextColor.GREEN));
            itemMeta.lore(List.of(
                Component.text("Current /worth value", NamedTextColor.GRAY),
                Component.text("$" + PRICE.format(price), NamedTextColor.GOLD),
                Component.text("Value updates with the server price registry", NamedTextColor.DARK_GRAY)
            ));
            display.setItemMeta(itemMeta);
            inv.setItem(new int[]{11, 13, 15}[i], display);
        }
        ItemStack frame = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta frameMeta = frame.getItemMeta();
        frameMeta.displayName(Component.text(" ", NamedTextColor.DARK_GRAY));
        frame.setItemMeta(frameMeta);
        for (int slot = 0; slot < inv.getSize(); slot++) {
            if (inv.getItem(slot) == null) inv.setItem(slot, frame.clone());
        }
        player.openInventory(inv);
        player.playSound(player.getLocation(), Sound.BLOCK_CHEST_OPEN, 0.35f, 1.15f);
        return true;
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!TITLE.equals(event.getView().getTitle())) return;
        event.setCancelled(true);
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (TITLE.equals(event.getView().getTitle())) event.setCancelled(true);
    }

    private static String pretty(Material material) {
        String[] parts = material.name().toLowerCase(Locale.ROOT).split("_");
        StringBuilder result = new StringBuilder();
        for (String part : parts) {
            if (result.length() > 0) result.append(' ');
            result.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }
        return result.toString();
    }
}
