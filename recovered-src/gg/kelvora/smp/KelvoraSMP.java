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
package gg.kelvora.smp;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.command.TabExecutor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
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

public final class KelvoraSMP
extends JavaPlugin
implements Listener,
TabExecutor {
    private static final TextColor GREEN = TextColor.color((int)45, (int)190, (int)105);
    private static final int[] CONTENT_SLOTS = KelvoraSMP.buildContentSlots();
    private final Map<Material, Double> prices = new EnumMap<Material, Double>(Material.class);
    private final Map<UUID, SearchState> pendingSearch = new ConcurrentHashMap<UUID, SearchState>();
    private NamespacedKey controlKey;

    public void onEnable() {
        this.saveDefaultConfig();
        this.controlKey = new NamespacedKey((Plugin)this, "worth-control");
        this.loadPrices();
        Objects.requireNonNull(this.getCommand("worth")).setExecutor((CommandExecutor)this);
        Objects.requireNonNull(this.getCommand("worth")).setTabCompleter((TabCompleter)this);
        Bukkit.getPluginManager().registerEvents((Listener)this, (Plugin)this);
        this.getLogger().info("Loaded " + this.prices.size() + " configured worth entries. Server IP: " + this.getConfig().getString("server-ip"));
    }

    private void loadPrices() {
        this.prices.clear();
        ConfigurationSection section = this.getConfig().getConfigurationSection("prices");
        if (section == null) {
            return;
        }
        for (String raw : section.getKeys(false)) {
            Number number;
            Material material = Material.matchMaterial((String)raw);
            Object value = section.get(raw);
            if (material == null) {
                this.getLogger().warning("Ignoring invalid material in prices: " + raw);
                continue;
            }
            if (!(value instanceof Number) || !Double.isFinite((number = (Number)value).doubleValue()) || number.doubleValue() < 0.0) {
                this.getLogger().warning("Ignoring invalid price for " + raw + ": must be a finite non-negative number.");
                continue;
            }
            if (!this.isSurvivalCandidate(material)) {
                this.getLogger().warning("Ignoring restricted/non-item material in prices: " + raw);
                continue;
            }
            this.prices.put(material, number.doubleValue());
        }
    }

    private boolean isSurvivalCandidate(Material m) {
        return m.isItem() && !m.isAir() && m != Material.BEDROCK && m != Material.BARRIER && !m.name().contains("COMMAND_BLOCK") && !m.name().contains("STRUCTURE_BLOCK") && !m.name().contains("JIGSAW") && !m.name().contains("SPAWNER") && !m.name().contains("DEBUG_STICK") && !m.name().contains("KNOWLEDGE_BOOK") && !m.name().endsWith("_SPAWN_EGG");
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
        String query = String.join((CharSequence)" ", args).trim();
        SearchState state = new SearchState(query);
        this.pendingSearch.remove(player.getUniqueId());
        this.openWorth(player, state);
        return true;
    }

    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length != 1) {
            return Collections.emptyList();
        }
        String prefix = args[0].toLowerCase(Locale.ROOT);
        return this.prices.keySet().stream().map(m -> KelvoraSMP.pretty(m).toLowerCase(Locale.ROOT)).filter(s -> s.startsWith(prefix)).distinct().sorted().limit(20L).toList();
    }

    /*
     * Exception decompiling
     */
    private void openWorth(Player player, SearchState state) {
        /*
         * This method has failed to decompile.  When submitting a bug report, please provide this stack trace, and (if you hold appropriate legal rights) the relevant class file.
         * 
         * java.lang.ClassCastException: class org.benf.cfr.reader.bytecode.analysis.opgraph.Op03SimpleStatement cannot be cast to class org.benf.cfr.reader.bytecode.analysis.opgraph.Op04StructuredStatement (org.benf.cfr.reader.bytecode.analysis.opgraph.Op03SimpleStatement and org.benf.cfr.reader.bytecode.analysis.opgraph.Op04StructuredStatement are in unnamed module of loader 'app')
         *     at org.benf.cfr.reader.bytecode.analysis.opgraph.op4rewriters.SwitchExpressionRewriter$LValueSingleUsageCheckingRewriter.rewriteExpression(SwitchExpressionRewriter.java:96)
         *     at org.benf.cfr.reader.bytecode.analysis.parse.expression.LValueExpression.applyExpressionRewriter(LValueExpression.java:84)
         *     at org.benf.cfr.reader.bytecode.analysis.parse.rewriters.AbstractExpressionRewriter.rewriteExpression(AbstractExpressionRewriter.java:14)
         *     at org.benf.cfr.reader.bytecode.analysis.parse.expression.ArithmeticOperation.applyExpressionRewriter(ArithmeticOperation.java:171)
         *     at org.benf.cfr.reader.bytecode.analysis.parse.rewriters.AbstractExpressionRewriter.rewriteExpression(AbstractExpressionRewriter.java:14)
         *     at org.benf.cfr.reader.bytecode.analysis.parse.expression.AssignmentExpression.applyExpressionRewriter(AssignmentExpression.java:68)
         *     at org.benf.cfr.reader.bytecode.analysis.parse.rewriters.AbstractExpressionRewriter.rewriteExpression(AbstractExpressionRewriter.java:14)
         *     at org.benf.cfr.reader.bytecode.analysis.parse.statement.AssignmentSimple.rewriteExpressions(AssignmentSimple.java:167)
         *     at org.benf.cfr.reader.bytecode.analysis.structured.statement.StructuredFor.rewriteExpressions(StructuredFor.java:194)
         *     at org.benf.cfr.reader.bytecode.analysis.opgraph.op4rewriters.transformers.ExpressionRewriterTransformer.transform(ExpressionRewriterTransformer.java:24)
         *     at org.benf.cfr.reader.bytecode.analysis.opgraph.Op04StructuredStatement.transform(Op04StructuredStatement.java:680)
         *     at org.benf.cfr.reader.bytecode.analysis.structured.statement.Block.transformStructuredChildren(Block.java:421)
         *     at org.benf.cfr.reader.bytecode.analysis.opgraph.op4rewriters.transformers.ExpressionRewriterTransformer.transform(ExpressionRewriterTransformer.java:25)
         *     at org.benf.cfr.reader.bytecode.analysis.opgraph.Op04StructuredStatement.transform(Op04StructuredStatement.java:680)
         *     at org.benf.cfr.reader.bytecode.analysis.opgraph.op4rewriters.SwitchExpressionRewriter.rewriteBlockSwitches(SwitchExpressionRewriter.java:140)
         *     at org.benf.cfr.reader.bytecode.analysis.opgraph.op4rewriters.SwitchExpressionRewriter.transform(SwitchExpressionRewriter.java:71)
         *     at org.benf.cfr.reader.bytecode.analysis.opgraph.Op04StructuredStatement.switchExpression(Op04StructuredStatement.java:101)
         *     at org.benf.cfr.reader.bytecode.CodeAnalyser.getAnalysisInner(CodeAnalyser.java:909)
         *     at org.benf.cfr.reader.bytecode.CodeAnalyser.getAnalysisOrWrapFail(CodeAnalyser.java:278)
         *     at org.benf.cfr.reader.bytecode.CodeAnalyser.getAnalysis(CodeAnalyser.java:201)
         *     at org.benf.cfr.reader.entities.attributes.AttributeCode.analyse(AttributeCode.java:94)
         *     at org.benf.cfr.reader.entities.Method.analyse(Method.java:531)
         *     at org.benf.cfr.reader.entities.ClassFile.analyseMid(ClassFile.java:1055)
         *     at org.benf.cfr.reader.entities.ClassFile.analyseTop(ClassFile.java:942)
         *     at org.benf.cfr.reader.Driver.doJarVersionTypes(Driver.java:257)
         *     at org.benf.cfr.reader.Driver.doJar(Driver.java:139)
         *     at org.benf.cfr.reader.CfrDriverImpl.analyse(CfrDriverImpl.java:76)
         *     at org.benf.cfr.reader.Main.main(Main.java:54)
         */
        throw new IllegalStateException("Decompilation failed");
    }

    private List<Entry> filtered(String query) {
        String q = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        ArrayList<Entry> out = new ArrayList<Entry>();
        for (Map.Entry<Material, Double> entry : this.prices.entrySet()) {
            String name = KelvoraSMP.pretty(entry.getKey()).toLowerCase(Locale.ROOT);
            String key = entry.getKey().name().toLowerCase(Locale.ROOT).replace('_', ' ');
            if (!q.isBlank() && !name.contains(q) && !key.contains(q) && !this.aliases(entry.getKey()).stream().anyMatch(a -> a.contains(q))) continue;
            out.add(new Entry(entry.getKey(), entry.getValue()));
        }
        return out;
    }

    private List<String> aliases(Material m) {
        ArrayList<String> aliases = new ArrayList<String>();
        String n = m.name().toLowerCase(Locale.ROOT).replace('_', ' ');
        aliases.add(n);
        if (n.contains("diamond")) {
            aliases.add("dia");
        }
        if (n.equals("chest boat")) {
            aliases.add("boat with chest");
        }
        if (n.equals("pink petals")) {
            aliases.add("petals");
        }
        return aliases;
    }

    private void control(Inventory inv, int slot, Material material, String label, String action, TextColor color) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName((Component)Component.text((String)label, (TextColor)color));
        meta.getPersistentDataContainer().set(this.controlKey, PersistentDataType.STRING, (Object)action);
        item.setItemMeta(meta);
        inv.setItem(slot, item);
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        HumanEntity humanEntity = e.getWhoClicked();
        if (!(humanEntity instanceof Player)) {
            return;
        }
        Player player = (Player)humanEntity;
        if (!e.getView().title().equals((Object)Component.text((String)"KELVORA  /  WORTH", (TextColor)GREEN))) {
            return;
        }
        e.setCancelled(true);
        if (e.getClickedInventory() == null || e.getClickedInventory() != e.getView().getTopInventory()) {
            return;
        }
        if (e.getClick() == ClickType.DOUBLE_CLICK || e.getClick() == ClickType.NUMBER_KEY || e.getClick() == ClickType.SWAP_OFFHAND) {
            return;
        }
        ItemStack clicked = e.getCurrentItem();
        if (clicked == null || !clicked.hasItemMeta()) {
            return;
        }
        String action = (String)clicked.getItemMeta().getPersistentDataContainer().get(this.controlKey, PersistentDataType.STRING);
        if (action == null) {
            return;
        }
        SearchState state = this.decode((String)player.getPersistentDataContainer().get(new NamespacedKey((Plugin)this, "worth-state"), PersistentDataType.STRING));
        switch (action) {
            case "prev": {
                --state.page;
                break;
            }
            case "next": {
                ++state.page;
                break;
            }
            case "sort": {
                state.sort = Sort.values()[(state.sort.ordinal() + 1) % Sort.values().length];
                break;
            }
            case "clear": {
                state.query = "";
                state.page = 0;
                break;
            }
            case "search": {
                state.awaitingChat = true;
                this.pendingSearch.put(player.getUniqueId(), state);
                player.closeInventory();
                player.sendMessage((Component)Component.text((String)"Type an item name to search. Type CANCEL to cancel; your search input will not be public.", (TextColor)GREEN));
                return;
            }
            default: {
                return;
            }
        }
        this.openWorth(player, state);
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (e.getView().title().equals((Object)Component.text((String)"KELVORA  /  WORTH", (TextColor)GREEN)) && e.getRawSlots().stream().anyMatch(slot -> slot < e.getView().getTopInventory().getSize())) {
            e.setCancelled(true);
        }
    }

    @EventHandler
    public void onChat(AsyncPlayerChatEvent e) {
        SearchState state = this.pendingSearch.get(e.getPlayer().getUniqueId());
        if (state == null || !state.awaitingChat) {
            return;
        }
        e.setCancelled(true);
        String input = e.getMessage().trim();
        Bukkit.getScheduler().runTask((Plugin)this, () -> {
            this.pendingSearch.remove(e.getPlayer().getUniqueId());
            if (input.equalsIgnoreCase("cancel")) {
                e.getPlayer().sendMessage((Component)Component.text((String)"Worth search cancelled.", (TextColor)NamedTextColor.GRAY));
                return;
            }
            state.query = input;
            state.page = 0;
            state.awaitingChat = false;
            this.openWorth(e.getPlayer(), state);
        });
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        this.pendingSearch.remove(e.getPlayer().getUniqueId());
    }

    private SearchState decode(String raw) {
        if (raw == null) {
            return new SearchState("");
        }
        String[] p = raw.split("\\|", -1);
        SearchState s = new SearchState(p.length > 0 ? p[0] : "");
        if (p.length > 1) {
            try {
                s.page = Integer.parseInt(p[1]);
            }
            catch (NumberFormatException numberFormatException) {
                // empty catch block
            }
        }
        if (p.length > 2) {
            try {
                s.sort = Sort.valueOf(p[2]);
            }
            catch (IllegalArgumentException illegalArgumentException) {
                // empty catch block
            }
        }
        return s;
    }

    private String encode(SearchState s) {
        return s.query.replace("|", " ").replace("\n", " ") + "|" + s.page + "|" + s.sort.name();
    }

    private static String pretty(Material m) {
        String[] words = m.name().toLowerCase(Locale.ROOT).split("_");
        return Arrays.stream(words).map(w -> w.isEmpty() ? w : Character.toUpperCase(w.charAt(0)) + w.substring(1)).collect(Collectors.joining(" "));
    }

    private static String money(double value) {
        return "$" + (value == Math.rint(value) ? String.format(Locale.US, "%.0f", value) : String.format(Locale.US, "%.2f", value));
    }

    private static int[] buildContentSlots() {
        int[] slots = new int[45];
        int at = 0;
        int i = 0;
        while (i < 45) {
            slots[at++] = i++;
        }
        return slots;
    }

    private static /* synthetic */ String lambda$openWorth$2(Entry e) {
        return KelvoraSMP.pretty(e.material()).toLowerCase(Locale.ROOT);
    }

    private static final class SearchState {
        String query;
        int page;
        Sort sort = Sort.AZ;
        boolean awaitingChat;

        SearchState(String query) {
            this.query = query;
        }
    }

    private static enum Sort {
        AZ,
        ZA,
        LOW,
        HIGH;

    }

    private record Entry(Material material, double price) {
    }
}

