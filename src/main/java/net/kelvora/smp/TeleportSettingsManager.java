package net.kelvora.smp;

import java.io.File;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

final class TeleportSettingsManager implements Listener {
    private static final String SETTINGS_TITLE = "KELVORA // SETTINGS";
    private static final long REQUEST_LIFETIME_MS = 60_000L;
    private final JavaPlugin plugin;
    private final BountyCombatManager combat;
    private final NamespacedKey actionKey;
    private final Map<UUID, Request> pending = new HashMap<>();
    private final Map<UUID, Boolean> allowTpa = new HashMap<>();
    private final Map<UUID, Boolean> allowTpahere = new HashMap<>();
    private final File file;
    private final YamlConfiguration data;

    TeleportSettingsManager(JavaPlugin plugin, BountyCombatManager combat) {
        this.plugin = plugin;
        this.combat = combat;
        this.actionKey = new NamespacedKey(plugin, "settings-action");
        this.file = new File(plugin.getDataFolder(), "player-settings.yml");
        this.data = YamlConfiguration.loadConfiguration(file);
        loadSettings();
    }

    void register() {
        Bukkit.getPluginManager().registerEvents(this, plugin);
        bind("settings", this::settingsCommand);
        bind("tpa", (sender, command, label, args) -> requestCommand(sender, args, false));
        bind("tpahere", (sender, command, label, args) -> requestCommand(sender, args, true));
        bind("tpaccept", (sender, command, label, args) -> acceptCommand(sender));
        bind("tpdeny", (sender, command, label, args) -> denyCommand(sender));
    }

    private void bind(String name, CommandExecutor executor) {
        PluginCommand command = plugin.getCommand(name);
        if (command != null) command.setExecutor(executor);
    }

