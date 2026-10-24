package net.kelvora.smp;

import java.text.DecimalFormat;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.block.ShulkerBox;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionType;

final class ShopSellManager implements Listener {
    private static final String MAIN = "KELVORA  /  SHOP";
    private static final String SELL = "KELVORA / SELL";
    private static final String QUANTITY = "KELVORA  /  QUANTITY";
    private static final String CATEGORY = "KELVORA  /  ";
    // Slots 9-44 are deposit slots; row 0 is the instruction header and row 5 is protected controls.
    private static final int SELL_START = 9;
    private static final int SELL_END = 45;
    private static final DecimalFormat MONEY = new DecimalFormat("#,##0.##");
    private final JavaPlugin plugin;
    private final NamespacedKey actionKey;
    private final NamespacedKey kelvoraToolIdKey;
    private final Set<UUID> transactions = ConcurrentHashMap.newKeySet();
    private final Set<Inventory> activeInventories = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Map<UUID, QuantitySession> quantitySessions = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> currentCategoryPages = new ConcurrentHashMap<>();
    private Object economy;

    ShopSellManager(JavaPlugin plugin) {
        this.plugin = plugin;
        this.actionKey = new NamespacedKey(plugin, "shop-sell-action");
        this.kelvoraToolIdKey = new NamespacedKey(plugin, "kelvora-tool-id");
    }

    void register() {
        connectEconomy();
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
        // Some economy plugins register their Vault provider later in enable order.
        Bukkit.getScheduler().runTask(plugin, () -> {
            connectEconomy();
            if (economy == null) plugin.getLogger().warning("Shop/Sell Money integration is waiting for Vault and a registered economy provider.");
            else plugin.getLogger().info("Shop/Sell connected to the server Money economy through Vault.");
        });
    }

    private void connectEconomy() {
        economy = null;
        if (!Bukkit.getPluginManager().isPluginEnabled("Vault")) return;
        try {
            Class<?> type = Class.forName("net.milkbowl.vault.economy.Economy", true,
                Bukkit.getPluginManager().getPlugin("Vault").getClass().getClassLoader());
            RegisteredServiceProvider<?> registration = Bukkit.getServicesManager().getRegistration((Class) type);
            if (registration != null) economy = registration.getProvider();
        } catch (ReflectiveOperationException | LinkageError ex) {
            plugin.getLogger().warning("Vault economy API unavailable; shop/sell transactions are disabled until fixed.");
        }
    }

    private void openShop(Player p) {
        Inventory inv = Bukkit.createInventory(null, 36, Component.text(MAIN, NamedTextColor.DARK_GREEN));
        activeInventories.add(inv);
        put(inv, 10, Material.BRICKS, "Blocks", "cat:blocks:0", "Building blocks, wood and shulker boxes");
        put(inv, 11, Material.COOKED_BEEF, "Food", "cat:food:0", "Ready-to-eat food");
        put(inv, 12, Material.END_CRYSTAL, "Crystal PvP", "cat:pvp:0", "Crystals and combat supplies");
        put(inv, 13, Material.REDSTONE, "Redstone", "cat:redstone:0", "Redstone components and mechanisms");
        put(inv, 14, Material.WHEAT, "Farm", "cat:farm:0", "Seeds, crops and farming tools");
        put(inv, 15, Material.NETHERRACK, "Nether", "cat:nether:0", "Nether blocks and materials");
        put(inv, 16, Material.END_STONE, "End", "cat:end:0", "End blocks and materials");
        put(inv, 31, Material.BARRIER, "Close", "close", "Close the shop");
        p.openInventory(inv);
        p.playSound(p.getLocation(), Sound.BLOCK_CHEST_OPEN, 0.3f, 1.15f);

    }

    private void openCategory(Player p, String cat, int page) {
        currentCategoryPages.put(p.getUniqueId(), page);
        List<ShopEntry> entries = categoryEntries(cat);
        // Use the smallest chest that fits this category plus a protected navigation row.
        int guiSize = entries.size() <= 18 ? 27 : entries.size() <= 27 ? 36 : entries.size() <= 36 ? 45 : 54;
        int pageSize = guiSize - 9;
        int pages = Math.max(1, (entries.size() + pageSize - 1) / pageSize);
        page = Math.max(0, Math.min(page, pages - 1));
        int controls = guiSize - 9;
        Inventory inv = Bukkit.createInventory(null, guiSize, Component.text(CATEGORY + (cat.equals("pvp") ? "CRYSTAL PVP" : cat.toUpperCase(Locale.ROOT)), NamedTextColor.DARK_GREEN));
        activeInventories.add(inv);
        for (int i = 0; i < pageSize; i++) {
            int index = page * pageSize + i;
            if (index >= entries.size()) break;
            inv.setItem(i, entries.get(index).createDisplay(actionKey, price(entries.get(index).material)));
        }
        put(inv, controls, Material.ARROW, "Back", "back", "Return to categories");
        if (page > 0) put(inv, controls + 2, Material.SPECTRAL_ARROW, "Previous Page", "page:" + cat + ":" + (page - 1), "Previous items");
        put(inv, controls + 3, Material.PAPER, "Page " + (page + 1) + "/" + pages, "noop", entries.size() + " shop items");
        if (page + 1 < pages) put(inv, controls + 4, Material.SPECTRAL_ARROW, "Next Page", "page:" + cat + ":" + (page + 1), "More items");
        put(inv, controls + 5, Material.EMERALD, "Balance", "balance", "Current Money balance");
        put(inv, controls + 8, Material.BARRIER, "Close", "close", "Close the shop");
        p.openInventory(inv);
        p.playSound(p.getLocation(), Sound.BLOCK_CHEST_OPEN, 0.25f, 1.25f);
    }

