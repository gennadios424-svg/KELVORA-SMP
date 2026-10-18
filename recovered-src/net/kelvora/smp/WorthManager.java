/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  net.kyori.adventure.text.Component
 *  net.kyori.adventure.text.format.NamedTextColor
 *  net.kyori.adventure.text.format.TextColor
 *  org.bukkit.Bukkit
 *  org.bukkit.Material
 *  org.bukkit.NamespacedKey
 *  org.bukkit.Sound
 *  org.bukkit.command.Command
 *  org.bukkit.command.CommandExecutor
 *  org.bukkit.command.CommandSender
 *  org.bukkit.command.TabCompleter
 *  org.bukkit.command.TabExecutor
 *  org.bukkit.configuration.ConfigurationSection
 *  org.bukkit.entity.HumanEntity
 *  org.bukkit.entity.Player
 *  org.bukkit.event.EventHandler
 *  org.bukkit.event.Listener
 *  org.bukkit.event.inventory.ClickType
 *  org.bukkit.event.inventory.InventoryClickEvent
 *  org.bukkit.event.inventory.InventoryDragEvent
 *  org.bukkit.event.player.AsyncPlayerChatEvent
 *  org.bukkit.event.player.PlayerQuitEvent
 *  org.bukkit.inventory.Inventory
 *  org.bukkit.inventory.ItemStack
 *  org.bukkit.inventory.meta.ItemMeta
 *  org.bukkit.persistence.PersistentDataType
 *  org.bukkit.plugin.Plugin
 *  org.bukkit.plugin.java.JavaPlugin
 */
package net.kelvora.smp;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.command.TabExecutor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

