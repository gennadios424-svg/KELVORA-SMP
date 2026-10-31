package net.kelvora.smp;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.CreatureSpawner;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Vector;

/** Persistent Donut-style item storage for spawners awarded by the Spawner Crate. */
final class SpawnerManager implements Listener {
    private static final String TITLE = "KELVORA // SPAWNER";
    private final JavaPlugin plugin;
    private final NamespacedKey crateKey, managedKey, bonesKey, bowsKey, arrowsKey;
    private final Random random = new Random();

    SpawnerManager(JavaPlugin plugin) {
        this.plugin = plugin;
        crateKey = new NamespacedKey(plugin, "spawner-reward-entity");
        managedKey = new NamespacedKey(plugin, "kelvora-storage-spawner");
        bonesKey = new NamespacedKey(plugin, "stored-bones");
        bowsKey = new NamespacedKey(plugin, "stored-bows");
        arrowsKey = new NamespacedKey(plugin, "stored-arrows");
    }

    void register() {
        Bukkit.getPluginManager().registerEvents(this, plugin);
        long ticks = Math.max(1L, plugin.getConfig().getLong("spawners.interval-seconds", 25L)) * 20L;
        Bukkit.getScheduler().runTaskTimer(plugin, this::generateForLoadedSpawners, ticks, ticks);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSpawnerPlace(BlockPlaceEvent event) {
        ItemStack hand = event.getItemInHand();
        if (hand == null || !hand.hasItemMeta()) return;
        String mob = hand.getItemMeta().getPersistentDataContainer().get(crateKey, PersistentDataType.STRING);
        if (mob == null) return;
        Block placed = event.getBlockPlaced();
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (placed.getType() != Material.SPAWNER || !(placed.getState() instanceof CreatureSpawner spawner)) return;
            try { spawner.setSpawnedType(org.bukkit.entity.EntityType.valueOf(mob)); }
            catch (IllegalArgumentException ex) { plugin.getLogger().warning("Unknown crate spawner type: " + mob); return; }
            PersistentDataContainer pdc = spawner.getPersistentDataContainer();
            pdc.set(managedKey, PersistentDataType.BYTE, (byte) 1);
            pdc.set(bonesKey, PersistentDataType.INTEGER, 0);
            pdc.set(bowsKey, PersistentDataType.INTEGER, 0);
            pdc.set(arrowsKey, PersistentDataType.INTEGER, 0);
            spawner.update(true, false);
        });
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onSpawnerInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getClickedBlock() == null) return;
        Block block = event.getClickedBlock();
        if (block.getType() != Material.SPAWNER || !(block.getState() instanceof CreatureSpawner spawner)) return;
        if (!spawner.getPersistentDataContainer().has(managedKey, PersistentDataType.BYTE)) return;
        event.setCancelled(true);
        openMenu(event.getPlayer(), block.getLocation());
    }

    private void openMenu(Player player, Location location) {
        if (!(location.getBlock().getState() instanceof CreatureSpawner spawner)) return;
        PersistentDataContainer pdc = spawner.getPersistentDataContainer();
        SpawnerMenu holder = new SpawnerMenu(location.clone());
        Inventory inv = Bukkit.createInventory(holder, 27, Component.text(TITLE, NamedTextColor.DARK_GREEN));
        holder.inventory = inv;
        ItemStack filler = named(Material.GRAY_STAINED_GLASS_PANE, " ", List.of());
        for (int i = 0; i < 27; i++) inv.setItem(i, filler);
        inv.setItem(11, named(Material.BONE, "&fBones: &a" + count(pdc, bonesKey), List.of("&7Click to drop all stored bones", "&8Drops in front of you")));
        inv.setItem(13, named(Material.BOW, "&fBows: &a" + count(pdc, bowsKey), List.of("&7Click to drop all stored bows", "&8Drops in front of you")));
        inv.setItem(15, named(Material.ARROW, "&fArrows: &a" + count(pdc, arrowsKey), List.of("&7Click to drop all stored arrows", "&8Drops in front of you")));
        inv.setItem(22, named(Material.BARRIER, "&cClose", List.of()));
        player.openInventory(inv);
    }

    @EventHandler
    public void onMenuClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof SpawnerMenu menu)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player) || event.getClickedInventory() != event.getView().getTopInventory()) return;
        int slot = event.getRawSlot();
        if (slot == 22) { player.closeInventory(); return; }
        NamespacedKey key;
        Material material;
        if (slot == 11) { key = bonesKey; material = Material.BONE; }
        else if (slot == 13) { key = bowsKey; material = Material.BOW; }
        else if (slot == 15) { key = arrowsKey; material = Material.ARROW; }
        else return;
        if (!(menu.location.getBlock().getState() instanceof CreatureSpawner spawner)) {
            player.closeInventory();
            player.sendMessage(Component.text("That spawner is no longer available.", NamedTextColor.RED));
            return;
        }
        PersistentDataContainer pdc = spawner.getPersistentDataContainer();
        int amount = count(pdc, key);
        if (amount <= 0) return;
        pdc.set(key, PersistentDataType.INTEGER, 0);
        if (!spawner.update(true, false)) {
            player.sendMessage(Component.text("Could not update spawner storage. Try again.", NamedTextColor.RED));
            return;
        }
        dropInFront(player, material, amount);
        player.playSound(player.getLocation(), org.bukkit.Sound.ENTITY_ITEM_PICKUP, 0.7f, 1.2f);
        openMenu(player, menu.location);
    }

    @EventHandler
    public void onMenuDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof SpawnerMenu) event.setCancelled(true);
    }

    private void generateForLoadedSpawners() {
        int min = Math.max(1, plugin.getConfig().getInt("spawners.minimum-items", 2));
        int max = Math.max(min, plugin.getConfig().getInt("spawners.maximum-items", 4));
        boolean bows = plugin.getConfig().getBoolean("spawners.bows-enabled", true);
        boolean arrows = plugin.getConfig().getBoolean("spawners.arrows-enabled", true);
        List<Material> choices = new ArrayList<>();
        choices.add(Material.BONE);
        if (bows) choices.add(Material.BOW);
        if (arrows) choices.add(Material.ARROW);
        for (org.bukkit.World world : Bukkit.getWorlds()) {
            for (org.bukkit.Chunk chunk : world.getLoadedChunks()) {
                for (BlockState tile : chunk.getTileEntities()) {
                    if (!(tile instanceof CreatureSpawner spawner)) continue;
                    PersistentDataContainer pdc = spawner.getPersistentDataContainer();
                    if (!pdc.has(managedKey, PersistentDataType.BYTE)) continue;
                    Material chosen = choices.get(random.nextInt(choices.size()));
                    int amount = min + random.nextInt(max - min + 1);
                    NamespacedKey key = chosen == Material.BONE ? bonesKey : chosen == Material.BOW ? bowsKey : arrowsKey;
                    int current = count(pdc, key);
                    pdc.set(key, PersistentDataType.INTEGER, current > Integer.MAX_VALUE - amount ? Integer.MAX_VALUE : current + amount);
                    spawner.update(true, false);
                }
            }
        }
    }

    private int count(PersistentDataContainer pdc, NamespacedKey key) {
        return Math.max(0, pdc.getOrDefault(key, PersistentDataType.INTEGER, 0));
    }

    private void dropInFront(Player player, Material material, int amount) {
        Vector direction = player.getLocation().getDirection().setY(0).normalize();
        if (direction.lengthSquared() < 0.01) direction = new Vector(0, 0, 1);
        Location dropAt = player.getLocation().clone().add(0, 1.0, 0).add(direction.clone().multiply(0.8));
        while (amount > 0) {
            int stack = Math.min(amount, material.getMaxStackSize());
            Item dropped = player.getWorld().dropItem(dropAt, new ItemStack(material, stack));
            dropped.setVelocity(direction.clone().multiply(0.25).setY(0.12));
            amount -= stack;
        }
    }

    private ItemStack named(Material material, String title, List<String> lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(ChatColor.translateAlternateColorCodes('&', title)));
        if (!lore.isEmpty()) meta.lore(lore.stream().map(line -> Component.text(ChatColor.translateAlternateColorCodes('&', line))).toList());
        item.setItemMeta(meta);
        return item;
    }

    private static final class SpawnerMenu implements InventoryHolder {
        private final Location location;
        private Inventory inventory;
        private SpawnerMenu(Location location) { this.location = location; }
        @Override public Inventory getInventory() { return inventory; }
    }
}