    private List<ShopEntry> categoryEntries(String cat) {
        LinkedHashMap<Material, ShopEntry> entries = new LinkedHashMap<>();
        List<ShopEntry> specialEntries = new ArrayList<>();
        String csv = switch (cat) {
            case "blocks" -> "STONE,COBBLESTONE,STONE_BRICKS,CRACKED_STONE_BRICKS,MOSSY_STONE_BRICKS,CHISELED_STONE_BRICKS,ANDESITE,DIORITE,GRANITE,DEEPSLATE,COBBLED_DEEPSLATE,DEEPSLATE_BRICKS,DEEPSLATE_TILES,DIRT,COARSE_DIRT,ROOTED_DIRT,GRASS_BLOCK,SAND,RED_SAND,GRAVEL,CLAY,BRICKS,BRICK,GLASS,GLASS_PANE,TERRACOTTA,SANDSTONE,CUT_SANDSTONE,SMOOTH_SANDSTONE,RED_SANDSTONE,PRISMARINE,PRISMARINE_BRICKS,DARK_PRISMARINE,SEA_LANTERN,BOOKSHELF,CRAFTING_TABLE,CHEST,BARREL";
            case "food" -> "BREAD,COOKED_BEEF,COOKED_PORKCHOP,COOKED_CHICKEN,COOKED_MUTTON,COOKED_RABBIT,COOKED_COD,COOKED_SALMON,BAKED_POTATO,GOLDEN_CARROT,APPLE,PUMPKIN_PIE,COOKIE,MELON_SLICE,SWEET_BERRIES,GLOW_BERRIES,DRIED_KELP,CHORUS_FRUIT";
            case "pvp" -> "OBSIDIAN,END_CRYSTAL,RESPAWN_ANCHOR,GLOWSTONE,EXPERIENCE_BOTTLE,GOLDEN_APPLE,ENDER_PEARL,ARROW,SPECTRAL_ARROW,TOTEM_OF_UNDYING";
            case "redstone" -> "REDSTONE,REDSTONE_TORCH,REPEATER,COMPARATOR,OBSERVER,PISTON,STICKY_PISTON,HOPPER,DROPPER,DISPENSER,REDSTONE_LAMP,LEVER,STONE_BUTTON,STONE_PRESSURE_PLATE,TRIPWIRE_HOOK,TARGET,DAYLIGHT_DETECTOR,NOTE_BLOCK,RAIL,POWERED_RAIL,DETECTOR_RAIL,ACTIVATOR_RAIL,MINECART,HOPPER_MINECART,SLIME_BLOCK,HONEY_BLOCK,TRAPPED_CHEST";
            case "farm" -> "WHEAT_SEEDS,CARROT,POTATO,BEETROOT_SEEDS,PUMPKIN_SEEDS,MELON_SEEDS,BONE_MEAL,SUGAR_CANE";
            case "nether" -> "NETHERRACK,NETHER_BRICKS,RED_NETHER_BRICKS,BLACKSTONE,POLISHED_BLACKSTONE,POLISHED_BLACKSTONE_BRICKS,BASALT,POLISHED_BASALT,SMOOTH_BASALT,SOUL_SAND,SOUL_SOIL,MAGMA_BLOCK,QUARTZ,QUARTZ_BLOCK,QUARTZ_BRICKS,CHISELED_QUARTZ_BLOCK,SMOOTH_QUARTZ,NETHER_WART";
            case "end" -> "END_STONE,END_STONE_BRICKS,PURPUR_BLOCK,PURPUR_PILLAR,PURPUR_STAIRS,PURPUR_SLAB,END_ROD,POPPED_CHORUS_FRUIT,ENDER_CHEST,ENDER_EYE";
            default -> "";
        };
        for (String raw : csv.split(",")) {
            Material m = Material.matchMaterial(raw.trim().toUpperCase(Locale.ROOT));
            if (m != null && m.isItem()) entries.putIfAbsent(m, new ShopEntry(m, null, 0));
        }
        if (cat.equals("blocks")) {
            entries.putIfAbsent(Material.SHULKER_BOX, new ShopEntry(Material.SHULKER_BOX, null, 0));
            // Keep only the two most useful decorative colour families; avoid a filler catalogue.
            addColorVariants(entries, "WOOL");
            addColorVariants(entries, "CONCRETE");
            for (String wood : List.of("OAK","SPRUCE","BIRCH","JUNGLE","ACACIA","DARK_OAK","MANGROVE","CHERRY","BAMBOO","CRIMSON","WARPED")) {
                for (String suffix : List.of("_LOG","_STEM")) {
                    Material m = Material.matchMaterial(wood + suffix);
                    if (m != null && m.isItem()) entries.putIfAbsent(m, new ShopEntry(m, null, 0));
                }
            }
        }
        List<ShopEntry> result = new ArrayList<>(entries.values());
        result.addAll(specialEntries);
        result.removeIf(entry -> entry.material == Material.POTION || entry.material == Material.SPLASH_POTION || entry.material == Material.LINGERING_POTION);
        // A single authoritative category assignment prevents duplicates, and this blacklist
        // prevents future catalogue edits from bypassing survival progression.
        result.removeIf(entry -> isProgressionRestricted(entry.material)
            || entry.material.name().endsWith("_SHULKER_BOX")
            || !cat.equals(categoryFor(entry)));
        // One canonical shulker entry only: the uncoloured standard SHULKER_BOX in Blocks.
        if (cat.equals("blocks")) result.removeIf(entry -> entry.material != Material.SHULKER_BOX && entry.material.name().endsWith("SHULKER_BOX"));
        return result;
    }