final class WorthManager
implements Listener,
TabExecutor {
    private static final TextColor GREEN = TextColor.color((int)45, (int)200, (int)115);
    private static final Component TITLE = Component.text((String)"KELVORA  /  WORTH", (TextColor)GREEN);
    private static final int CONTENT_SIZE = 45;
    private static final Set<String> EXCLUDED = Set.of("AIR", "CAVE_AIR", "VOID_AIR", "BEDROCK", "BARRIER", "KNOWLEDGE_BOOK", "DEBUG_STICK", "COMMAND_BLOCK", "CHAIN_COMMAND_BLOCK", "REPEATING_COMMAND_BLOCK", "STRUCTURE_BLOCK", "STRUCTURE_VOID", "JIGSAW", "SPAWNER", "TRIAL_SPAWNER", "VAULT", "END_PORTAL_FRAME", "END_PORTAL", "END_GATEWAY", "LIGHT");
    private final JavaPlugin plugin;
    private final NamespacedKey actionKey;
    private final NamespacedKey stateKey;
    private final Map<Material, Double> prices = new EnumMap<Material, Double>(Material.class);
    private final Map<UUID, SearchState> pendingSearch = new ConcurrentHashMap<UUID, SearchState>();

    WorthManager(JavaPlugin plugin) {
        this.plugin = plugin;
        this.actionKey = new NamespacedKey((Plugin)plugin, "worth-action");
        this.stateKey = new NamespacedKey((Plugin)plugin, "worth-state");
        this.loadPrices();
    }

    void register() {
        if (this.plugin.getCommand("worth") != null) {
            this.plugin.getCommand("worth").setExecutor((CommandExecutor)this);
            this.plugin.getCommand("worth").setTabCompleter((TabCompleter)this);
        }
        Bukkit.getPluginManager().registerEvents((Listener)this, (Plugin)this.plugin);
    }

    private boolean eligible(Material material) {
        if (material == null || !material.isItem() || material.isAir()) {
            return false;
        }
        String n = material.name();
        if (EXCLUDED.contains(n) || n.endsWith("_SPAWN_EGG")) {
            return false;
        }
        return !n.contains("COMMAND_BLOCK") && !n.contains("STRUCTURE_BLOCK") && !n.contains("STRUCTURE_VOID") && !n.contains("JIGSAW") && !n.contains("SPAWNER") && !n.startsWith("INFESTED_") && !n.equals("BUDDING_AMETHYST") && !n.equals("REINFORCED_DEEPSLATE") && !n.equals("CHORUS_PLANT") && !n.equals("MOVING_PISTON") && !n.equals("PISTON_HEAD") && !n.equals("FROSTED_ICE") && !n.equals("FARMLAND") && !n.equals("DIRT_PATH") && !n.equals("ATTACHED_MELON_STEM") && !n.equals("ATTACHED_PUMPKIN_STEM");
    }

    private void loadPrices() {
        this.prices.clear();
        ConfigurationSection section = this.plugin.getConfig().getConfigurationSection("prices");
        int generated = 0;
        int invalid = 0;
        for (Material material : Material.values()) {
            double price;
            Number number;
            Object configured;
            if (!this.eligible(material)) continue;
            String key = material.name();
            Object object = configured = section == null ? null : section.get(key);
            if (configured instanceof Number && Double.isFinite((number = (Number)configured).doubleValue()) && number.doubleValue() > 0.0) {
                price = number.doubleValue();
            } else {
                price = this.defaultPrice(material);
                this.plugin.getConfig().set("prices." + key, (Object)price);
                ++generated;
            }
            this.prices.put(material, price);
        }
        if (section != null) {
            for (String key : section.getKeys(false)) {
                Number n;
                Material material = Material.matchMaterial((String)key);
                Object raw = section.get(key);
                if (material == null || !this.eligible(material)) {
                    this.plugin.getLogger().warning("Ignoring ineligible/unknown worth material: " + key);
                    ++invalid;
                    continue;
                }
                if (raw instanceof Number && Double.isFinite((n = (Number)raw).doubleValue()) && !(n.doubleValue() <= 0.0)) continue;
                this.plugin.getLogger().warning("Invalid or non-positive worth price for " + key + "; using generated default.");
            }
        }
        this.setReference("KELP", 200.0);
        this.setReference("PINK_PETALS", 150.0);
        this.setReference("SEA_PICKLE", 125.0);
        this.setReference("CHEST", 50.0);
        this.setReference("TRAPPED_CHEST", 100.0);
        this.setReference("OAK_CHEST_BOAT", 250.0);
        this.setReference("NETHERITE_BLOCK", 225000.0);
        this.plugin.saveConfig();
        long missing = Arrays.stream(Material.values()).filter(this::eligible).filter(m -> !this.prices.containsKey(m)).count();
        this.plugin.getLogger().info("Worth registry coverage: eligible=" + this.prices.size() + ", priced=" + this.prices.size() + ", missing=" + missing + ", generated defaults=" + generated + ", invalid configured keys=" + invalid + ".");
        if (missing != 0L) {
            this.plugin.getLogger().severe("Worth registry is incomplete: " + missing + " eligible materials have no price.");
        }
    }

    private void setReference(String key, double value) {
        Material material = Material.matchMaterial((String)key);
        if (material != null && this.eligible(material)) {
            this.prices.put(material, value);
            this.plugin.getConfig().set("prices." + key, (Object)value);
        }
    }

    private double defaultPrice(Material m) {
        String n = m.name();
        if (n.equals("NETHERITE_BLOCK")) {
            return 225000.0;
        }
        if (n.contains("NETHERITE")) {
            return n.contains("SCRAP") ? 6000.0 : (n.contains("INGOT") ? 25000.0 : 2500.0);
        }
        if (n.contains("ELYTRA")) {
            return 25000.0;
        }
        if (n.contains("DRAGON_EGG")) {
            return 2000000.0;
        }
        if (n.contains("TOTEM")) {
            return 12000.0;
        }
        if (n.contains("ENCHANTED_GOLDEN_APPLE")) {
            return 1200.0;
        }
        if (n.contains("SHULKER_SHELL")) {
            return 1500.0;
        }
        if (n.contains("NETHER_STAR")) {
            return 15000.0;
        }
        if (n.contains("HEART_OF_THE_SEA")) {
            return 500.0;
        }
        if (n.contains("TRIDENT")) {
            return 3500.0;
        }
        if (n.contains("MACE")) {
            return 8000.0;
        }
        if (n.contains("DIAMOND")) {
            return n.contains("BLOCK") ? 22500.0 : (n.contains("ORE") ? 3000.0 : 2500.0);
        }
        if (n.contains("EMERALD")) {
            return n.contains("BLOCK") ? 2700.0 : 300.0;
        }
        if (n.contains("ANCIENT_DEBRIS")) {
            return 5000.0;
        }
        if (n.contains("GOLDEN_APPLE")) {
            return 150.0;
        }
        if (n.contains("SPAWN_EGG")) {
            return 100.0;
        }
        if (n.contains("NUGGET")) {
            return 5.0;
        }
        if (n.contains("INGOT")) {
            if (n.contains("IRON")) {
                return 45.0;
            }
            if (n.contains("GOLD")) {
                return 60.0;
            }
            if (n.contains("COPPER")) {
                return 20.0;
            }
            return 30.0;
        }
        if (n.contains("RAW_")) {
            return n.contains("GOLD") ? 45.0 : (n.contains("IRON") ? 35.0 : 15.0);
        }
        if (n.contains("ORE")) {
            return n.contains("DIAMOND") ? 3000.0 : (n.contains("EMERALD") ? 350.0 : (n.contains("GOLD") ? 55.0 : (n.contains("IRON") ? 40.0 : 20.0)));
        }
        if (n.contains("COAL")) {
            return n.contains("BLOCK") ? 135.0 : 15.0;
        }
        if (n.contains("REDSTONE")) {
            return n.contains("BLOCK") ? 108.0 : 12.0;
        }
        if (n.contains("LAPIS")) {
            return n.contains("BLOCK") ? 180.0 : 20.0;
        }
        if (n.contains("QUARTZ")) {
            return 12.0;
        }
        if (n.contains("AMETHYST")) {
            return n.contains("BLOCK") ? 60.0 : 15.0;
        }
        if (n.contains("LOG") || n.contains("WOOD") || n.contains("STEM") || n.contains("HYPHAE")) {
            return 18.0;
        }
        if (n.contains("PLANKS")) {
            return 5.0;
        }
        if (n.contains("CHEST_BOAT")) {
            return 250.0;
        }
        if (n.endsWith("_BOAT") || n.endsWith("_RAFT")) {
            return 35.0;
        }
        if (n.contains("CHEST")) {
            return n.contains("TRAPPED") ? 100.0 : 50.0;
        }
        if (n.contains("PICKAXE") || n.contains("SWORD") || n.contains("AXE") || n.contains("SHOVEL") || n.contains("HOE")) {
            if (n.contains("NETHERITE")) {
                return 8000.0;
            }
            if (n.contains("DIAMOND")) {
                return 2500.0;
            }
            if (n.contains("IRON")) {
                return 150.0;
            }
            if (n.contains("GOLDEN")) {
                return 100.0;
            }
            if (n.contains("STONE")) {
                return 40.0;
            }
            return 20.0;
        }
        if (n.contains("HELMET") || n.contains("CHESTPLATE") || n.contains("LEGGINGS") || n.contains("BOOTS")) {
            if (n.contains("NETHERITE")) {
                return 12000.0;
            }
            if (n.contains("DIAMOND")) {
                return 5000.0;
            }
            if (n.contains("IRON")) {
                return 250.0;
            }
            if (n.contains("GOLDEN")) {
                return 180.0;
            }
            if (n.contains("CHAINMAIL")) {
                return 300.0;
            }
            return 80.0;
        }
        if (n.contains("SHULKER_BOX")) {
            return 2500.0;
        }
        if (n.contains("SPONGE")) {
            return n.contains("WET") ? 200.0 : 180.0;
        }
        if (n.contains("PRISMARINE")) {
            return 35.0;
        }
        if (n.contains("OBSIDIAN")) {
            return n.contains("CRYING") ? 80.0 : 35.0;
        }
        if (n.contains("WOOL") || n.contains("CARPET")) {
            return 8.0;
        }
        if (n.contains("GLASS_PANE")) {
            return 3.0;
        }
        if (n.contains("GLASS")) {
            return 8.0;
        }
        if (n.contains("DIRT") || n.contains("SAND") || n.contains("GRAVEL") || n.contains("STONE") || n.contains("DEEPSLATE") || n.contains("TUFF") || n.contains("BASALT") || n.contains("BLACKSTONE")) {
            return 3.0;
        }
        if (n.contains("FLOWER") || n.contains("PETALS") || n.contains("TULIP") || n.contains("ORCHID") || n.contains("DAISY") || n.contains("POPPY") || n.contains("ALLIUM") || n.contains("LILY")) {
            return 5.0;
        }
        if (n.contains("SEEDS") || n.contains("SAPLING")) {
            return 2.0;
        }
        if (n.contains("WHEAT") || n.contains("CARROT") || n.contains("POTATO") || n.contains("BEETROOT")) {
            return 6.0;
        }
        if (n.contains("BEEF") || n.contains("PORKCHOP") || n.contains("CHICKEN") || n.contains("MUTTON") || n.contains("RABBIT")) {
            return 8.0;
        }
        if (n.contains("SPAWNER")) {
            return 5000.0;
        }
        if (n.contains("DISC") || n.contains("MUSIC")) {
            return 500.0;
        }
        if (n.contains("BANNER") || n.contains("CANDLE") || n.contains("DYE")) {
            return 12.0;
        }
        if (n.contains("POTION") || n.contains("TIPPED_ARROW")) {
            return 30.0;
        }
        if (n.contains("RAIL") || n.contains("MINECART")) {
            return 35.0;
        }
        if (n.contains("REDSTONE") || n.contains("PISTON") || n.contains("DISPENSER") || n.contains("DROPPER") || n.contains("OBSERVER") || n.contains("REPEATER") || n.contains("COMPARATOR") || n.contains("HOPPER")) {
            return 30.0;
        }
        if (n.contains("BONE") || n.contains("STRING") || n.contains("FEATHER") || n.contains("LEATHER")) {
            return 5.0;
        }
        if (n.contains("ENDER_PEARL")) {
            return 35.0;
        }
        if (n.contains("BLAZE_ROD")) {
            return 45.0;
        }
        if (n.contains("GHAST_TEAR")) {
            return 100.0;
        }
        if (n.contains("SLIME")) {
            return 25.0;
        }
        if (n.contains("GUNPOWDER")) {
            return 12.0;
        }
        if (n.contains("APPLE")) {
            return 10.0;
        }
        if (n.contains("BREAD") || n.contains("COOKED_")) {
            return 6.0;
        }
        if (n.contains("BLOCK")) {
            return 30.0;
        }
        if (n.contains("BUCKET")) {
            return 20.0;
        }
        if (n.contains("TORCH")) {
            return 2.0;
        }
        if (n.contains("DOOR") || n.contains("TRAPDOOR") || n.contains("FENCE") || n.contains("SLAB") || n.contains("STAIRS")) {
            return 5.0;
        }
        if (n.contains("FLOWER") || n.contains("LEAVES") || n.contains("VINE") || n.contains("MOSS")) {
            return 4.0;
        }
        return 10.0;
    }

    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage("This command can only be used in-game.");
            return true;
        }
        Player player = (Player)sender;
        if (!player.hasPermission("kelvora.worth")) {
            player.sendMessage((Component)Component.text((String)"You do not have permission.", (TextColor)NamedTextColor.RED));
            return true;
        }
        SearchState state = new SearchState(String.join((CharSequence)" ", args).trim());
        this.pendingSearch.remove(player.getUniqueId());
        this.openWorth(player, state);
        this.play(player, Sound.BLOCK_CHEST_OPEN, 0.55f, 1.0f);
        return true;
    }

    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length != 1) {
            return List.of();
        }
        String prefix = args[0].toLowerCase(Locale.ROOT);
        return this.prices.keySet().stream().map(WorthManager::pretty).filter(n -> n.toLowerCase(Locale.ROOT).startsWith(prefix)).limit(20L).toList();
    }

    private List<Map.Entry<Material, Double>> results(SearchState state) {
        String q = state.query.toLowerCase(Locale.ROOT).trim();
        List<Map.Entry<Material, Double>> list = this.prices.entrySet().stream().filter(e -> {
            String display = WorthManager.pretty((Material)e.getKey()).toLowerCase(Locale.ROOT);
            String raw = ((Material)e.getKey()).name().toLowerCase(Locale.ROOT).replace('_', ' ');
            return q.isEmpty() || display.contains(q) || raw.contains(q) || q.equals("dia") && raw.contains("diamond") || q.equals("petals") && raw.contains("pink petals") || q.equals("boat with chest") && raw.contains("chest boat");
        }).collect(Collectors.toCollection(ArrayList::new));
        Comparator<Map.Entry<Material, Double>> alpha = Comparator.comparing(e -> WorthManager.pretty(e.getKey()).toLowerCase(Locale.ROOT));
        list.sort(switch (state.sort.ordinal()) {
            default -> throw new MatchException(null, null);
            case 0 -> alpha;
            case 1 -> alpha.reversed();
            case 2 -> Comparator.comparingDouble((Map.Entry<Material, Double> e) -> e.getValue()).thenComparing(alpha);
            case 3 -> Comparator.comparingDouble((Map.Entry<Material, Double> e) -> e.getValue()).reversed().thenComparing(alpha);
        });
        return list;
    }

    private void openWorth(Player player, SearchState state) {
        int index;
        List<Map.Entry<Material, Double>> entries = this.results(state);
        int pages = Math.max(1, (entries.size() + 45 - 1) / 45);
        state.page = Math.max(0, Math.min(state.page, pages - 1));
        Inventory inv = Bukkit.createInventory(null, (int)54, (Component)TITLE);
        for (int i = 0; i < 45 && (index = state.page * 45 + i) < entries.size(); ++i) {
            Map.Entry<Material, Double> entry = entries.get(index);
            ItemStack item = new ItemStack(entry.getKey());
            ItemMeta meta = item.getItemMeta();
            meta.displayName((Component)Component.text((String)WorthManager.pretty(entry.getKey()), (TextColor)NamedTextColor.WHITE));
            int maxStack = item.getMaxStackSize();
            double unit = entry.getValue();
            double stack = unit * (double)maxStack;
            meta.lore(List.of(Component.text((String)("Unit Value: " + WorthManager.money(unit)), (TextColor)GREEN), Component.text((String)("Stack Value (" + maxStack + "): " + WorthManager.money(stack)), (TextColor)TextColor.color((int)95, (int)205, (int)220)), Component.text((String)("Maximum stack size: " + maxStack), (TextColor)NamedTextColor.GRAY)));
            item.setItemMeta(meta);
            inv.setItem(i, item);
        }
        this.control(inv, 45, Material.ARROW, "Previous Page", "prev", (TextColor)NamedTextColor.WHITE, List.of("Go to the previous page"));
        ItemStack info = new ItemStack(Material.PAPER);
        ItemMeta im = info.getItemMeta();
        im.displayName((Component)Component.text((String)("Page " + (state.page + 1) + " of " + pages), (TextColor)NamedTextColor.WHITE));
        im.lore(List.of(Component.text((String)(entries.size() + " priced items"), (TextColor)GREEN), Component.text((String)(state.query.isBlank() ? "Search: All Items" : "Search: " + state.query), (TextColor)NamedTextColor.GRAY)));
        info.setItemMeta(im);
        inv.setItem(46, info);
        this.control(inv, 48, Material.COMPASS, "Search Items", "search", GREEN, List.of("Click to search by item name"));
        this.control(inv, 49, Material.HOPPER, "Sort: " + state.sort.label, "sort", GREEN, List.of("Click to change sorting mode", "Sorting applies to all matching items"));
        this.control(inv, 50, Material.SLIME_BALL, "Clear Search", "clear", GREEN, List.of("Remove the current search filter"));
        this.control(inv, 53, Material.ARROW, "Next Page", "next", (TextColor)NamedTextColor.WHITE, List.of("Go to the next page"));
        player.getPersistentDataContainer().set(this.stateKey, PersistentDataType.STRING, this.encode(state));
        player.openInventory(inv);
    }

    private void control(Inventory inv, int slot, Material material, String label, String action, TextColor color, List<String> lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName((Component)Component.text((String)label, (TextColor)color));
        meta.lore(lore.stream().map(line -> Component.text((String)line, (TextColor)NamedTextColor.GRAY)).toList());
        meta.getPersistentDataContainer().set(this.actionKey, PersistentDataType.STRING, action);
        item.setItemMeta(meta);
        inv.setItem(slot, item);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onClick(InventoryClickEvent event) {
        Player player;
        block22: {
            block21: {
                if (!event.getView().getTitle().replaceAll("\\s+", " ").trim().startsWith("KELVORA / WORTH")) {
                    return;
                }
                event.setCancelled(true);
                HumanEntity humanEntity = event.getWhoClicked();
                if (!(humanEntity instanceof Player)) break block21;
                player = (Player)humanEntity;
                if (event.getClickedInventory() == event.getView().getTopInventory()) break block22;
            }
            return;
        }
        if (event.getClick() == ClickType.DOUBLE_CLICK || event.getClick() == ClickType.NUMBER_KEY || event.getClick() == ClickType.SWAP_OFFHAND) {
            return;
        }
        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || !clicked.hasItemMeta()) {
            return;
        }
        String action = (String)clicked.getItemMeta().getPersistentDataContainer().get(this.actionKey, PersistentDataType.STRING);
        if (action == null) {
            return;
        }
        SearchState state = this.decode((String)player.getPersistentDataContainer().get(this.stateKey, PersistentDataType.STRING));
        switch (action) {
            case "prev": {
                --state.page;
                this.play(player, Sound.UI_BUTTON_CLICK, 0.45f, 0.9f);
                break;
            }
            case "next": {
                ++state.page;
                this.play(player, Sound.UI_BUTTON_CLICK, 0.45f, 1.1f);
                break;
            }
            case "sort": {
                state.sort = Sort.values()[(state.sort.ordinal() + 1) % Sort.values().length];
                state.page = 0;
                this.play(player, Sound.UI_BUTTON_CLICK, 0.45f, 1.0f);
                break;
            }
            case "clear": {
                state.query = "";
                state.page = 0;
                this.play(player, Sound.UI_BUTTON_CLICK, 0.45f, 0.9f);
                break;
            }
            case "search": {
                state.awaitingChat = true;
                this.pendingSearch.put(player.getUniqueId(), state);
                player.closeInventory();
                this.play(player, Sound.UI_BUTTON_CLICK, 0.45f, 1.0f);
                player.sendMessage((Component)Component.text((String)"Type an item name to search. Type CANCEL to cancel; your search input will not be public.", (TextColor)GREEN));
                return;
            }
            default: {
                return;
            }
        }
        this.openWorth(player, state);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTitle().replaceAll("\\s+", " ").trim().startsWith("KELVORA / WORTH") && event.getRawSlots().stream().anyMatch(slot -> slot < event.getView().getTopInventory().getSize())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onChat(AsyncPlayerChatEvent event) {
        SearchState state = this.pendingSearch.get(event.getPlayer().getUniqueId());
        if (state == null || !state.awaitingChat) {
            return;
        }
        event.setCancelled(true);
        String input = event.getMessage().trim();
        Bukkit.getScheduler().runTask((Plugin)this.plugin, () -> {
            this.pendingSearch.remove(event.getPlayer().getUniqueId());
            if (input.equalsIgnoreCase("cancel")) {
                event.getPlayer().sendMessage((Component)Component.text((String)"Worth search cancelled.", (TextColor)NamedTextColor.GRAY));
                return;
            }
            state.query = input;
            state.page = 0;
            state.awaitingChat = false;
            this.openWorth(event.getPlayer(), state);
            if (this.results(state).isEmpty()) {
                event.getPlayer().sendMessage((Component)Component.text((String)"No matching items found.", (TextColor)NamedTextColor.YELLOW));
            } else {
                this.play(event.getPlayer(), Sound.BLOCK_NOTE_BLOCK_PLING, 0.35f, 1.35f);
            }
        });
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        this.pendingSearch.remove(event.getPlayer().getUniqueId());
    }

    private void play(Player player, Sound sound, float volume, float pitch) {
        player.playSound(player.getLocation(), sound, volume, pitch);
    }

    private SearchState decode(String raw) {
        if (raw == null) {
            return new SearchState("");
        }
        String[] parts = raw.split("\\|", -1);
        SearchState state = new SearchState(parts.length > 0 ? parts[0] : "");
        if (parts.length > 1) {
            try {
                state.page = Integer.parseInt(parts[1]);
            }
            catch (NumberFormatException numberFormatException) {
                // empty catch block
            }
        }
        if (parts.length > 2) {
            try {
                state.sort = Sort.valueOf(parts[2]);
            }
            catch (IllegalArgumentException illegalArgumentException) {
                // empty catch block
            }
        }
        return state;
    }

    private String encode(SearchState state) {
        return state.query.replace("|", " ").replace("\n", " ") + "|" + state.page + "|" + state.sort.name();
    }

    private static String pretty(Material material) {
        return Arrays.stream(material.name().toLowerCase(Locale.ROOT).split("_")).map(word -> word.isEmpty() ? word : Character.toUpperCase(word.charAt(0)) + word.substring(1)).collect(Collectors.joining(" "));
    }

    private static String money(double amount) {
        double abs = Math.abs(amount);
        if (abs >= 1.0E9) {
            return WorthManager.compact(amount / 1.0E9, "B");
        }
        if (abs >= 1000000.0) {
            return WorthManager.compact(amount / 1000000.0, "M");
        }
        if (abs >= 1000.0) {
            return WorthManager.compact(amount / 1000.0, "K");
        }
        return "$" + (amount == Math.rint(amount) ? String.format(Locale.US, "%.0f", amount) : String.format(Locale.US, "%.2f", amount).replaceAll("0+$", "").replaceAll("\\.$", ""));
    }

    private static String compact(double value, String suffix) {
        return "$" + String.format(Locale.US, "%.1f", value).replaceAll("\\.0$", "") + suffix;
    }

    private static final class SearchState {
        String query;
        int page;
        Sort sort = Sort.AZ;
        boolean awaitingChat;

        SearchState(String query) {
            this.query = query == null ? "" : query;
        }
    }

    private static enum Sort {
        AZ("Name: A to Z"),
        ZA("Name: Z to A"),
        LOW("Price: Lowest First"),
        HIGH("Price: Highest First");

        final String label;

        private Sort(String label) {
            this.label = label;
        }
    }
}

