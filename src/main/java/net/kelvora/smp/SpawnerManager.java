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
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Vector;

final class SpawnerManager implements Listener {
    private static final String TITLE = "KELVORA // SPAWNER";
    private final JavaPlugin plugin;
    private final NamespacedKey crateKey, managedKey, bonesKey, bowsKey, arrowsKey, stringKey, eyeKey, ingotKey, poppyKey;
    private final Random random = new Random();

    SpawnerManager(JavaPlugin plugin) {
        this.plugin = plugin;
        crateKey = new NamespacedKey(plugin, "spawner-reward-entity");
        managedKey = new NamespacedKey(plugin, "kelvora-storage-spawner");
        bonesKey = new NamespacedKey(plugin, "stored-bones");
        bowsKey = new NamespacedKey(plugin, "stored-bows");
        arrowsKey = new NamespacedKey(plugin, "stored-arrows");
        stringKey = new NamespacedKey(plugin, "stored-string");
        eyeKey = new NamespacedKey(plugin, "stored-spider-eyes");
        ingotKey = new NamespacedKey(plugin, "stored-iron-ingots");
        poppyKey = new NamespacedKey(plugin, "stored-poppies");
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
            try { spawner.setSpawnedType(EntityType.valueOf(mob)); }
            catch (IllegalArgumentException ex) { plugin.getLogger().warning("Unknown crate spawner type: " + mob); return; }
            PersistentDataContainer pdc = spawner.getPersistentDataContainer();
            pdc.set(managedKey, PersistentDataType.BYTE, (byte) 1);
            for (NamespacedKey key : allKeys()) if (!pdc.has(key, PersistentDataType.INTEGER)) pdc.set(key, PersistentDataType.INTEGER, 0);
            spawner.update(true, false);
        });
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onSpawnerBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        if (!(block.getState() instanceof CreatureSpawner spawner) || !isManaged(spawner)) return;
        event.setCancelled(true);
        Player player = event.getPlayer();
        EntityType type = spawner.getSpawnedType();
        int amount = event.isDropItems() && player.isSneaking() ? verticalStackSize(block, type, 64) : 1;
        List<Block> removed = new ArrayList<>();
        Block cursor = block;
        for (int i = 0; i < amount; i++) {
            if (!(cursor.getState() instanceof CreatureSpawner current) || !isManaged(current) || current.getSpawnedType() != type) break;
            removed.add(cursor);
            cursor = cursor.getRelative(0, 1, 0);
        }
        // A regular break removes only the clicked spawner. Sneak-breaking collects upward stacks.
        for (Block target : removed) target.setType(Material.AIR, false);
        if (removed.isEmpty()) return;
        ItemStack item = createSpawnerItem(type, removed.size());
        var leftovers = player.getInventory().addItem(item);
        leftovers.values().forEach(left -> block.getWorld().dropItemNaturally(block.getLocation(), left));
        player.playSound(player.getLocation(), org.bukkit.Sound.ENTITY_ITEM_PICKUP, 0.8f, 1.1f);
    }

    private int verticalStackSize(Block block, EntityType type, int limit) {
        int count = 0;
        Block cursor = block;
        while (count < limit && cursor.getState() instanceof CreatureSpawner spawner && isManaged(spawner) && spawner.getSpawnedType() == type) {
            count++;
            cursor = cursor.getRelative(0, 1, 0);
        }
        return count;
    }

    private ItemStack createSpawnerItem(EntityType type, int amount) {
        ItemStack item = new ItemStack(Material.SPAWNER, amount);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(ChatColor.GREEN + type.name().replace('_', ' ') + " Spawner"));
        meta.getPersistentDataContainer().set(crateKey, PersistentDataType.STRING, type.name());
        item.setItemMeta(meta);
        return item;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onSpawnerInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getClickedBlock() == null) return;
        Block block = event.getClickedBlock();
        if (!(block.getState() instanceof CreatureSpawner spawner) || !isManaged(spawner)) return;
        event.setCancelled(true);
        openMenu(event.getPlayer(), block.getLocation());
    }

    private boolean isManaged(CreatureSpawner spawner) {
        return spawner.getPersistentDataContainer().has(managedKey, PersistentDataType.BYTE);
    }

    private List<NamespacedKey> allKeys() {
        return List.of(bonesKey, bowsKey, arrowsKey, stringKey, eyeKey, ingotKey, poppyKey);
    }

    private List<Material> drops(EntityType type) {
        return switch (type) {
            case SKELETON -> {
                List<Material> result = new ArrayList<>(List.of(Material.BONE));
                if (plugin.getConfig().getBoolean("spawners.bows-enabled", true)) result.add(Material.BOW);
                if (plugin.getConfig().getBoolean("spawners.arrows-enabled", true)) result.add(Material.ARROW);
                yield result;
            }
            case SPIDER -> List.of(Material.STRING, Material.SPIDER_EYE);
            case IRON_GOLEM -> List.of(Material.IRON_INGOT, Material.POPPY);
            default -> List.of(Material.BONE);
        };
    }

    private NamespacedKey key(Material material) {
        return switch (material) {
            case BONE -> bonesKey;
            case BOW -> bowsKey;
            case ARROW -> arrowsKey;
            case STRING -> stringKey;
            case SPIDER_EYE -> eyeKey;
            case IRON_INGOT -> ingotKey;
            case POPPY -> poppyKey;
            default -> bonesKey;
        };
    }

    private Material icon(Material material) {
        return material;
    }

    private void openMenu(Player player, Location location) {
        if (!(location.getBlock().getState() instanceof CreatureSpawner spawner)) return;
        EntityType type = spawner.getSpawnedType();
        PersistentDataContainer pdc = spawner.getPersistentDataContainer();
        SpawnerMenu holder = new SpawnerMenu(location.clone());
        Inventory inv = Bukkit.createInventory(holder, 27, Component.text(TITLE, NamedTextColor.DARK_GREEN));
        holder.inventory = inv;
        ItemStack filler = named(Material.GRAY_STAINED_GLASS_PANE, " ", List.of());
        for (int i = 0; i < 27; i++) inv.setItem(i, filler);
        int[] slots = {11, 15, 13};
        List<Material> available = drops(type);
        for (int i = 0; i < available.size(); i++) {
            Material material = available.get(i);
            inv.setItem(slots[i], named(icon(material), "&f" + material.name().replace('_', ' ') + ": &a" + count(pdc, key(material)),
                List.of("&7Click to drop all stored items", "&8Drops in front of you")));
        }
        inv.setItem(22, named(Material.BARRIER, "&cClose", List.of()));
        player.openInventory(inv);
    }

    @EventHandler
    public void onMenuClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof SpawnerMenu menu)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player) || event.getClickedInventory() != event.getView().getTopInventory()) return;
        if (event.getRawSlot() == 22) { player.closeInventory(); return; }
        if (!(menu.location.getBlock().getState() instanceof CreatureSpawner spawner) || !isManaged(spawner)) {
            player.closeInventory();
            return;
        }
        int[] slots = {11, 15, 13};
        List<Material> available = drops(spawner.getSpawnedType());
        Material chosen = null;
        for (int i = 0; i < available.size(); i++) if (slots[i] == event.getRawSlot()) chosen = available.get(i);
        if (chosen == null) return;
        PersistentDataContainer pdc = spawner.getPersistentDataContainer();
        NamespacedKey key = key(chosen);
        int amount = count(pdc, key);
        if (amount <= 0) return;
        pdc.set(key, PersistentDataType.INTEGER, 0);
        if (!spawner.update(true, false)) {
            player.sendMessage(Component.text("Could not update spawner storage. Try again.", NamedTextColor.RED));
            return;
        }
        dropInFront(player, chosen, amount);
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
        for (org.bukkit.World world : Bukkit.getWorlds()) {
            for (org.bukkit.Chunk chunk : world.getLoadedChunks()) {
                for (BlockState tile : chunk.getTileEntities()) {
                    if (!(tile instanceof CreatureSpawner spawner) || !isManaged(spawner)) continue;
                    EntityType type = spawner.getSpawnedType();
                    Block block = spawner.getBlock();
                    // Only the lowest spawner in a contiguous vertical stack generates for the whole stack.
                    Block below = block.getRelative(0, -1, 0);
                    if (below.getState() instanceof CreatureSpawner lower && isManaged(lower) && lower.getSpawnedType() == type) continue;
                    int multiplier = verticalStackSize(block, type, Integer.MAX_VALUE);
                    List<Material> choices = drops(type);
                    if (choices.isEmpty()) continue;
                    Material chosen = choices.get(random.nextInt(choices.size()));
                    int amount = (min + random.nextInt(max - min + 1)) * multiplier;
                    PersistentDataContainer pdc = spawner.getPersistentDataContainer();
                    NamespacedKey storageKey = key(chosen);
                    int current = count(pdc, storageKey);
                    long sum = (long) current + amount;
                    pdc.set(storageKey, PersistentDataType.INTEGER, (int) Math.min(Integer.MAX_VALUE, sum));
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