    private boolean settingsCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) { sender.sendMessage("Use this command in-game."); return true; }
        if (!player.hasPermission("kelvora.settings")) { player.sendMessage(ChatColor.RED + "You do not have permission."); return true; }
        openSettings(player);
        return true;
    }

    private void openSettings(Player player) {
        Inventory inventory = Bukkit.createInventory(null, 27, Component.text(SETTINGS_TITLE, NamedTextColor.DARK_GREEN));
        refreshSettings(player, inventory);
        player.openInventory(inventory);
    }

    private void refreshSettings(Player player, Inventory inventory) {
        inventory.clear();
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            if (slot < 9 || slot >= 18) inventory.setItem(slot, named(Material.GRAY_STAINED_GLASS_PANE, " ", null));
        }
        boolean tpa = allowTpa.getOrDefault(player.getUniqueId(), true);
        boolean here = allowTpahere.getOrDefault(player.getUniqueId(), true);
        inventory.setItem(11, named(tpa ? Material.LIME_DYE : Material.GRAY_DYE,
            "Allow /tpa requests: " + (tpa ? "ON" : "OFF"), "allow-tpa"));
        inventory.setItem(15, named(here ? Material.LIME_DYE : Material.GRAY_DYE,
            "Allow /tpahere requests: " + (here ? "ON" : "OFF"), "allow-tpahere"));
        inventory.setItem(22, named(Material.BARRIER, "Close", "close"));
    }

    private ItemStack named(Material material, String title, String action) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(title));
        if (action != null) meta.getPersistentDataContainer().set(actionKey, PersistentDataType.STRING, action);
        item.setItemMeta(meta);
        return item;
    }

    private boolean requestCommand(CommandSender sender, String[] args, boolean here) {
        if (!(sender instanceof Player requester)) { sender.sendMessage("Use this command in-game."); return true; }
        if (!requester.hasPermission("kelvora.teleport.requests")) { requester.sendMessage(ChatColor.RED + "You do not have permission."); return true; }
        if (args.length != 1) { requester.sendMessage(ChatColor.YELLOW + "Usage: /" + (here ? "tpahere" : "tpa") + " <player>"); return true; }
        Player target = Bukkit.getPlayerExact(args[0]);
        if (target == null) { requester.sendMessage(ChatColor.RED + "That player is not online."); return true; }
        if (target.equals(requester)) { requester.sendMessage(ChatColor.RED + "You cannot send a teleport request to yourself."); return true; }
        if (combat != null && (combat.isInCombat(requester) || combat.isInCombat(target))) {
            requester.sendMessage(ChatColor.RED + "Teleport requests are unavailable while either player is in combat."); return true;
        }
        UUID targetId = target.getUniqueId();
        boolean allowed = here ? allowTpahere.getOrDefault(targetId, true) : allowTpa.getOrDefault(targetId, true);
        if (!allowed) { requester.sendMessage(ChatColor.RED + "That player has disabled this type of teleport request."); return true; }
        Request existing = pending.get(targetId);
        if (existing != null && existing.expiresAt > System.currentTimeMillis()) {
            requester.sendMessage(ChatColor.YELLOW + "That player already has a pending teleport request."); return true;
        }
        Request request = new Request(requester.getUniqueId(), targetId, here, System.currentTimeMillis() + REQUEST_LIFETIME_MS);
        pending.put(targetId, request);
        requester.sendMessage(Component.text("Teleport request sent to " + target.getName() + ". Expires in 60 seconds.", NamedTextColor.GREEN));
        target.sendMessage(Component.text(requester.getName() + (here ? " wants you to teleport to them." : " wants to teleport to you."), NamedTextColor.AQUA));
        target.sendMessage(Component.text("Use /tpaccept or /tpdeny.", NamedTextColor.YELLOW));
        Bukkit.getScheduler().runTaskLater(plugin, () -> pending.remove(targetId, request), 20L * 60L);
        return true;
    }

    private boolean acceptCommand(CommandSender sender) {
        if (!(sender instanceof Player receiver)) { sender.sendMessage("Use this command in-game."); return true; }
        Request request = pending.remove(receiver.getUniqueId());
        if (request == null || request.expiresAt < System.currentTimeMillis()) {
            receiver.sendMessage(ChatColor.RED + "You have no pending teleport requests."); return true;
        }
        Player requester = Bukkit.getPlayer(request.requester);
        if (requester == null || !requester.isOnline()) {
            receiver.sendMessage(ChatColor.RED + "The requesting player is no longer online."); return true;
        }
        if (combat != null && (combat.isInCombat(receiver) || combat.isInCombat(requester))) {
            receiver.sendMessage(ChatColor.RED + "Teleporting is blocked while either player is in combat."); return true;
        }
        Player teleporter = request.here ? receiver : requester;
        Player destination = request.here ? requester : receiver;
        Location target = destination.getLocation().clone();
        receiver.sendMessage(Component.text("Teleport request accepted.", NamedTextColor.GREEN));
        requester.sendMessage(Component.text(receiver.getName() + " accepted your teleport request.", NamedTextColor.GREEN));
        teleporter.teleportAsync(target).whenComplete((success, error) -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (error != null || !Boolean.TRUE.equals(success)) {
                if (teleporter.isOnline()) teleporter.sendMessage(Component.text("Teleport failed.", NamedTextColor.RED));
            } else if (teleporter.isOnline()) {
                teleporter.playSound(teleporter.getLocation(), Sound.ENTITY_ENDERMAN_TELEPORT, 0.5f, 1.2f);
            }
        }));
        return true;
    }

    private boolean denyCommand(CommandSender sender) {
        if (!(sender instanceof Player player)) { sender.sendMessage("Use this command in-game."); return true; }
        Request request = pending.remove(player.getUniqueId());
        if (request == null) { player.sendMessage(ChatColor.RED + "You have no pending teleport requests."); return true; }
        player.sendMessage(Component.text("Teleport request denied.", NamedTextColor.YELLOW));
        Player requester = Bukkit.getPlayer(request.requester);
        if (requester != null) requester.sendMessage(Component.text(player.getName() + " denied your teleport request.", NamedTextColor.RED));
        return true;
    }

    @EventHandler
    public void onSettingsClick(InventoryClickEvent event) {
        if (!SETTINGS_TITLE.equals(ChatColor.stripColor(event.getView().getTitle()))) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player) || event.getClickedInventory() != event.getView().getTopInventory()) return;
        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || !clicked.hasItemMeta()) return;
        String action = clicked.getItemMeta().getPersistentDataContainer().get(actionKey, PersistentDataType.STRING);
        if (action == null) return;
        UUID id = player.getUniqueId();
        switch (action) {
            case "allow-tpa" -> {
                allowTpa.put(id, !allowTpa.getOrDefault(id, true));
                data.set("players." + id + ".allow-tpa", allowTpa.get(id));
                saveSettings();
                refreshSettings(player, event.getView().getTopInventory());
            }
            case "allow-tpahere" -> {
                allowTpahere.put(id, !allowTpahere.getOrDefault(id, true));
                data.set("players." + id + ".allow-tpahere", allowTpahere.get(id));
                saveSettings();
                refreshSettings(player, event.getView().getTopInventory());
            }
            case "close" -> player.closeInventory();
        }
    }

    @EventHandler
    public void onSettingsDrag(InventoryDragEvent event) {
        if (SETTINGS_TITLE.equals(ChatColor.stripColor(event.getView().getTitle()))) event.setCancelled(true);
    }

    private void loadSettings() {
        if (!file.exists()) return;
        var players = data.getConfigurationSection("players");
        if (players == null) return;
        for (String raw : players.getKeys(false)) {
            try {
                UUID id = UUID.fromString(raw);
                allowTpa.put(id, data.getBoolean("players." + raw + ".allow-tpa", true));
                allowTpahere.put(id, data.getBoolean("players." + raw + ".allow-tpahere", true));
            } catch (IllegalArgumentException ignored) { }
        }
    }

    private void saveSettings() {
        try { data.save(file); }
        catch (java.io.IOException ex) { plugin.getLogger().warning("Could not save player-settings.yml: " + ex.getMessage()); }
    }

    private record Request(UUID requester, UUID receiver, boolean here, long expiresAt) { }
}