    private boolean isProgressionRestricted(Material material) {
        return Set.of(
            "DIAMOND", "DIAMOND_BLOCK", "DIAMOND_ORE", "DEEPSLATE_DIAMOND_ORE",
            "IRON_INGOT", "IRON_NUGGET", "IRON_BLOCK", "RAW_IRON", "RAW_IRON_BLOCK", "IRON_ORE", "DEEPSLATE_IRON_ORE",
            "GOLD_INGOT", "GOLD_NUGGET", "GOLD_BLOCK", "RAW_GOLD", "RAW_GOLD_BLOCK", "GOLD_ORE", "DEEPSLATE_GOLD_ORE",
            "EMERALD", "EMERALD_BLOCK", "EMERALD_ORE", "DEEPSLATE_EMERALD_ORE",
            "COPPER_INGOT", "RAW_COPPER", "RAW_COPPER_BLOCK", "COPPER_BLOCK", "EXPOSED_COPPER", "WEATHERED_COPPER", "OXIDIZED_COPPER", "WAXED_COPPER_BLOCK",
            "GILDED_BLACKSTONE", "ANCIENT_DEBRIS", "NETHERITE_SCRAP", "NETHERITE_INGOT", "NETHERITE_BLOCK",
            "ELYTRA", "SHULKER_SHELL", "HEART_OF_THE_SEA", "ENCHANTED_GOLDEN_APPLE"
        ).contains(material.name());
    }

    private String categoryFor(ShopEntry entry) {
        if (entry.potionType != null || entry.material == Material.POTION || entry.material == Material.SPLASH_POTION || entry.material == Material.LINGERING_POTION) return "pvp";
        String name = entry.material.name();
        if (Set.of("OBSIDIAN","END_CRYSTAL","RESPAWN_ANCHOR","GLOWSTONE","EXPERIENCE_BOTTLE","GOLDEN_APPLE","ENDER_PEARL","ARROW","SPECTRAL_ARROW","POTION","TOTEM_OF_UNDYING").contains(name)) return "pvp";
        if (name.startsWith("NETHER") || Set.of("BLACKSTONE","POLISHED_BLACKSTONE","POLISHED_BLACKSTONE_BRICKS","BASALT","POLISHED_BASALT","SMOOTH_BASALT","SOUL_SAND","SOUL_SOIL","MAGMA_BLOCK","QUARTZ","QUARTZ_BLOCK","QUARTZ_BRICKS","CHISELED_QUARTZ_BLOCK","SMOOTH_QUARTZ").contains(name)) return "nether";
        if (name.startsWith("END_") || name.startsWith("PURPUR_") || name.equals("ENDER_CHEST") || name.equals("ENDER_EYE") || name.equals("POPPED_CHORUS_FRUIT")) return "end";
        if (Set.of("WHEAT_SEEDS","CARROT","POTATO","BEETROOT_SEEDS","PUMPKIN_SEEDS","MELON_SEEDS","WHEAT","SUGAR_CANE","PUMPKIN","MELON","CACTUS","BAMBOO","COCOA_BEANS","BONE_MEAL","COMPOSTER","FLOWER_POT","WATER_BUCKET","LAVA_BUCKET","SHEARS","LEAD").contains(name)) return "farm";
        if (Set.of("BREAD","COOKED_BEEF","COOKED_PORKCHOP","COOKED_CHICKEN","COOKED_MUTTON","COOKED_RABBIT","COOKED_COD","COOKED_SALMON","BAKED_POTATO","GOLDEN_CARROT","APPLE","PUMPKIN_PIE","COOKIE","MELON_SLICE","SWEET_BERRIES","GLOW_BERRIES","DRIED_KELP","CHORUS_FRUIT").contains(name)) return "food";
        if (Set.of("REDSTONE","REDSTONE_TORCH","REPEATER","COMPARATOR","OBSERVER","PISTON","STICKY_PISTON","HOPPER","DROPPER","DISPENSER","REDSTONE_LAMP","LEVER","STONE_BUTTON","STONE_PRESSURE_PLATE","TRIPWIRE_HOOK","TARGET","DAYLIGHT_DETECTOR","NOTE_BLOCK","RAIL","POWERED_RAIL","DETECTOR_RAIL","ACTIVATOR_RAIL","MINECART","HOPPER_MINECART","SLIME_BLOCK","HONEY_BLOCK","TRAPPED_CHEST").contains(name)) return "redstone";
        return "blocks";
    }

    private void addColorVariants(Map<Material, ShopEntry> entries, String suffix) {
        for (String color : List.of("WHITE","ORANGE","MAGENTA","LIGHT_BLUE","YELLOW","LIME","PINK","GRAY","LIGHT_GRAY","CYAN","PURPLE","BLUE","BROWN","GREEN","RED","BLACK")) {
            Material m = Material.matchMaterial(color + "_" + suffix);
            if (m != null && m.isItem()) entries.putIfAbsent(m, new ShopEntry(m, null, 0));
        }
    }

    private double potionCost(String type) {
        if (type.contains("FIRE_RESISTANCE")) return 1800;
        if (type.contains("STRENGTH")) return 2200;
        if (type.contains("HEALING")) return 1200;
        if (type.contains("REGENERATION")) return 2500;
        return 1000;
    }

