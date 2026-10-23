package net.kelvora.smp;

import java.io.File;
import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

/** Money-only coinflip with persisted escrow, locked outcomes and server-side GUI validation. */
final class CoinflipManager implements Listener {
    private static final String MAIN = "KELVORA / COINFLIP";
    private static final String CONFIRM = "KELVORA / JOIN COINFLIP";
    private static final String ANIMATION = "KELVORA / COINFLIP RESULT";
    private final JavaPlugin plugin;
    private final BountyCombatManager combatManager;
    private final File file;
    private YamlConfiguration data;
    private Object economy;
    private final Map<UUID, Challenge> challenges = new LinkedHashMap<>();
    private final Map<UUID, UUID> confirmations = new HashMap<>();
    private final Map<UUID, Long> chatCreate = new HashMap<>();
    private final List<String> history = new ArrayList<>();

    CoinflipManager(JavaPlugin plugin, BountyCombatManager combatManager) {
        this.plugin = plugin;
        this.combatManager = combatManager;
        this.file = new File(plugin.getDataFolder(), "coinflip.yml");
    }

    void register() {
        connectEconomy();
        load();
        Bukkit.getPluginManager().registerEvents(this, plugin);
        if (plugin.getCommand("coinflip") != null) {
            plugin.getCommand("coinflip").setExecutor((sender, command, label, args) -> {
                if (!(sender instanceof Player p)) { sender.sendMessage("Use this command in-game."); return true; }
                if (args.length == 0) { openMain(p); return true; }
                switch (args[0].toLowerCase(Locale.ROOT)) {
                    case "create" -> {
                        if (args.length != 2) { help(p); return true; }
                        try { create(p, Double.parseDouble(args[1])); }
                        catch (NumberFormatException ex) { p.sendMessage(Component.text("Enter a valid wager amount.", NamedTextColor.RED)); }
                    }
                    case "cancel" -> cancel(p);
                    case "history" -> showHistory(p);
                    case "help" -> help(p);
                    default -> help(p);
                }
                return true;
            });
            plugin.getCommand("coinflip").setTabCompleter((sender, command, alias, args) -> {
                if (args.length == 1) return List.of("create", "cancel", "history", "help").stream()
                    .filter(s -> s.startsWith(args[0].toLowerCase(Locale.ROOT))).toList();
                return Collections.emptyList();
            });
        }
        Bukkit.getScheduler().runTask(plugin, this::connectEconomy);
        plugin.getLogger().info("Coinflip system loaded (" + challenges.size() + " open challenges).");
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
            plugin.getLogger().warning("Coinflip could not connect to Vault Money: " + ex.getMessage());
        }
    }

    private boolean money(org.bukkit.OfflinePlayer p, double amount, boolean withdraw) {
        if (economy == null) connectEconomy();
        if (economy == null || !Double.isFinite(amount) || amount <= 0) return false;
        try {
            Method method = economy.getClass().getMethod(withdraw ? "withdrawPlayer" : "depositPlayer",
                org.bukkit.OfflinePlayer.class, double.class);
            Object response = method.invoke(economy, p, amount);
            if (response == null) return false;
            try { return Boolean.TRUE.equals(response.getClass().getMethod("transactionSuccess").invoke(response)); }
            catch (ReflectiveOperationException ignored) {
                Object type = response.getClass().getMethod("type").invoke(response);
                return type != null && type.toString().equalsIgnoreCase("SUCCESS");
            }
        } catch (ReflectiveOperationException | LinkageError ex) {
            plugin.getLogger().warning("Coinflip Money transaction failed: " + ex.getMessage());
            return false;
        }
    }

    private double balance(Player p) {
        if (economy == null) connectEconomy();
        try {
            Object n = economy.getClass().getMethod("getBalance", org.bukkit.OfflinePlayer.class).invoke(economy, p);
            return n instanceof Number ? ((Number)n).doubleValue() : -1;
        } catch (Exception ex) { return -1; }
    }

    private void create(Player p, double amount) {
        if (combatManager.isInCombat(p)) { p.sendMessage(Component.text("You cannot create a coinflip during combat.", NamedTextColor.RED)); return; }
        if (economy == null) connectEconomy();
        if (economy == null) { p.sendMessage(Component.text("Money is unavailable right now. Try again later.", NamedTextColor.RED)); return; }
        if (!Double.isFinite(amount) || amount < 1 || amount > 1_000_000_000_000d || Math.abs(amount * 100 - Math.rint(amount * 100)) > 0.000001) {
            p.sendMessage(Component.text("Wager must be between $1 and $1,000,000,000,000 (max 2 decimals).", NamedTextColor.RED)); return;
        }
        if (challenges.values().stream().anyMatch(c -> c.creator.equals(p.getUniqueId()) && c.status.equals("OPEN"))) {
            p.sendMessage(Component.text("You already have an open coinflip. Use /coinflip cancel.", NamedTextColor.RED)); return;
        }
        if (balance(p) + 0.000001 < amount) { p.sendMessage(Component.text("You do not have enough Money.", NamedTextColor.RED)); return; }
        if (!money(p, amount, true)) { p.sendMessage(Component.text("Your wager could not be reserved; no challenge was created.", NamedTextColor.RED)); return; }
        Challenge c = new Challenge(UUID.randomUUID(), p.getUniqueId(), p.getName(), amount);
        challenges.put(c.id, c);
        save();
        p.sendMessage(Component.text("Coinflip created for $" + fmt(amount) + ". Your wager is escrowed. Waiting for an opponent.", NamedTextColor.GREEN));
        for (Player online : Bukkit.getOnlinePlayers()) if (!online.equals(p)) online.sendMessage(Component.text(p.getName() + " opened a $" + fmt(amount) + " coinflip. Use /coinflip to join.", NamedTextColor.GOLD));
        openMain(p);
    }

    private void cancel(Player p) {
        if (combatManager.isInCombat(p)) { p.sendMessage(Component.text("You cannot cancel a coinflip during combat.", NamedTextColor.RED)); return; }
        Challenge found = challenges.values().stream().filter(c -> c.creator.equals(p.getUniqueId()) && c.status.equals("OPEN")).findFirst().orElse(null);
        if (found == null) { p.sendMessage(Component.text("You have no unjoined coinflip to cancel.", NamedTextColor.RED)); return; }
        challenges.remove(found.id);
        save();
        if (!money(p, found.amount, false)) {
            // Keep a durable refund claim so the escrow is never silently discarded.
            found.status = "REFUND_PENDING"; challenges.put(found.id, found); save();
            p.sendMessage(Component.text("Refund is pending because Money is temporarily unavailable. Contact staff if it does not resolve.", NamedTextColor.RED));
            return;
        }
        p.sendMessage(Component.text("Coinflip cancelled. Your $" + fmt(found.amount) + " wager was returned.", NamedTextColor.YELLOW));
        openMain(p);
    }

    private void openMain(Player p) {
        Inventory inv = Bukkit.createInventory(null, 54, Component.text(MAIN, NamedTextColor.GREEN));
        for (int i = 0; i < 9; i++) inv.setItem(i, item(Material.LIME_STAINED_GLASS_PANE, " ", null));
        inv.setItem(4, item(Material.GOLD_INGOT, "Create Coinflip", List.of("Click, then enter the wager in chat", "or use /coinflip create <amount>")));
        inv.setItem(8, item(Material.CLOCK, "Refresh", List.of("Refresh open challenges")));
        int slot = 9;
        for (Challenge c : challenges.values()) {
            if (!c.status.equals("OPEN") || slot >= 45) continue;
            ItemStack head = new ItemStack(Material.PLAYER_HEAD);
            SkullMeta meta = (SkullMeta) head.getItemMeta();
            meta.setOwningPlayer(Bukkit.getOfflinePlayer(c.creator));
            meta.displayName(Component.text(c.creatorName, NamedTextColor.GREEN));
            meta.lore(List.of(Component.text("Wager: $" + fmt(c.amount), NamedTextColor.GOLD),
                Component.text("Total pot: $" + fmt(c.amount * 2), NamedTextColor.YELLOW),
                Component.text("Status: Waiting for Player", NamedTextColor.AQUA),
                Component.text("Click to review and join", NamedTextColor.GRAY)));
            head.setItemMeta(meta);
            inv.setItem(slot++, head);
        }
        if (slot == 9) inv.setItem(22, item(Material.PAPER, "No Open Coinflips", List.of("Create one and wait for an opponent.")));
        inv.setItem(45, item(Material.WRITABLE_BOOK, "Latest Coinflips", history.isEmpty() ? List.of("No completed coinflips yet.") :
            history.stream().limit(6).map(s -> s.length() > 45 ? s.substring(0,45) : s).map(s -> Component.text(s, NamedTextColor.GRAY)).toList()));
        inv.setItem(49, item(Material.EMERALD, "Your Money", List.of("$" + fmt(Math.max(0, balance(p))))));
        inv.setItem(53, item(Material.BARRIER, "Close", null));
        p.openInventory(inv);
    }

    private void openConfirm(Player p, Challenge c) {
        confirmations.put(p.getUniqueId(), c.id);
        Inventory inv = Bukkit.createInventory(null, 27, Component.text(CONFIRM, NamedTextColor.GOLD));
        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta sm = (SkullMeta) head.getItemMeta(); sm.setOwningPlayer(Bukkit.getOfflinePlayer(c.creator));
        sm.displayName(Component.text(c.creatorName, NamedTextColor.GREEN)); head.setItemMeta(sm);
        inv.setItem(11, head);
        inv.setItem(13, item(Material.DIAMOND, "Join Coinflip", List.of("Your wager: $" + fmt(c.amount), "Total pot: $" + fmt(c.amount * 2), "Winner receives the full pot.", "Click to confirm and escrow your wager.")));
        inv.setItem(15, item(Material.BARRIER, "Back", null));
        p.openInventory(inv);
    }

    private void join(Player p, UUID id) {
        if (combatManager.isInCombat(p)) { p.sendMessage(Component.text("You cannot join a coinflip during combat.", NamedTextColor.RED)); return; }
        Challenge c = challenges.get(id);
        if (c == null || !c.status.equals("OPEN")) { p.sendMessage(Component.text("That coinflip is no longer available.", NamedTextColor.RED)); openMain(p); return; }
        if (c.creator.equals(p.getUniqueId())) { p.sendMessage(Component.text("You cannot join your own coinflip.", NamedTextColor.RED)); return; }
        if (p.getWorld().getName().isBlank()) return;
        Player creator = Bukkit.getPlayer(c.creator);
        if (creator == null) {
            // The creator's escrow is still owed even if their challenge owner disconnected.
            if (money(Bukkit.getOfflinePlayer(c.creator), c.amount, false)) {
                challenges.remove(c.id);
            } else {
                c.status = "REFUND_PENDING";
                challenges.put(c.id, c);
            }
            save();
            p.sendMessage(Component.text("Creator disconnected; the challenge was cancelled.", NamedTextColor.YELLOW));
            return;
        }
        if (balance(p) + 0.000001 < c.amount) { p.sendMessage(Component.text("You do not have enough Money.", NamedTextColor.RED)); return; }
        if (!money(p, c.amount, true)) { p.sendMessage(Component.text("Your wager could not be reserved.", NamedTextColor.RED)); return; }
        // Lock and persist the match and fair server-side result before starting any animation.
        c.status = "RUNNING"; c.opponent = p.getUniqueId(); c.opponentName = p.getName();
        c.winner = ThreadLocalRandom.current().nextBoolean() ? c.creator : p.getUniqueId();
        c.pot = c.amount * 2;
        save();
        animate(c, creator, p, 0);
    }

    private void animate(Challenge c, Player a, Player b, int step) {
        if (step == 0) {
            Inventory ia = Bukkit.createInventory(null, 27, Component.text(ANIMATION, NamedTextColor.GOLD));
            Inventory ib = Bukkit.createInventory(null, 27, Component.text(ANIMATION, NamedTextColor.GOLD));
            fillAnimation(ia, c, step); fillAnimation(ib, c, step);
            a.openInventory(ia); b.openInventory(ib);
        }
        if (step < 12) {
            ItemStack left = playerHead(step % 2 == 0 ? c.creator : c.opponent, step % 2 == 0 ? c.creatorName : c.opponentName);
            ItemStack right = playerHead(step % 2 == 0 ? c.opponent : c.creator, step % 2 == 0 ? c.opponentName : c.creatorName);
            for (Player viewer : List.of(a,b)) {
                if (!viewer.isOnline() || !viewer.getOpenInventory().getTitle().equals(ANIMATION)) continue;
                Inventory inv = viewer.getOpenInventory().getTopInventory();
                inv.setItem(11, left); inv.setItem(15, right);
                viewer.playSound(viewer.getLocation(), Sound.UI_BUTTON_CLICK, 0.25f, 1.2f + step * 0.04f);
            }
            Bukkit.getScheduler().runTaskLater(plugin, () -> animate(c, a, b, step + 1), 4L);
            return;
        }
        Player winner = Bukkit.getPlayer(c.winner);
        Player loser = Bukkit.getPlayer(c.winner.equals(c.creator) ? c.opponent : c.creator);
        for (Player viewer : List.of(a,b)) {
            if (!viewer.isOnline()) continue;
            // Never clear an unrelated inventory if a participant closed or replaced the animation GUI.
            Inventory inv = Bukkit.createInventory(null, 27, Component.text(ANIMATION, NamedTextColor.GOLD));
            inv.setItem(4, item(Material.DIAMOND_BLOCK, "WINNING POSITION", List.of("Server-side result locked")));
            inv.setItem(13, playerHead(c.winner, c.winner.equals(c.creator) ? c.creatorName : c.opponentName));
            inv.setItem(22, item(Material.GOLD_INGOT, (c.winner.equals(viewer.getUniqueId()) ? "YOU WIN" : "YOU LOSE"),
                List.of("Winner: " + (c.winner.equals(c.creator) ? c.creatorName : c.opponentName), "Wager each: $" + fmt(c.amount), "Total pot: $" + fmt(c.pot))));
            viewer.openInventory(inv);
            viewer.playSound(viewer.getLocation(), c.winner.equals(viewer.getUniqueId()) ? Sound.UI_TOAST_CHALLENGE_COMPLETE : Sound.BLOCK_NOTE_BLOCK_BASS, 0.6f, 1f);
        }
        settle(c, winner, loser);
    }

    private void fillAnimation(Inventory inv, Challenge c, int step) {
        for (int i=0;i<27;i++) inv.setItem(i, item(Material.BLACK_STAINED_GLASS_PANE, " ", null));
        inv.setItem(13, item(Material.DIAMOND_BLOCK, "Winning Position", List.of("The outcome is determined server-side.")));
        inv.setItem(11, playerHead(c.creator,c.creatorName)); inv.setItem(15, playerHead(c.opponent,c.opponentName));
    }

    private void settle(Challenge c, Player winner, Player loser) {
        if (c.paid || "PAID".equals(c.status) || "PAYOUT_PENDING".equals(c.status)) return;
        String winnerName = c.winner.equals(c.creator) ? c.creatorName : c.opponentName;
        // Persist a recoverable claim before calling Vault. Since generic Vault providers do not
        // offer idempotent transaction IDs, a crash during the deposit must be reviewed rather
        // than automatically paying twice or silently deleting the escrow record.
        c.status = "PAYOUT_PENDING";
        challenges.put(c.id, c);
        save();
        boolean paid = c.winner != null && money(Bukkit.getOfflinePlayer(c.winner), c.pot, false);
        if (paid) {
            c.paid = true;
            c.status = "PAID";
            challenges.remove(c.id);
            history.add(0, c.creatorName + " vs " + c.opponentName + " | winner " + winnerName + " | $" + fmt(c.pot));
            while (history.size() > 10) history.remove(history.size()-1);
            save();
            if (winner != null) winner.sendMessage(Component.text("Coinflip won! You received $" + fmt(c.pot) + ".", NamedTextColor.GREEN));
            if (loser != null) loser.sendMessage(Component.text("Coinflip lost. $" + fmt(c.amount) + " wager was lost.", NamedTextColor.RED));
        } else {
            // Preserve the unresolved escrow/payout record; never retry automatically because
            // the provider may have accepted the deposit just before a crash or timeout.
            plugin.getLogger().severe("Coinflip payout requires staff review for match " + c.id + " winner=" + winnerName + " pot=" + c.pot);
            if (winner != null) winner.sendMessage(Component.text("The result is locked, but payout needs staff review. Your wager has not been silently discarded.", NamedTextColor.RED));
        }
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (winner != null && winner.isOnline()) openMain(winner);
            if (loser != null && loser.isOnline()) openMain(loser);
        }, 50L);
    }

    @EventHandler public void onClick(InventoryClickEvent e) {
        String title = e.getView().getTitle();
        if (!title.equals(MAIN) && !title.equals(CONFIRM) && !title.equals(ANIMATION)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p) || e.getClickedInventory() != e.getView().getTopInventory()) return;
        if (title.equals(MAIN)) {
            if (e.getSlot() == 53) { p.closeInventory(); return; }
            if (e.getSlot() == 8) { openMain(p); return; }
            if (e.getSlot() == 4) { chatCreate.put(p.getUniqueId(), System.currentTimeMillis()); p.closeInventory(); p.sendMessage(Component.text("Type the wager amount in chat, or use /coinflip create <amount>. Type cancel to stop.", NamedTextColor.GOLD)); return; }
            ItemStack clicked = e.getCurrentItem();
            if (clicked != null && clicked.getType() == Material.PLAYER_HEAD) {
                int slot = e.getSlot(); int index = slot - 9;
                List<Challenge> list = challenges.values().stream().filter(c -> c.status.equals("OPEN")).toList();
                if (index >= 0 && index < list.size()) openConfirm(p, list.get(index));
            }
        } else if (title.equals(CONFIRM)) {
            UUID id = confirmations.remove(p.getUniqueId());
            if (e.getSlot() == 13 && id != null) join(p, id);
            else if (e.getSlot() == 15) openMain(p);
            else if (e.getSlot() == 13) p.sendMessage(Component.text("Challenge expired.", NamedTextColor.RED));
        }
    }

    @EventHandler public void onChat(AsyncPlayerChatEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        if (!chatCreate.containsKey(id)) return;
        e.setCancelled(true); String msg = e.getMessage().trim();
        Bukkit.getScheduler().runTask(plugin, () -> {
            chatCreate.remove(id);
            if (msg.equalsIgnoreCase("cancel")) { e.getPlayer().sendMessage(Component.text("Creation cancelled.", NamedTextColor.YELLOW)); return; }
            try { create(e.getPlayer(), Double.parseDouble(msg)); }
            catch (NumberFormatException ex) { e.getPlayer().sendMessage(Component.text("Invalid amount. Use /coinflip create <amount>.", NamedTextColor.RED)); }
        });
    }

    @EventHandler public void onQuit(PlayerQuitEvent e) {
        chatCreate.remove(e.getPlayer().getUniqueId()); confirmations.remove(e.getPlayer().getUniqueId());
        UUID id = e.getPlayer().getUniqueId();
        Challenge c = challenges.values().stream().filter(x -> x.status.equals("OPEN") && x.creator.equals(id)).findFirst().orElse(null);
        if (c != null) {
            c.status = "REFUND_PENDING";
            challenges.put(c.id, c);
            save();
            // Vault accepts OfflinePlayer; don't depend on Bukkit.getPlayer after quit (it is normally null).
            if (money(Bukkit.getOfflinePlayer(id), c.amount, false)) {
                challenges.remove(c.id);
                save();
            } else {
                plugin.getLogger().severe("Coinflip refund pending for " + id + " amount " + c.amount + "; escrow record preserved.");
            }
        }
    }

    private void load() {
        if (!plugin.getDataFolder().exists()) plugin.getDataFolder().mkdirs();
        data = YamlConfiguration.loadConfiguration(file);
        for (String key : data.getKeys(false)) {
            if (key.equals("history")) continue;
            try {
                UUID id = UUID.fromString(key); String status = data.getString(key+".status","OPEN");
                Challenge c = new Challenge(id, UUID.fromString(data.getString(key+".creator")), data.getString(key+".creatorName","Player"), data.getDouble(key+".amount"));
                c.status = status; c.opponentName = data.getString(key+".opponentName",""); c.pot = data.getDouble(key+".pot");
                if (data.getString(key+".opponent") != null) c.opponent = UUID.fromString(data.getString(key+".opponent"));
                if (data.getString(key+".winner") != null) c.winner = UUID.fromString(data.getString(key+".winner"));
                c.paid = data.getBoolean(key+".paid",false);
                if (status.equals("OPEN")) {
                    // An open challenge from a prior server session has no valid live opponent; refund escrow on startup.
                    Player creator = Bukkit.getPlayer(c.creator);
                    if (money(Bukkit.getOfflinePlayer(c.creator),c.amount,false)) continue;
                    c.status = "REFUND_PENDING"; challenges.put(c.id,c);
                } else if (status.equals("RUNNING") && c.winner != null) {
                    // Outcome was already locked before the crash. Mark payout claimed before contacting Vault,
                    // and preserve the record if the provider cannot confirm the deposit.
                    c.paid = true;
                    if (money(Bukkit.getOfflinePlayer(c.winner), c.pot, false)) {
                        plugin.getLogger().info("Recovered interrupted coinflip payout " + c.id);
                    } else {
                        c.status = "PAYOUT_PENDING";
                        challenges.put(c.id, c);
                        plugin.getLogger().severe("Interrupted coinflip payout needs staff review; durable record preserved: " + c.id);
                    }
                } else if (status.equals("REFUND_PENDING")) {
                    if (!money(Bukkit.getOfflinePlayer(c.creator), c.amount, false)) challenges.put(c.id, c);
                    else plugin.getLogger().info("Recovered pending coinflip refund " + c.id);
                } else if (status.equals("PAYOUT_PENDING")) {
                    c.paid = true;
                    challenges.put(c.id, c);
                }
            } catch (Exception ex) { plugin.getLogger().warning("Skipping malformed coinflip record " + key); }
        }
        history.addAll(data.getStringList("history"));
        save();
    }

    private void save() {
        if (data == null) data = new YamlConfiguration();
        for (String key : new HashSet<>(data.getKeys(false))) if (!key.equals("history")) data.set(key,null);
        for (Challenge c : challenges.values()) {
            String k=c.id.toString(); data.set(k+".creator",c.creator.toString()); data.set(k+".creatorName",c.creatorName);
            data.set(k+".amount",c.amount); data.set(k+".status",c.status); data.set(k+".opponentName",c.opponentName);
            data.set(k+".pot",c.pot); data.set(k+".paid",c.paid);
            if (c.opponent != null) data.set(k+".opponent",c.opponent.toString());
            if (c.winner != null) data.set(k+".winner",c.winner.toString());
        }
        data.set("history",new ArrayList<>(history));
        try { data.save(file); } catch (Exception ex) { plugin.getLogger().severe("Could not save coinflip escrow file: "+ex.getMessage()); }
    }

    private void showHistory(Player p) {
        p.sendMessage(Component.text("Recent coinflips:", NamedTextColor.GOLD));
        if (history.isEmpty()) p.sendMessage(Component.text("No results yet.", NamedTextColor.GRAY));
        else history.stream().limit(10).forEach(s -> p.sendMessage(Component.text(s, NamedTextColor.GRAY)));
    }
    private void help(Player p) {
        p.sendMessage(Component.text("/coinflip - open GUI", NamedTextColor.GREEN));
        p.sendMessage(Component.text("/coinflip create <amount> - create challenge", NamedTextColor.GREEN));
        p.sendMessage(Component.text("/coinflip cancel | history | help", NamedTextColor.GREEN));
    }
    private ItemStack playerHead(UUID id, String name) {
        ItemStack item = new ItemStack(Material.PLAYER_HEAD); SkullMeta meta = (SkullMeta)item.getItemMeta();
        if (id != null) meta.setOwningPlayer(Bukkit.getOfflinePlayer(id)); meta.displayName(Component.text(name, NamedTextColor.GREEN)); item.setItemMeta(meta); return item;
    }
    private ItemStack item(Material material, String name, List<?> lore) {
        ItemStack item = new ItemStack(material); var meta=item.getItemMeta(); meta.displayName(Component.text(name, NamedTextColor.WHITE));
        if (lore != null) meta.lore(lore.stream().map(s -> s instanceof Component component ? component : Component.text(String.valueOf(s), NamedTextColor.GRAY)).toList());
        item.setItemMeta(meta); return item;
    }
    private String fmt(double n) { return String.format(Locale.US, "%,.2f", n).replaceAll("\\.00$",""); }

    private static final class Challenge {
        final UUID id, creator; final String creatorName; final double amount;
        String status="OPEN", opponentName=""; UUID opponent, winner; double pot; boolean paid;
        Challenge(UUID id, UUID creator, String creatorName, double amount) { this.id=id; this.creator=creator; this.creatorName=creatorName; this.amount=amount; }
    }
}
