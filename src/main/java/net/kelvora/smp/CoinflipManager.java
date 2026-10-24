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
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

/** Money-only coinflip with persisted escrow, locked outcomes and server-side GUI validation. */
// Build trigger: compile and publish the latest 2.6.0 source.
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
    private final Map<Inventory, Map<Integer, UUID>> challengeSlots = Collections.synchronizedMap(new WeakHashMap<>());
    private final Set<Inventory> protectedGuis = Collections.newSetFromMap(new WeakHashMap<>());
    private final Map<UUID, Long> chatCreate = new HashMap<>();
    private final List<String> history = new ArrayList<>();
    private final Set<UUID> joining = ConcurrentHashMap.newKeySet();

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
        if (combatManager.isInCombat(p)) { fail(p, "You cannot create a coinflip during combat."); return; }
        if (economy == null) connectEconomy();
        if (economy == null) { fail(p, "Money is unavailable right now. Try again later."); return; }
        if (!Double.isFinite(amount) || amount < 1 || amount > 1_000_000_000_000d || Math.abs(amount * 100 - Math.rint(amount * 100)) > 0.000001) {
            fail(p, "Wager must be between $1 and $1,000,000,000,000 (max 2 decimals)."); return;
        }
        if (challenges.values().stream().anyMatch(c -> c.creator.equals(p.getUniqueId()) && c.status.equals("OPEN"))) {
            fail(p, "You already have an open coinflip. Use /coinflip cancel."); return;
        }
        if (balance(p) + 0.000001 < amount) { fail(p, "You do not have enough Money."); return; }
        if (!money(p, amount, true)) { fail(p, "Your wager could not be reserved; no challenge was created."); return; }
        Challenge challenge = new Challenge(UUID.randomUUID(), p.getUniqueId(), p.getName(), amount);
        challenges.put(challenge.id, challenge);
        if (!save()) {
            challenges.remove(challenge.id);
            if (!money(p, amount, false)) {
                challenge.status = "REFUND_PENDING";
                challenges.put(challenge.id, challenge);
                save();
                plugin.getLogger().severe("Coinflip creation refund needs staff review for " + p.getUniqueId() + " amount " + amount);
                fail(p, "The challenge could not be saved and your refund needs staff review.");
            } else fail(p, "The challenge could not be saved. Your wager was returned.");
            return;
        }
        p.sendMessage(Component.text("Coinflip created for $" + fmt(amount) + ". Your wager is escrowed. Waiting for an opponent.", NamedTextColor.GREEN));
        p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.35f, 1.15f);
        for (Player online : Bukkit.getOnlinePlayers()) if (!online.equals(p)) online.sendMessage(Component.text(p.getName() + " opened a $" + fmt(amount) + " coinflip. Use /coinflip to join.", NamedTextColor.GOLD));
        openMain(p);
    }

    private void cancel(Player p) {
        if (combatManager.isInCombat(p)) { fail(p, "You cannot cancel a coinflip during combat."); return; }
        Challenge found = challenges.values().stream().filter(c -> c.status.equals("OPEN") && c.creator.equals(p.getUniqueId())).findFirst().orElse(null);
        if (found == null) { fail(p, "You have no unjoined coinflip to cancel."); return; }
        found.status = "REFUND_PENDING";
        challenges.put(found.id, found);
        if (!save()) { found.status = "OPEN"; challenges.put(found.id, found); fail(p, "The cancellation could not be saved safely; your challenge remains open."); return; }
        if (!money(p, found.amount, false)) {
            plugin.getLogger().severe("Coinflip refund pending for " + p.getUniqueId() + " amount " + found.amount + "; escrow record preserved.");
            fail(p, "Refund is pending because Money is temporarily unavailable. Contact staff if it does not resolve.");
            return;
        }
        challenges.remove(found.id);
        save();
        p.sendMessage(Component.text("Coinflip cancelled. Your $" + fmt(found.amount) + " wager was returned.", NamedTextColor.YELLOW));
        p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, .25f, .9f);
        openMain(p);
    }

    private void openMain(Player p) {
        Inventory inv = Bukkit.createInventory(null, 54, Component.text(MAIN, NamedTextColor.GREEN));
        protectedGuis.add(inv);
        Map<Integer, UUID> slotChallenges = new HashMap<>();
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
            inv.setItem(slot, head);
            slotChallenges.put(slot, c.id);
            slot++;
        }
        if (slot == 9) inv.setItem(22, item(Material.PAPER, "No Open Coinflips", List.of("Create one and wait for an opponent.")));
        inv.setItem(45, item(Material.WRITABLE_BOOK, "Latest Coinflips", history.isEmpty() ? List.of("No completed coinflips yet.") :
            history.stream().limit(6).map(s -> s.length() > 45 ? s.substring(0,45) : s).map(s -> Component.text(s, NamedTextColor.GRAY)).toList()));
        inv.setItem(49, item(Material.EMERALD, "Your Money", List.of("$" + fmt(Math.max(0, balance(p))))));
        inv.setItem(53, item(Material.BARRIER, "Close", null));
        challengeSlots.put(inv, slotChallenges);
        p.openInventory(inv);
    }

    private void openConfirm(Player p, Challenge c) {
        confirmations.put(p.getUniqueId(), c.id);
        Inventory inv = Bukkit.createInventory(null, 27, Component.text(CONFIRM, NamedTextColor.GOLD));
        protectedGuis.add(inv);
        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta sm = (SkullMeta) head.getItemMeta(); sm.setOwningPlayer(Bukkit.getOfflinePlayer(c.creator));
        sm.displayName(Component.text(c.creatorName, NamedTextColor.GREEN)); head.setItemMeta(sm);
        inv.setItem(11, head);
        inv.setItem(13, item(Material.DIAMOND, "Join Coinflip", List.of("Your wager: $" + fmt(c.amount), "Total pot: $" + fmt(c.amount * 2), "Winner receives the full pot.", "Click to confirm and escrow your wager.")));
        inv.setItem(15, item(Material.BARRIER, "Back", null));
        p.openInventory(inv);
    }

    private void join(Player p, UUID id) {
        if (combatManager.isInCombat(p)) { fail(p, "You cannot join a coinflip during combat."); return; }
        Challenge c = challenges.get(id);
        if (c == null || !c.status.equals("OPEN")) { fail(p, "That coinflip is no longer available."); openMain(p); return; }
        if (c.creator.equals(p.getUniqueId())) { fail(p, "You cannot join your own coinflip."); openMain(p); return; }
        if (!joining.add(id)) { fail(p, "That coinflip is already being joined."); return; }
        try {
            Player creator = Bukkit.getPlayer(c.creator);
            if (creator == null) {
                c.status = "REFUND_PENDING";
                challenges.put(c.id, c);
                if (!save()) { fail(p, "Creator disconnected and the refund record could not be saved; staff review is required."); return; }
                if (money(Bukkit.getOfflinePlayer(c.creator), c.amount, false)) {
                    challenges.remove(c.id);
                    save();
                } else {
                    plugin.getLogger().severe("Coinflip creator refund requires staff review for challenge " + c.id);
                }
                fail(p, "Creator disconnected; the challenge was cancelled.");
                return;
            }
            if (combatManager.isInCombat(creator)) { fail(p, "The creator is in combat; you cannot join right now."); return; }
            if (balance(p) + 0.000001 < c.amount) { fail(p, "You do not have enough Money."); return; }

            // Persist a JOINING reservation before the opponent's balance changes.
            c.status = "JOINING";
            c.opponent = p.getUniqueId();
            c.opponentName = p.getName();
            c.pot = c.amount * 2;
            c.winner = null;
            if (!save()) {
                c.status = "OPEN"; c.opponent = null; c.opponentName = ""; c.pot = 0;
                challenges.put(c.id, c);
                fail(p, "Could not reserve this challenge safely. No wager was taken.");
                return;
            }
            if (!money(p, c.amount, true)) {
                c.status = "OPEN"; c.opponent = null; c.opponentName = ""; c.pot = 0;
                challenges.put(c.id, c);
                save();
                fail(p, "Your wager could not be reserved.");
                return;
            }

            c.status = "RUNNING";
            c.winner = ThreadLocalRandom.current().nextBoolean() ? c.creator : p.getUniqueId();
            challenges.put(c.id, c);
            if (!save()) {
                // Do not start the animation unless the locked result is persisted.
                boolean opponentRefunded = money(p, c.amount, false);
                boolean creatorRefunded = money(creator, c.amount, false);
                if (opponentRefunded && creatorRefunded) {
                    challenges.remove(c.id);
                    save();
                    fail(p, "The match could not be saved. Both wagers were returned.");
                    creator.sendMessage(Component.text("The match could not be saved. Your wager was returned.", NamedTextColor.YELLOW));
                } else {
                    c.status = "PAYOUT_PENDING";
                    challenges.put(c.id, c);
                    save();
                    plugin.getLogger().severe("Coinflip match reservation needs staff reconciliation: " + c.id + "; opponentRefunded=" + opponentRefunded + "; creatorRefunded=" + creatorRefunded);
                    fail(p, "The match could not be saved safely. Staff must reconcile the wagers; do not retry.");
                    creator.sendMessage(Component.text("Coinflip requires staff reconciliation. Do not create another match; contact staff.", NamedTextColor.RED));
                }
                return;
            }
            p.playSound(p.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, .35f, 1.15f);
            creator.playSound(creator.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, .35f, 1.15f);
            animate(c, creator, p, 0);
        } finally {
            joining.remove(id);
        }
    }

    private void animate(Challenge c, Player a, Player b, int step) {
        if (step == 0) {
            Inventory ia = Bukkit.createInventory(null, 27, Component.text(ANIMATION, NamedTextColor.GOLD));
            Inventory ib = Bukkit.createInventory(null, 27, Component.text(ANIMATION, NamedTextColor.GOLD));
            protectedGuis.add(ia); protectedGuis.add(ib);
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
            protectedGuis.add(inv);
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
        Inventory top = e.getView().getTopInventory();
        if (!protectedGuis.contains(top)) return;
        // Cancel every click mode while one of our exact inventory instances is open:
        // shift-click, number-key, offhand, double-click, creative and bottom-inventory clicks too.
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p) || e.getClickedInventory() != top) return;
        p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, .18f, 1.1f);
        String title = e.getView().getTitle();
        int slot = e.getRawSlot();
        if (title.equals(MAIN)) {
            if (slot == 53) { p.closeInventory(); return; }
            if (slot == 8) { openMain(p); return; }
            if (slot == 4) { chatCreate.put(p.getUniqueId(), System.currentTimeMillis()); p.closeInventory(); p.sendMessage(Component.text("Type the wager amount in chat, or use /coinflip create <amount>. Type cancel to stop.", NamedTextColor.GOLD)); return; }
            UUID challengeId = challengeSlots.getOrDefault(top, Map.of()).get(slot);
            if (challengeId != null) {
                Challenge current = challenges.get(challengeId);
                if (current == null || !current.status.equals("OPEN")) {
                    p.sendMessage(Component.text("That coinflip is no longer available. Refresh and try again.", NamedTextColor.RED));
                    openMain(p);
                } else openConfirm(p, current);
            }
        } else if (title.equals(CONFIRM)) {
            UUID id = confirmations.get(p.getUniqueId());
            if (slot == 13) {
                confirmations.remove(p.getUniqueId());
                if (id != null) join(p, id);
                else { p.sendMessage(Component.text("Challenge expired. Open /coinflip again.", NamedTextColor.RED)); openMain(p); }
            } else if (slot == 15) {
                confirmations.remove(p.getUniqueId());
                openMain(p);
            }
        }
    }

    @EventHandler public void onDrag(InventoryDragEvent e) {
        if (protectedGuis.contains(e.getView().getTopInventory())) e.setCancelled(true);
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
                } else if (status.equals("JOINING")) {
                    // A crash may have occurred around the opponent's withdrawal. Never auto-pay twice.
                    challenges.put(c.id, c);
                    plugin.getLogger().severe("Coinflip JOINING transaction requires staff reconciliation (no automatic refund/payout): " + c.id);
                } else if (status.equals("RUNNING") && c.winner != null) {
                    // Vault has no generic idempotency key. A crash could happen immediately
                    // after a deposit but before saving PAID, so never auto-deposit on recovery.
                    c.status = "PAYOUT_PENDING";
                    challenges.put(c.id, c);
                    plugin.getLogger().severe("Interrupted coinflip payout preserved for staff reconciliation (no automatic retry): " + c.id);
                } else if (status.equals("REFUND_PENDING")) {
                    // The previous provider response may have been lost after accepting the refund.
                    // Keep a durable claim for staff review rather than risk issuing the refund twice.
                    challenges.put(c.id, c);
                    plugin.getLogger().severe("Coinflip refund preserved for staff reconciliation (no automatic retry): " + c.id);
                } else if (status.equals("PAYOUT_PENDING")) {
                    // Do not retry automatically: generic Vault providers cannot guarantee exactly-once deposits.
                    challenges.put(c.id, c);
                }
            } catch (Exception ex) { plugin.getLogger().warning("Skipping malformed coinflip record " + key); }
        }
        history.addAll(data.getStringList("history"));
        save();
    }

    private boolean save() {
        if (data == null) data = new YamlConfiguration();
        for (String key : new HashSet<>(data.getKeys(false))) if (!key.equals("history")) data.set(key, null);
        for (Challenge c : challenges.values()) {
            String k = c.id.toString();
            data.set(k + ".creator", c.creator.toString());
            data.set(k + ".creatorName", c.creatorName);
            data.set(k + ".amount", c.amount);
            data.set(k + ".status", c.status);
            data.set(k + ".opponentName", c.opponentName);
            data.set(k + ".pot", c.pot);
            data.set(k + ".paid", c.paid);
            if (c.opponent != null) data.set(k + ".opponent", c.opponent.toString());
            else data.set(k + ".opponent", null);
            if (c.winner != null) data.set(k + ".winner", c.winner.toString());
            else data.set(k + ".winner", null);
        }
        data.set("history", new ArrayList<>(history));
        File temp = new File(plugin.getDataFolder(), "coinflip.yml.tmp");
        try {
            if (!plugin.getDataFolder().exists() && !plugin.getDataFolder().mkdirs()) throw new IOException("Could not create plugin data directory");
            data.save(temp);
            try {
                java.nio.file.Files.move(temp.toPath(), file.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException ex) {
                java.nio.file.Files.move(temp.toPath(), file.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            return true;
        } catch (Exception ex) {
            try { java.nio.file.Files.deleteIfExists(temp.toPath()); } catch (Exception ignored) {}
            plugin.getLogger().severe("Could not save coinflip escrow file: " + ex.getMessage());
            return false;
        }
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