    @EventHandler(priority = org.bukkit.event.EventPriority.HIGHEST, ignoreCancelled = true)
    public void forceKelvoraSellCommand(PlayerCommandPreprocessEvent e) {
        String message = e.getMessage();
        if (message == null || !message.startsWith("/")) return;
        String[] parts = message.substring(1).trim().split("\\s+", 2);
        if (parts.length == 0) return;
        String label = parts[0].toLowerCase(Locale.ROOT);
        // Force /sell to this GUI even when another plugin registered /sell first.
        if (!label.equals("sell") && !label.endsWith(":sell")) return;
        e.setCancelled(true);
        Player p = e.getPlayer();
        if (!p.hasPermission("kelvora.sell")) {
            p.sendMessage(Component.text("You do not have permission.", NamedTextColor.RED));
            return;
        }
        openSell(p);
    }

    private void openSell(Player p) {
        Inventory inv = Bukkit.createInventory(null, 54, Component.text(SELL, NamedTextColor.DARK_GREEN));
        activeInventories.add(inv);
        // New layout: dedicated instruction header, four rows of real deposit slots, and protected controls.
        for (int i = 0; i < SELL_START; i++) inv.setItem(i, createSellHeaderFrame());
        for (int i = SELL_END; i < 54; i++) inv.setItem(i, createSellFrame());
        put(inv, 45, Material.BARRIER, "Cancel & Return", "cancel", "Close safely and return every unsold item");
        put(inv, 49, Material.GOLD_INGOT, "Sell Value: $0", "total", "Click to announce the current sell value");
        put(inv, 53, Material.LIME_DYE, "CONFIRM SALE", "confirm", "Sell priced items for Money");
        updateSellButton(inv);
        p.openInventory(inv);

    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        String title = stripColor(e.getView().getTitle());
        if (title.equals(MAIN)) {
            e.setCancelled(true);
            if (!(e.getWhoClicked() instanceof Player p) || e.getClickedInventory() != e.getView().getTopInventory()) return;
            String action = action(e.getCurrentItem());
            if (action == null) return;
            playUiSound(p, Sound.UI_BUTTON_CLICK);
            if (action.startsWith("cat:")) {
                String[] bits = action.split(":");
                if (bits.length >= 3) openCategory(p, bits[1], parsePage(bits[2]));
            } else if (action.equals("close")) p.closeInventory();
            return;
        }
        if (title.equals(QUANTITY)) {
            e.setCancelled(true);
            if (!(e.getWhoClicked() instanceof Player p) || e.getClickedInventory() != e.getView().getTopInventory()) return;
            String action = action(e.getCurrentItem());
            if (action != null) playUiSound(p, Sound.UI_BUTTON_CLICK);
            QuantitySession session = quantitySessions.get(p.getUniqueId());
            if (action == null || session == null) return;
            if (action.startsWith("qty:")) {
                int qty = parsePage(action.substring(4));
                if (validQuantity(qty, session.prototype.getMaxStackSize())) {
                    session.quantity = qty;
                    confirmPurchase(p, session);
                }
            } else if (action.equals("confirm")) confirmPurchase(p, session);
            else if (action.equals("back")) {
                quantitySessions.remove(p.getUniqueId());
                openCategory(p, session.category, session.page);
            } else if (action.equals("cancel")) {
                quantitySessions.remove(p.getUniqueId());
                openCategory(p, session.category, session.page);
            }
            return;
        }
        if (title.startsWith(CATEGORY)) {
            e.setCancelled(true);
            if (!(e.getWhoClicked() instanceof Player p) || e.getClickedInventory() != e.getView().getTopInventory()) return;
            String action = action(e.getCurrentItem());
            if (action == null) return;
            playUiSound(p, action.startsWith("page:") ? Sound.UI_BUTTON_CLICK : Sound.UI_BUTTON_CLICK);
            if (action.equals("back")) openShop(p);
            else if (action.equals("close")) p.closeInventory();
            else if (action.equals("balance")) tellBalance(p);
            else if (action.startsWith("page:")) {
                String[] bits = action.split(":");
                if (bits.length == 3) openCategory(p, bits[1], parsePage(bits[2]));
            } else if (action.startsWith("buyPotion:")) openPotionQuantity(p, action.substring("buyPotion:".length()), title);
            else if (action.startsWith("buy:")) openMaterialQuantity(p, Material.matchMaterial(action.substring(4)), title);
            return;
        }
        if (!title.equals(SELL)) return;
        int raw = e.getRawSlot();
        Inventory top = e.getView().getTopInventory();

        // The deposit area (0-44) deliberately uses Bukkit's normal inventory
        // mechanics. Cancelling these clicks and simulating them manually broke
        // number-key swaps, double-clicks, drag placement, and client prediction.
        // Only the bottom control row is protected.
        if (raw >= SELL_END && raw < 54) {
            e.setCancelled(true);
            if (!(e.getWhoClicked() instanceof Player p)) return;
            String action = action(e.getCurrentItem());
            if (action != null) playUiSound(p, Sound.UI_BUTTON_CLICK);
            if ("cancel".equals(action)) {
                // The close handler safely returns every item still in the deposit area.
                p.closeInventory();
            }
            else if ("total".equals(action)) tellSellTotal(p, top);
            else if ("confirm".equals(action)) confirmSell(p, top);
            return;
        }

        if (raw >= 0 && raw < SELL_START) {
            e.setCancelled(true);
            return;
        }

        // Shift-click from the player's inventory is routed only into the
        // four deposit rows, never into the header or control row.
        if (e.getClickedInventory() == e.getView().getBottomInventory() && e.isShiftClick()) {
            e.setCancelled(true);
            ItemStack stack = e.getCurrentItem();
            if (stack != null && !stack.getType().isAir()) {
                int remaining = stack.getAmount();
                for (int i = SELL_START; i < SELL_END && remaining > 0; i++) {
                    ItemStack existing = top.getItem(i);
                    if (existing == null || existing.getType().isAir()) {
                        int moved = Math.min(remaining, stack.getMaxStackSize());
                        ItemStack placed = stack.clone();
                        placed.setAmount(moved);
                        top.setItem(i, placed);
                        remaining -= moved;
                    } else if (existing.isSimilar(stack) && existing.getAmount() < existing.getMaxStackSize()) {
                        int moved = Math.min(remaining, existing.getMaxStackSize() - existing.getAmount());
                        existing.setAmount(existing.getAmount() + moved);
                        remaining -= moved;
                    }
                }
                if (remaining <= 0) e.getClickedInventory().setItem(e.getSlot(), null);
                else if (remaining < stack.getAmount()) {
                    stack.setAmount(remaining);
                    e.getClickedInventory().setItem(e.getSlot(), stack);
                }
            }
        }

        // For ordinary clicks in deposit slots, do not cancel the event. Bukkit
        // handles pickup/place/split/swap/double-click and keeps cursor state synced.
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (pStillViewingSell(e.getWhoClicked(), top)) updateSellButton(top);
        });
    }

    private boolean pStillViewingSell(org.bukkit.entity.HumanEntity human, Inventory inv) {
        return human.getOpenInventory().getTopInventory() == inv && stripColor(human.getOpenInventory().getTitle()).equals(SELL);
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        String title = stripColor(e.getView().getTitle());
        if (title.equals(MAIN) || title.startsWith(CATEGORY)) { e.setCancelled(true); return; }
        if (title.equals(SELL)) {
            if (e.getRawSlots().stream().anyMatch(s -> s < SELL_START || (s >= SELL_END && s < 54))) { e.setCancelled(true); return; }
            Inventory top = e.getView().getTopInventory();
            Bukkit.getScheduler().runTask(plugin, () -> updateSellButton(top));
        }
    }

    @EventHandler
    public void onClose(InventoryCloseEvent e) {
        String title = stripColor(e.getView().getTitle());
        if (title.equals(QUANTITY)) {
            quantitySessions.remove(e.getPlayer().getUniqueId());
            return;
        }
        if (!title.equals(SELL)) return;
        Inventory top = e.getView().getTopInventory();
        Player p = (Player)e.getPlayer();
        for (int i = SELL_START; i < SELL_END; i++) {
            ItemStack item = top.getItem(i);
            if (item == null || item.getType().isAir()) continue;
            Map<Integer, ItemStack> leftovers = p.getInventory().addItem(item.clone());
            leftovers.values().forEach(drop -> p.getWorld().dropItemNaturally(p.getLocation(), drop));
            top.setItem(i, null);
        }
    }

    private double balance(Player player) {
        if (economy == null) connectEconomy();
        Object value = economyCall("getBalance", player, null);
        return value instanceof Number ? ((Number)value).doubleValue() : 0.0;
    }

    private Object economyCall(String method, Player player, Double amount) {
        if (economy == null) return null;
        try {
            if (amount == null) return economy.getClass().getMethod(method, org.bukkit.OfflinePlayer.class).invoke(economy, player);
            return economy.getClass().getMethod(method, org.bukkit.OfflinePlayer.class, double.class).invoke(economy, player, amount.doubleValue());
        } catch (ReflectiveOperationException | LinkageError ex) {
            plugin.getLogger().warning("Money transaction failed (" + method + "): " + ex.getClass().getSimpleName());
            return null;
        }
    }

    private boolean transactionSucceeded(Object response) {
        if (response == null) return false;
        try {
            Object type = response.getClass().getField("type").get(response);
            return type != null && type.toString().equalsIgnoreCase("SUCCESS");
        } catch (ReflectiveOperationException ignored) {
            try { return Boolean.TRUE.equals(response.getClass().getMethod("transactionSuccess").invoke(response)); }
            catch (ReflectiveOperationException ex) { return false; }
        }
    }

    private void openMaterialQuantity(Player p, Material material, String title) {
        if (material == null || material.isAir()) return;
        String category = categoryFromTitle(title);
        int page = 0;
        double unitPrice = shopPrice(material);
        if (!Double.isFinite(unitPrice) || unitPrice <= 0) {
            p.sendMessage(Component.text("This item has no valid shop price and cannot be bought.", NamedTextColor.RED));
            return;
        }
        ItemStack prototype = new ItemStack(material);
        quantitySessions.put(p.getUniqueId(), new QuantitySession(prototype, unitPrice, pretty(material), category, currentCategoryPages.getOrDefault(p.getUniqueId(), 0)));
        openQuantity(p, quantitySessions.get(p.getUniqueId()));
    }

    private void openPotionQuantity(Player p, String type, String title) {
        try {
            PotionType potionType = PotionType.valueOf(type);
            ItemStack item = new ItemStack(Material.POTION);
            PotionMeta meta = (PotionMeta)item.getItemMeta();
            meta.setBasePotionType(potionType);
            meta.displayName(Component.text(prettyPotion(type), NamedTextColor.WHITE));
            item.setItemMeta(meta);
            quantitySessions.put(p.getUniqueId(), new QuantitySession(item, Math.max(potionCost(type), price(Material.POTION) + 0.01), prettyPotion(type), categoryFromTitle(title), currentCategoryPages.getOrDefault(p.getUniqueId(), 0)));
            openQuantity(p, quantitySessions.get(p.getUniqueId()));
        } catch (IllegalArgumentException ex) {
            p.sendMessage(Component.text("That potion is unavailable.", NamedTextColor.RED));
        }
    }

    private String categoryFromTitle(String title) {
        String category = title.substring(CATEGORY.length()).toLowerCase(Locale.ROOT);
        return switch (category) {
            case "blocks", "food", "pvp", "redstone", "farm", "nether", "end" -> category;
            case "crystal pvp" -> "pvp";
            default -> "blocks";
        };
    }

    private boolean validQuantity(int quantity, int maxStack) {
        return quantity == 1 || quantity == 32 || quantity == 64;
    }

    private void openQuantity(Player p, QuantitySession session) {
        Inventory inv = Bukkit.createInventory(null, 45, Component.text(QUANTITY, NamedTextColor.DARK_GREEN));
        activeInventories.add(inv);
        fill(inv, Material.GRAY_STAINED_GLASS_PANE, " ");
        refreshQuantityView(p, inv, session);
        put(inv, 19, Material.LIME_CONCRETE, "Buy 1", "qty:1", "Total: $" + MONEY.format(session.unitPrice));
        put(inv, 21, Material.LIME_CONCRETE, "Buy 32", "qty:32", "Total: $" + MONEY.format(session.unitPrice * 32));
        put(inv, 23, Material.LIME_CONCRETE, "Buy 64", "qty:64", "Total: $" + MONEY.format(session.unitPrice * 64));
        put(inv, 29, Material.ARROW, "Back", "back", "Return to " + session.category + " shop");
        put(inv, 31, Material.EMERALD, "Confirm Purchase", "confirm", "Pay $" + MONEY.format(session.unitPrice * session.quantity));
        put(inv, 33, Material.BARRIER, "Cancel", "cancel", "Return without buying");
        p.openInventory(inv);
        p.playSound(p.getLocation(), Sound.BLOCK_CHEST_OPEN, 0.25f, 1.2f);
    }

    private void refreshQuantityView(Player p, Inventory inv, QuantitySession session) {
        ItemStack display = session.prototype.clone();
        display.setAmount(1);
        ItemMeta dm = display.getItemMeta();
        dm.displayName(Component.text(session.name, NamedTextColor.WHITE));
        dm.lore(List.of(Component.text("Unit price: $" + MONEY.format(session.unitPrice), NamedTextColor.GREEN),
            Component.text("Selected quantity: " + session.quantity, NamedTextColor.AQUA),
            Component.text("Total: $" + MONEY.format(session.unitPrice * session.quantity), NamedTextColor.GOLD),
            Component.text("Current Money: $" + MONEY.format(balance(p)), NamedTextColor.GRAY)));
        display.setItemMeta(dm);
        inv.setItem(13, display);
        ItemStack confirm = inv.getItem(31);
        if (confirm != null) {
            ItemMeta meta = confirm.getItemMeta();
            meta.lore(List.of(Component.text("Pay $" + MONEY.format(session.unitPrice * session.quantity), NamedTextColor.GREEN)));
            confirm.setItemMeta(meta);
            inv.setItem(31, confirm);
        }
    }

    private void confirmPurchase(Player p, QuantitySession session) {
        if (quantitySessions.get(p.getUniqueId()) != session || !transactions.add(p.getUniqueId())) return;
        try {
            double unit = session.unitPrice;
            Material type = session.prototype.getType();
            if (session.prototype.getItemMeta() instanceof PotionMeta) {
                // Rebuild the price from the actual base potion type immediately before charging.
                unit = Math.max(session.unitPrice, price(Material.POTION) + 0.01);
            } else {
                unit = shopPrice(type);
                if (!Double.isFinite(unit) || unit <= 0) {
                    p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.45f, 0.8f);
                    p.sendMessage(Component.text("This item no longer has a valid shop price. No charge was made.", NamedTextColor.RED));
                    return;
                }
            }
            int qty = session.quantity;
            if (!validQuantity(qty, session.prototype.getMaxStackSize())) {
                p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.45f, 0.8f);
                p.sendMessage(Component.text("Invalid quantity. No charge was made.", NamedTextColor.RED)); return;
            }
            double cost = unit * qty;
            if (!Double.isFinite(cost) || cost <= 0) {
                p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.45f, 0.8f);
                p.sendMessage(Component.text("Invalid price. No charge was made.", NamedTextColor.RED)); return;
            }
            if (economy == null) connectEconomy();
            if (economy == null) {
                p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.45f, 0.8f);
                p.sendMessage(Component.text("Money is not connected. The server needs Vault and its existing Money economy provider.", NamedTextColor.RED)); return;
            }
            double available = balance(p);
            if (available + 0.000001 < cost) {
                p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.45f, 0.8f);
                p.sendMessage(Component.text("Not enough Money. Need $" + MONEY.format(cost) + "; your balance is $" + MONEY.format(available) + ".", NamedTextColor.RED)); return;
            }
            ItemStack grant = session.prototype.clone();
            grant.setAmount(qty);
            if (!simulateAdd(p.getInventory(), grant).isEmpty()) {
                p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.45f, 0.8f);
                p.sendMessage(Component.text("Not enough inventory space for that quantity. No charge was made.", NamedTextColor.RED)); return;
            }
            Object response = economyCall("withdrawPlayer", p, cost);
            if (!transactionSucceeded(response)) {
                p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.45f, 0.8f);
                p.sendMessage(Component.text("Money transaction failed. No items were given.", NamedTextColor.RED)); return;
            }
            Map<Integer, ItemStack> failed = p.getInventory().addItem(grant);
            if (!failed.isEmpty()) {
                Object refund = economyCall("depositPlayer", p, cost);
                failed.values().forEach(item -> p.getWorld().dropItemNaturally(p.getLocation(), item));
                p.sendMessage(Component.text(transactionSucceeded(refund)
                    ? "Inventory changed during purchase; payment was refunded."
                    : "Inventory changed; leftover items were dropped safely. Contact staff to verify the refund.", NamedTextColor.RED));
                return;
            }
            p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.45f, 1.4f);
            p.sendMessage(Component.text("Bought " + qty + "x " + session.name + " for $" + MONEY.format(cost)
                + ". New Money balance: $" + MONEY.format(balance(p)), NamedTextColor.GREEN));
            refreshQuantityView(p, p.getOpenInventory().getTopInventory(), session);
        } finally {
            transactions.remove(p.getUniqueId());
        }
    }

    private Map<Integer, ItemStack> simulateAdd(PlayerInventory inventory, ItemStack grant) {
        ItemStack[] contents = inventory.getStorageContents();
        Inventory temp = Bukkit.createInventory(null, contents.length);
        temp.setContents(contents);
        return temp.addItem(grant.clone());
    }

    private void confirmSell(Player p, Inventory inv) {
        if (economy == null) connectEconomy();
        if (economy == null) { p.sendMessage(Component.text("Money is not connected. The server needs Vault and its existing Money economy provider.", NamedTextColor.RED)); return; }
        if (!transactions.add(p.getUniqueId())) return;
        try {
            double total = 0;
            int soldCount = 0;
            Set<Integer> sellSlots = new LinkedHashSet<>();
            for (int i = SELL_START; i < SELL_END; i++) {
                ItemStack item = inv.getItem(i);
                if (item == null || item.getType().isAir()) continue;
                Valuation valuation = valuation(item);
                if (isShulker(item.getType()) && !valuation.valid) {
                    p.sendMessage(Component.text("Sale stopped: every item inside a shulker must have a valid /worth price. Nothing was sold.", NamedTextColor.RED)); return;
                }
                if (!valuation.valid) continue;
                total += valuation.value; soldCount += valuation.count; sellSlots.add(i);
            }
            if (sellSlots.isEmpty() || total <= 0 || !Double.isFinite(total)) {
                p.sendMessage(Component.text("No items with a valid /worth price were found.", NamedTextColor.RED)); return;
            }
            Object response = economyCall("depositPlayer", p, total);
            if (!transactionSucceeded(response)) {
                p.sendMessage(Component.text("Money payment failed. Your items are still in the sell chest.", NamedTextColor.RED)); return;
            }
            for (int slot : sellSlots) inv.setItem(slot, null);
            p.sendMessage(Component.text("Sold " + soldCount + " items for $" + MONEY.format(total)
                + " Money. New balance: $" + MONEY.format(balance(p)), NamedTextColor.GREEN));
            p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 0.2f, 1.2f);
            p.closeInventory();
        } finally { transactions.remove(p.getUniqueId()); }
    }

    private Valuation valuation(ItemStack item) {
        if (item == null || item.getType().isAir()) return new Valuation(true, 0, 0);
        if (item.hasItemMeta() && item.getItemMeta().getPersistentDataContainer().has(kelvoraToolIdKey, PersistentDataType.STRING))
            return new Valuation(false, 0, 0);
        double unit = price(item.getType());
        if (unit <= 0) return new Valuation(false, 0, 0);
        double total = unit * item.getAmount();
        int count = item.getAmount();
        if (item.getItemMeta() instanceof BlockStateMeta blockMeta && isShulker(item.getType())) {
            if (!(blockMeta.getBlockState() instanceof ShulkerBox box)) return new Valuation(false, 0, 0);
            for (ItemStack content : box.getInventory().getContents()) {
                if (content == null || content.getType().isAir()) continue;
                Valuation nested = valuation(content);
                if (!nested.valid) return new Valuation(false, 0, 0);
                total += nested.value * item.getAmount();
                count += nested.count * item.getAmount();
            }
        }
        return Double.isFinite(total) ? new Valuation(true, total, count) : new Valuation(false, 0, 0);
    }

    private boolean isShulker(Material material) {
        return material == Material.SHULKER_BOX || material.name().endsWith("_SHULKER_BOX");
    }

    private void tellSellTotal(Player p, Inventory inv) {
        double total = 0; int count = 0; boolean invalidShulker = false;
        for (int i = SELL_START; i < SELL_END; i++) {
            ItemStack item = inv.getItem(i);
            if (item == null || item.getType().isAir()) continue;
            Valuation value = valuation(item);
            if (isShulker(item.getType()) && !value.valid) invalidShulker = true;
            else if (value.valid) { total += value.value; count += value.count; }
        }
        if (invalidShulker) p.sendMessage(Component.text("Total unavailable: a shulker contains an item without a valid /worth price.", NamedTextColor.RED));
        else p.sendMessage(Component.text("Sell chest: " + count + " items worth $" + MONEY.format(total), NamedTextColor.GOLD));
    }

    private void updateSellButton(Inventory inv) {
        if (inv == null || inv.getSize() < 54) return;
        double total = 0; boolean invalidShulker = false;
        for (int i = SELL_START; i < SELL_END; i++) {
            ItemStack item = inv.getItem(i);
            if (item == null || item.getType().isAir()) continue;
            Valuation v = valuation(item);
            if (isShulker(item.getType()) && !v.valid) invalidShulker = true;
            else if (v.valid) total += v.value;
        }
        ItemStack button = new ItemStack(Material.GOLD_INGOT);
        ItemMeta meta = button.getItemMeta();
        meta.displayName(Component.text(invalidShulker ? "Sell Value: Unpriced shulker" : "Sell Value: $" + MONEY.format(total), invalidShulker ? NamedTextColor.RED : NamedTextColor.GOLD));
        meta.lore(List.of(Component.text("Click to calculate full value", NamedTextColor.GRAY)));
        meta.getPersistentDataContainer().set(actionKey, PersistentDataType.STRING, "total");
        button.setItemMeta(meta);
        inv.setItem(49, button);
    }

    private double price(Material m) {
        if (m == null || m.isAir()) return 0;
        double value = plugin.getConfig().getDouble("prices." + m.name(), 0);
        return value > 0 && Double.isFinite(value) ? value : 0;
    }

    private double shopPrice(Material material) {
        if (material == null || material.isAir()) return 0;
        double worth = price(material);
        double configured = plugin.getConfig().getDouble("shop-prices." + material.name(), 0);
        if (worth > 0) return Math.max(Math.max(worth * 1.5, worth + 0.01), configured);
        if (configured > 0 && Double.isFinite(configured)) return configured;
        // Items absent from /worth can still be bought; they cannot be sold for profit because /sell has no valuation.
        return 25.0;
    }

    private void tellBalance(Player p) {
        if (economy == null) p.sendMessage(Component.text("Money unavailable: Vault economy provider not found.", NamedTextColor.RED));
        else p.sendMessage(Component.text("Balance: $" + MONEY.format(balance(p)), NamedTextColor.GREEN));
    }

    private void playUiSound(Player player, Sound sound) {
        player.playSound(player.getLocation(), sound, 0.18f, 1.15f);
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

    private ItemStack createSellFrame() {
        ItemStack item = new ItemStack(Material.GREEN_STAINED_GLASS_PANE);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(" ", NamedTextColor.DARK_GREEN));
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack createSellHeaderFrame() {
        ItemStack item = new ItemStack(Material.BLACK_STAINED_GLASS_PANE);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(" ", NamedTextColor.DARK_GRAY));
        item.setItemMeta(meta);
        return item;
    }

    private void fill(Inventory inv, Material material, String name) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(name, NamedTextColor.GRAY));
        item.setItemMeta(meta);
        for (int i = 0; i < inv.getSize(); i++) inv.setItem(i, item.clone());
    }

    private String action(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return null;
        return item.getItemMeta().getPersistentDataContainer().get(actionKey, PersistentDataType.STRING);
    }

    private String stripColor(String title) { return title.replaceAll("(?i)§[0-9A-FK-OR]", ""); }
    private int parsePage(String value) { try { return Math.max(0, Integer.parseInt(value)); } catch (NumberFormatException ex) { return 0; } }

    private String pretty(Material m) {
        return Arrays.stream(m.name().toLowerCase(Locale.ROOT).split("_"))
            .map(s -> Character.toUpperCase(s.charAt(0)) + s.substring(1)).reduce((a,b) -> a + " " + b).orElse(m.name());
    }

    private String prettyPotion(String type) {
        String name = type.toLowerCase(Locale.ROOT).replace("long_", "extended ").replace("strong_", "strong ");
        return Arrays.stream(name.split("_")).map(s -> Character.toUpperCase(s.charAt(0)) + s.substring(1))
            .reduce((a,b) -> a + " " + b).orElse(name) + " Potion";
    }

    private record Valuation(boolean valid, double value, int count) { }

    private static final class QuantitySession {
        private final ItemStack prototype;
        private final double unitPrice;
        private final String name;
        private final String category;
        private final int page;
        private int quantity = 1;
        private QuantitySession(ItemStack prototype, double unitPrice, String name, String category, int page) {
            this.prototype = prototype.clone();
            this.unitPrice = unitPrice;
            this.name = name;
            this.category = category;
            this.page = page;
        }
    }

    private static final class ShopEntry {
        private final Material material;
        private final String potionType;
        private final double customPrice;
        private ShopEntry(Material material, String potionType, double customPrice) {
            this.material = material; this.potionType = potionType; this.customPrice = customPrice;
        }
        private ItemStack createDisplay(NamespacedKey actionKey, double worth) {
            ItemStack item = new ItemStack(material);
            if (potionType != null) {
                PotionMeta pm = (PotionMeta)item.getItemMeta();
                pm.setBasePotionType(PotionType.valueOf(potionType));
                item.setItemMeta(pm);
            }
            ItemMeta meta = item.getItemMeta();
            String label = potionType == null ? prettyStatic(material) : prettyPotionStatic(potionType);
            meta.displayName(Component.text(label, NamedTextColor.WHITE));
            double configured = customPrice > 0 ? customPrice : 0;
            double buy = worth > 0 ? Math.max(Math.max(worth * 1.5, worth + 0.01), configured) : (configured > 0 ? configured : 25.0);
            meta.lore(List.of(Component.text("Price: $" + MONEY.format(buy), NamedTextColor.GREEN)));
            meta.getPersistentDataContainer().set(actionKey, PersistentDataType.STRING,
                potionType == null ? "buy:" + material.name() : "buyPotion:" + potionType);
            item.setItemMeta(meta);
            return item;
        }
        private static String prettyStatic(Material m) {
            return Arrays.stream(m.name().toLowerCase(Locale.ROOT).split("_"))
                .map(s -> Character.toUpperCase(s.charAt(0)) + s.substring(1)).reduce((a,b) -> a + " " + b).orElse(m.name());
        }
        private static String prettyPotionStatic(String type) {
            String name = type.toLowerCase(Locale.ROOT).replace("long_", "extended ").replace("strong_", "strong ");
            return Arrays.stream(name.split("_")).map(s -> Character.toUpperCase(s.charAt(0)) + s.substring(1))
                .reduce((a,b) -> a + " " + b).orElse(name) + " Potion";
        }
    }
}
