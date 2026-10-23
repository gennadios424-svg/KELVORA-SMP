package net.kelvora.smp;

import java.io.File;
import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.entity.*;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.NamespacedKey;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

/** Money bounties and a 20-second PvP combat tag. */
final class BountyCombatManager implements Listener {
    private static final long COMBAT_MS = 20_000L;
    private static final long INDIRECT_MS = 8_000L;
    private static final String BOUNTY_TITLE = "KELVORA // BOUNTIES";
    private final JavaPlugin plugin;
    private final File file;
    private final Map<UUID, Bounty> bounties = new LinkedHashMap<>();
    private final Map<UUID, PendingClaim> pendingClaims = new HashMap<>();
    private final Map<UUID, Combat> combat = new HashMap<>();
    private final Map<UUID, Long> recentCrystalUse = new HashMap<>();
    private final Map<String, TimedOwner> explosiveBlocks = new HashMap<>();
    private final List<RecentExplosion> recentExplosions = new ArrayList<>();
    private final Map<UUID, UUID> crystalOwners = new HashMap<>();
    private final NamespacedKey guiAction;
    private Object economy;
    private YamlConfiguration store;
    private final Set<UUID> paying = ConcurrentHashMap.newKeySet();
    private final Set<UUID> handledDeaths = ConcurrentHashMap.newKeySet();
    private final Set<UUID> combatLogouts = ConcurrentHashMap.newKeySet();
    private final Map<UUID, TimedOwner> lastAttackers = new HashMap<>();

    BountyCombatManager(JavaPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "bounties.yml");
        this.guiAction = new NamespacedKey(plugin, "bounty-action");
    }

    void register() {
        load();
        connectEconomy();
        Bukkit.getPluginManager().registerEvents(this, plugin);
        if (plugin.getCommand("bounty") != null) {
            plugin.getCommand("bounty").setExecutor((sender, command, label, args) -> {
                if (!(sender instanceof Player p)) { sender.sendMessage("Use this command in-game."); return true; }
                if (!p.hasPermission("kelvora.bounty")) { fail(p, "You do not have permission."); return true; }
                if (args.length == 0) { openMenu(p); return true; }
                switch (args[0].toLowerCase(Locale.ROOT)) {
                    case "set" -> {
                        if (args.length != 3) { fail(p, "Usage: /bounty set <player> <amount>"); return true; }
                        setBounty(p, args[1], args[2]);
                    }
                    case "check" -> {
                        if (args.length != 2) { fail(p, "Usage: /bounty check <player>"); return true; }
                        OfflinePlayer target = knownTarget(args[1]);
                        if (target == null) { fail(p, "That player is not known to this server."); return true; }
                        Bounty b = bounties.get(target.getUniqueId());
                        p.sendMessage(Component.text(target.getName() + "'s bounty: $" + money(b == null ? 0 : b.total), NamedTextColor.GREEN));
                    }
                    case "top", "list" -> showList(p, args[0].equalsIgnoreCase("top"));
                    case "recover" -> {
                        if (!p.hasPermission("kelvora.bounty.admin")) { fail(p, "You do not have permission."); return true; }
                        if (args.length != 3 || !(args[2].equalsIgnoreCase("paid") || args[2].equalsIgnoreCase("retry"))) { fail(p, "Usage: /bounty recover <player> <paid|retry>"); return true; }
                        recoverClaim(p, args[1], args[2].equalsIgnoreCase("paid"));
                    }
                    default -> fail(p, "Usage: /bounty [set <player> <amount>|check <player>|top|list]");
                }
                return true;
            });
            plugin.getCommand("bounty").setTabCompleter((sender, command, alias, args) -> {
                if (args.length == 1) return prefix(List.of("set", "check", "top", "list", "recover"), args[0]);
                if (args.length == 2 && (args[0].equalsIgnoreCase("set") || args[0].equalsIgnoreCase("check") || args[0].equalsIgnoreCase("recover"))) {
                    List<String> names = new ArrayList<>();
                    for (Player online : Bukkit.getOnlinePlayers()) names.add(online.getName());
                    return prefix(names, args[1]);
                }
                if (args.length == 3 && args[0].equalsIgnoreCase("recover")) return prefix(List.of("paid", "retry"), args[2]);
                return Collections.emptyList();
            });
        }
        Bukkit.getScheduler().runTaskTimer(plugin, this::tickCombat, 20L, 20L);
        plugin.getLogger().info("Bounty and combat-tag systems loaded.");
    }

    private void connectEconomy() {
        economy = null;
        if (!Bukkit.getPluginManager().isPluginEnabled("Vault")) return;
        try {
            Class<?> type = Class.forName("net.milkbowl.vault.economy.Economy",
                    true, Bukkit.getPluginManager().getPlugin("Vault").getClass().getClassLoader());
            RegisteredServiceProvider<?> registration = Bukkit.getServicesManager().getRegistration((Class) type);
            if (registration != null) economy = registration.getProvider();
        } catch (ReflectiveOperationException | LinkageError ex) {
            plugin.getLogger().warning("Bounty system could not load the Vault Money API.");
        }
    }

    private double balance(OfflinePlayer p) {
        Object v = invoke("getBalance", new Class<?>[]{OfflinePlayer.class}, p);
        return v instanceof Number n ? n.doubleValue() : Double.NaN;
    }
    private boolean withdraw(OfflinePlayer p, double amount) {
        Object r = invoke("withdrawPlayer", new Class<?>[]{OfflinePlayer.class, double.class}, p, amount);
        return transactionSuccess(r);
    }
    private boolean deposit(OfflinePlayer p, double amount) {
        Object r = invoke("depositPlayer", new Class<?>[]{OfflinePlayer.class, double.class}, p, amount);
        return transactionSuccess(r);
    }
    private Object invoke(String method, Class<?>[] types, Object... args) {
        if (economy == null) connectEconomy();
        if (economy == null) return null;
        try { return economy.getClass().getMethod(method, types).invoke(economy, args); }
        catch (ReflectiveOperationException | LinkageError ex) {
            plugin.getLogger().warning("Vault Money operation failed: " + method);
            return null;
        }
    }
    private boolean transactionSuccess(Object response) {
        if (response == null) return false;
        try { return Boolean.TRUE.equals(response.getClass().getMethod("transactionSuccess").invoke(response)); }
        catch (ReflectiveOperationException ex) { return Boolean.TRUE.equals(response); }
    }

    private void setBounty(Player issuer, String targetName, String amountText) {
        OfflinePlayer target = knownTarget(targetName);
        if (target == null || target.getUniqueId() == null) { fail(issuer, "Target must be online or have joined before."); return; }
        if (target.getUniqueId().equals(issuer.getUniqueId())) { fail(issuer, "You cannot place a bounty on yourself."); return; }
        if (pendingClaims.containsKey(target.getUniqueId())) { fail(issuer, "This bounty is awaiting transaction recovery."); return; }
        double amount;
        try { amount = Double.parseDouble(amountText); } catch (NumberFormatException ex) { fail(issuer, "Enter a valid positive amount."); return; }
        if (!Double.isFinite(amount) || amount <= 0 || amount > 1.0E12) { fail(issuer, "Enter a valid positive amount."); return; }
        amount = Math.round(amount * 100.0) / 100.0;
        if (amount <= 0) { fail(issuer, "Enter a valid positive amount."); return; }
        double funds = balance(issuer);
        if (!Double.isFinite(funds)) { fail(issuer, "Money economy is not connected."); return; }
        if (funds < amount) { fail(issuer, "Insufficient Money."); return; }
        if (!withdraw(issuer, amount)) { fail(issuer, "Money payment failed; no bounty was added."); return; }
        UUID id = target.getUniqueId();
        Bounty bounty = bounties.computeIfAbsent(id, k -> new Bounty(target.getName() == null ? targetName : target.getName()));
        bounty.name = target.getName() == null ? targetName : target.getName();
        bounty.total = round(bounty.total + amount);
        bounty.contributors.add(issuer.getName() + ":$" + money(amount));
        if (!save()) {
            bounty.total = round(bounty.total - amount);
            if (!bounty.contributors.isEmpty()) bounty.contributors.remove(bounty.contributors.size() - 1);
            if (bounty.total <= 0) bounties.remove(id);
            deposit(issuer, amount);
            fail(issuer, "Could not save bounty; your payment was refunded if Money accepted it.");
            return;
        }
        issuer.sendMessage(Component.text("Bounty added: $" + money(amount) + " on " + bounty.name + ". Total: $" + money(bounty.total), NamedTextColor.GREEN));
        sound(issuer, Sound.ENTITY_EXPERIENCE_ORB_PICKUP);
        Player online = Bukkit.getPlayer(id);
        if (online != null) online.sendMessage(Component.text("Your bounty is now $" + money(bounty.total) + ".", NamedTextColor.RED));
    }

    private OfflinePlayer knownTarget(String name) {
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) return online;
        return Bukkit.getOfflinePlayerIfCached(name);
    }

    private void showList(Player p, boolean top) {
        List<Map.Entry<UUID, Bounty>> entries = new ArrayList<>(bounties.entrySet());
        entries.removeIf(e -> pendingClaims.containsKey(e.getKey()));
        entries.sort((a,b) -> Double.compare(b.getValue().total, a.getValue().total));
        if (entries.isEmpty()) { p.sendMessage(Component.text("No active bounties.", NamedTextColor.GRAY)); return; }
        p.sendMessage(Component.text(top ? "Top Bounties" : "Active Bounties", NamedTextColor.GREEN));
        int count = 0;
        for (Map.Entry<UUID, Bounty> entry : entries) {
            if (count++ == 10) break;
            p.sendMessage(Component.text((count) + ". " + entry.getValue().name + " — $" + money(entry.getValue().total), NamedTextColor.WHITE));
        }
    }

    private void openMenu(Player p) {
        Inventory inv = Bukkit.createInventory(null, 54, Component.text(BOUNTY_TITLE, NamedTextColor.DARK_GREEN));
        ItemStack frame = item(Material.GREEN_STAINED_GLASS_PANE, " ", List.of());
        for (int i = 0; i < 54; i++) inv.setItem(i, frame);
        List<Map.Entry<UUID, Bounty>> entries = new ArrayList<>(bounties.entrySet());
        entries.removeIf(e -> pendingClaims.containsKey(e.getKey()));
        entries.sort((a,b) -> Double.compare(b.getValue().total, a.getValue().total));
        for (int i = 0; i < Math.min(45, entries.size()); i++) {
            Map.Entry<UUID, Bounty> e = entries.get(i);
            ItemStack head = item(Material.PLAYER_HEAD, "§a" + e.getValue().name,
                    List.of("§7Active bounty: §f$" + money(e.getValue().total), "§7Recent contributors:"));
            ItemMeta meta = head.getItemMeta();
            List<String> lore = new ArrayList<>(meta.getLore() == null ? List.of() : meta.getLore());
            for (String contributor : e.getValue().contributors.subList(Math.max(0, e.getValue().contributors.size()-3), e.getValue().contributors.size()))
                lore.add("§8" + contributor);
            meta.setLore(lore); meta.getPersistentDataContainer().set(guiAction, PersistentDataType.STRING, "check:" + e.getValue().name);
            head.setItemMeta(meta); inv.setItem(i, head);
        }
        inv.setItem(49, item(Material.EMERALD, "§aBounty Menu", List.of("§7Set a bounty: §f/bounty set <player> <amount>", "§7Top rewards: §f/bounty top")));
        inv.setItem(53, item(Material.BARRIER, "§cClose", List.of()));
        p.openInventory(inv);
    }

    private ItemStack item(Material material, String name, List<String> lore) {
        ItemStack stack = new ItemStack(material);
        ItemMeta meta = stack.getItemMeta(); meta.setDisplayName(name); meta.setLore(lore);
        stack.setItemMeta(meta); return stack;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onGuiClick(InventoryClickEvent e) {
        if (!strip(e.getView().getTitle()).equals(BOUNTY_TITLE)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p) || e.getClickedInventory() != e.getView().getTopInventory()) return;
        ItemStack clicked = e.getCurrentItem();
        if (clicked == null || !clicked.hasItemMeta()) return;
        String action = clicked.getItemMeta().getPersistentDataContainer().get(guiAction, PersistentDataType.STRING);
        if (clicked.getType() == Material.BARRIER) { p.closeInventory(); return; }
        sound(p, Sound.UI_BUTTON_CLICK);
        if (action != null && action.startsWith("check:")) {
            OfflinePlayer target = knownTarget(action.substring(6));
            Bounty b = target == null ? null : bounties.get(target.getUniqueId());
            p.sendMessage(Component.text(target == null || b == null ? "No active bounty." : target.getName() + "'s bounty: $" + money(b.total), NamedTextColor.GREEN));
        }
    }
    @EventHandler public void onGuiDrag(InventoryDragEvent e) {
        if (strip(e.getView().getTitle()).equals(BOUNTY_TITLE)) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player victim) || event.getFinalDamage() <= 0) return;
        Player attacker = attacker(event.getDamager());
        if (attacker == null && event.getDamager() instanceof EnderCrystal crystal) attacker = crystalOwners.get(crystal.getUniqueId()) == null ? null : Bukkit.getPlayer(crystalOwners.get(crystal.getUniqueId()));
        if (attacker == null || attacker.equals(victim) || !attacker.isOnline() || !attacker.getWorld().equals(victim.getWorld())) return;
        tag(attacker, victim);
    }

    private Player attacker(Entity damager) {
        if (damager instanceof Player p) return p;
        if (damager instanceof Projectile projectile && projectile.getShooter() instanceof Player p) return p;
        if (damager instanceof TNTPrimed tnt && tnt.getSource() instanceof Player p) return p;
        return null;
    }

    private void tag(Player attacker, Player victim) {
        long now = System.currentTimeMillis();
        boolean freshA = !active(attacker.getUniqueId(), now), freshV = !active(victim.getUniqueId(), now);
        combat.put(attacker.getUniqueId(), new Combat(now + COMBAT_MS, victim.getUniqueId(), now));
        combat.put(victim.getUniqueId(), new Combat(now + COMBAT_MS, attacker.getUniqueId(), now));
        lastAttackers.put(victim.getUniqueId(), new TimedOwner(attacker.getUniqueId(), now + COMBAT_MS));
        if (freshA) { attacker.sendMessage(Component.text("Combat tagged for 20 seconds!", NamedTextColor.RED)); sound(attacker, Sound.UI_BUTTON_CLICK); }
        if (freshV) { victim.sendMessage(Component.text("Combat tagged for 20 seconds!", NamedTextColor.RED)); sound(victim, Sound.UI_BUTTON_CLICK); }
    }

    private boolean active(UUID id, long now) { Combat c = combat.get(id); return c != null && c.until > now; }

    private void tickCombat() {
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<UUID, Combat>> it = combat.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Combat> entry = it.next();
            UUID id = entry.getKey(); Combat c = entry.getValue();
            Player p = Bukkit.getPlayer(id);
            if (c.until <= now || p == null || !p.isOnline() || p.isDead()) {
                if (p != null && p.isOnline() && c.until <= now && !p.isDead()) {
                    p.sendMessage(Component.text("You are no longer in combat.", NamedTextColor.GREEN));
                    sound(p, Sound.BLOCK_NOTE_BLOCK_PLING);
                }
                it.remove(); continue;
            }
            long seconds = Math.max(1, (c.until - now + 999) / 1000);
            if (seconds == 10 || seconds == 5 || seconds == 3 || seconds == 2 || seconds == 1) {
                p.sendMessage(Component.text("Combat ends in " + seconds + "s.", NamedTextColor.RED));
                sound(p, Sound.BLOCK_NOTE_BLOCK_HAT);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent e) {
        Player p = e.getPlayer();
        if (!active(p.getUniqueId(), System.currentTimeMillis())) return;
        String message = e.getMessage();
        if (message == null || !message.startsWith("/")) return;
        String label = message.substring(1).trim().split("\\s+", 2)[0].toLowerCase(Locale.ROOT);
        int colon = label.lastIndexOf(':'); if (colon >= 0) label = label.substring(colon + 1);
        if (Set.of("ah", "auctionhouse", "auctions", "sell", "worth", "itemworth").contains(label)) {
            e.setCancelled(true);
            fail(p, "You cannot use /" + label + " during combat.");
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent e) {
        if (e.getItem() != null && e.getItem().getType() == Material.END_CRYSTAL) recentCrystalUse.put(e.getPlayer().getUniqueId(), System.currentTimeMillis());
        Block b = e.getClickedBlock();
        if (b != null && (b.getType() == Material.RESPAWN_ANCHOR || b.getType() == Material.TNT)) {
            explosiveBlocks.put(key(b.getLocation()), new TimedOwner(e.getPlayer().getUniqueId(), System.currentTimeMillis() + INDIRECT_MS));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent e) {
        if (e.getBlockPlaced().getType() == Material.TNT || e.getBlockPlaced().getType() == Material.RESPAWN_ANCHOR) {
            explosiveBlocks.put(key(e.getBlockPlaced().getLocation()), new TimedOwner(e.getPlayer().getUniqueId(), System.currentTimeMillis() + INDIRECT_MS));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSpawn(EntitySpawnEvent e) {
        if (!(e.getEntity() instanceof EnderCrystal crystal)) return;
        long now = System.currentTimeMillis();
        UUID owner = recentCrystalUse.entrySet().stream().filter(x -> now - x.getValue() <= 3000)
                .max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(null);
        if (owner != null) crystalOwners.put(crystal.getUniqueId(), owner);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockExplosion(BlockExplodeEvent e) {
        TimedOwner owner = explosiveBlocks.remove(key(e.getBlock().getLocation()));
        if (owner == null || owner.expires < System.currentTimeMillis()) return;
        recentExplosions.add(new RecentExplosion(e.getBlock().getLocation().add(.5, .5, .5), owner.owner,
                System.currentTimeMillis() + 1500L));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onExplode(EntityExplodeEvent e) {
        Player attacker = attacker(e.getEntity());
        if (attacker == null && e.getEntity() instanceof EnderCrystal crystal) {
            UUID owner = crystalOwners.remove(crystal.getUniqueId());
            if (owner != null) attacker = Bukkit.getPlayer(owner);
        }
        if (attacker != null) recentExplosions.add(new RecentExplosion(e.getLocation(), attacker.getUniqueId(),
                System.currentTimeMillis() + 1500L));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onExplosionDamage(EntityDamageEvent e) {
        if (!(e.getEntity() instanceof Player victim) || e instanceof EntityDamageByEntityEvent
                || e.getCause() != EntityDamageEvent.DamageCause.BLOCK_EXPLOSION
                || e.getFinalDamage() <= 0) return;
        long now = System.currentTimeMillis();
        recentExplosions.removeIf(x -> x.expires < now);
        RecentExplosion source = recentExplosions.stream()
                .filter(x -> x.location.getWorld().equals(victim.getWorld()))
                .filter(x -> x.location.distanceSquared(victim.getLocation()) <= 64)
                .min(Comparator.comparingDouble(x -> x.location.distanceSquared(victim.getLocation()))).orElse(null);
        if (source == null) return;
        Player attacker = Bukkit.getPlayer(source.owner);
        if (attacker != null && !attacker.equals(victim)) tag(attacker, victim);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent e) {
        Player victim = e.getEntity();
        UUID victimId = victim.getUniqueId();
        Combat tag = combat.get(victimId);
        combat.remove(victimId);
        if (tag != null) {
            Combat other = combat.get(tag.opponent);
            if (other != null && other.opponent.equals(victimId)) combat.remove(tag.opponent);
        }
        if (!handledDeaths.add(victimId)) return;
        if (combatLogouts.remove(victimId)) return;
        Player killer = victim.getKiller();
        if (killer == null) {
            TimedOwner last = lastAttackers.get(victimId);
            if (last != null && last.expires > System.currentTimeMillis()) killer = Bukkit.getPlayer(last.owner);
        }
        lastAttackers.remove(victimId);
        if (killer == null || killer.equals(victim) || !killer.isOnline()) return;
        Bounty bounty = bounties.get(victimId);
        if (bounty == null || bounty.total <= 0 || pendingClaims.containsKey(victimId) || !paying.add(victimId)) return;
        double reward = bounty.total;
        PendingClaim pending = new PendingClaim(killer.getUniqueId(), killer.getName(), victim.getName(), reward);
        pendingClaims.put(victimId, pending);
        if (!save()) {
            pendingClaims.remove(victimId);
            paying.remove(victimId);
            plugin.getLogger().severe("Could not persist pending bounty payout for " + victim.getName() + "; bounty remains active.");
            return;
        }
        if (!deposit(killer, reward)) {
            pendingClaims.remove(victimId);
            if (!save()) {
                pendingClaims.put(victimId, pending);
                plugin.getLogger().severe("Bounty payout failed and transaction state could not be cleared; target remains locked for recovery.");
            }
            paying.remove(victimId);
            plugin.getLogger().severe("Bounty payout failed; bounty remains active for " + victim.getName());
            return;
        }
        // Keep the pending claim durable until the completed state is safely written.
        bounties.remove(victimId);
        pendingClaims.remove(victimId);
        if (!save()) {
            bounties.put(victimId, bounty);
            pendingClaims.put(victimId, pending);
            plugin.getLogger().severe("Bounty was paid but final persistence failed. Target is locked pending /bounty recover " + victim.getName() + " paid|retry. Do not retry automatically.");
            paying.remove(victimId);
            return;
        }
        killer.sendMessage(Component.text("Bounty claimed: +$" + money(reward) + " for eliminating " + victim.getName() + ".", NamedTextColor.GREEN));
        victim.sendMessage(Component.text("Your $" + money(reward) + " bounty was claimed by " + killer.getName() + ".", NamedTextColor.RED));
        for (Player online : Bukkit.getOnlinePlayers()) if (!online.equals(killer) && !online.equals(victim))
            online.sendMessage(Component.text(killer.getName() + " claimed $" + money(reward) + " for eliminating " + victim.getName() + ".", NamedTextColor.GREEN));
        killer.playSound(killer.getLocation(), Sound.UI_TOAST_IN, 0.3f, 1.1f);
        paying.remove(victimId);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        Combat c = combat.remove(id);
        if (c != null && c.until > System.currentTimeMillis()) {
            Player attacker = Bukkit.getPlayer(c.opponent);
            combatLogouts.add(id);
            e.getPlayer().setHealth(0.0);
            if (attacker != null && attacker.isOnline()) {
                attacker.sendMessage(Component.text(e.getPlayer().getName() + " combat-logged.", NamedTextColor.RED));
                // A logout itself is not a bounty kill. No reward is issued here.
            }
        }
        combat.remove(id);
        lastAttackers.remove(id);
        recentCrystalUse.remove(id);
        Bukkit.getScheduler().runTaskLater(plugin, () -> handledDeaths.remove(id), 2L);
    }

    @EventHandler public void onJoin(PlayerJoinEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        combat.remove(id);
        lastAttackers.remove(id);
        combatLogouts.remove(id);
        handledDeaths.remove(id);
    }

    private void load() {
        if (!file.exists()) {
            store = new YamlConfiguration();
            save();
            return;
        }
        store = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection claims = store.getConfigurationSection("pending-claims");
        if (claims != null) for (String raw : claims.getKeys(false)) {
            try {
                UUID target = UUID.fromString(raw);
                ConfigurationSection pc = claims.getConfigurationSection(raw);
                if (pc != null) pendingClaims.put(target, new PendingClaim(UUID.fromString(pc.getString("killer-uuid")), pc.getString("killer-name", "Unknown"), pc.getString("victim-name", "Unknown"), pc.getDouble("reward", 0)));
            } catch (IllegalArgumentException ignored) { }
        }
        ConfigurationSection section = store.getConfigurationSection("bounties");
        if (section == null) return;
        for (String raw : section.getKeys(false)) {
            try {
                UUID id = UUID.fromString(raw);
                ConfigurationSection b = section.getConfigurationSection(raw);
                if (b == null) continue;
                Bounty bounty = new Bounty(b.getString("name", "Unknown"));
                bounty.total = Math.max(0, b.getDouble("total", 0));
                bounty.contributors.addAll(b.getStringList("contributors"));
                if (bounty.total > 0) bounties.put(id, bounty);
            } catch (IllegalArgumentException ignored) { }
        }
    }

    private boolean save() {
        try {
            YamlConfiguration next = new YamlConfiguration();
            for (Map.Entry<UUID, Bounty> entry : bounties.entrySet()) {
                String path = "bounties." + entry.getKey();
                next.set(path + ".name", entry.getValue().name);
                next.set(path + ".total", entry.getValue().total);
                next.set(path + ".contributors", entry.getValue().contributors);
            }
            for (Map.Entry<UUID, PendingClaim> entry : pendingClaims.entrySet()) {
                String path = "pending-claims." + entry.getKey();
                next.set(path + ".killer-uuid", entry.getValue().killer.toString());
                next.set(path + ".killer-name", entry.getValue().killerName);
                next.set(path + ".victim-name", entry.getValue().victimName);
                next.set(path + ".reward", entry.getValue().reward);
            }
            next.save(file);
            store = next;
            return true;
        } catch (Exception ex) {
            plugin.getLogger().severe("Could not persist bounties: " + ex.getMessage());
            return false;
        }
    }

    private void recoverClaim(Player admin, String targetName, boolean alreadyPaid) {
        OfflinePlayer target = knownTarget(targetName);
        if (target == null) { fail(admin, "That player is not known to this server."); return; }
        UUID id = target.getUniqueId();
        PendingClaim pending = pendingClaims.get(id);
        if (pending == null) { fail(admin, "No pending bounty transaction for that player."); return; }
        Bounty existing = bounties.get(id);
        pendingClaims.remove(id);
        if (alreadyPaid) bounties.remove(id);
        if (!save()) {
            pendingClaims.put(id, pending);
            if (alreadyPaid && existing != null) bounties.put(id, existing);
            fail(admin, "Could not save recovery; no state change was committed."); return;
        }
        admin.sendMessage(Component.text(alreadyPaid ? "Marked pending payout as paid; bounty cleared." : "Pending payout released; bounty remains active. Verify Money before choosing retry.", NamedTextColor.GREEN));
    }

    private void fail(Player p, String text) {
        p.sendMessage(Component.text(text, NamedTextColor.RED)); sound(p, Sound.BLOCK_NOTE_BLOCK_BASS);
    }
    private void sound(Player p, Sound sound) { p.playSound(p.getLocation(), sound, 0.18f, 1.1f); }
    private static String money(double n) { return String.format(Locale.US, "%,.2f", n).replaceAll("\\.00$", ""); }
    private static double round(double n) { return Math.round(n * 100.0) / 100.0; }
    private static String strip(String s) { return org.bukkit.ChatColor.stripColor(s == null ? "" : s); }
    private static List<String> prefix(List<String> choices, String input) {
        List<String> out = new ArrayList<>(); for (String s : choices) if (s.regionMatches(true, 0, input, 0, input.length())) out.add(s); return out;
    }
    private static String key(Location l) { return l.getWorld().getName() + ":" + l.getBlockX() + ":" + l.getBlockY() + ":" + l.getBlockZ(); }

    private static final class PendingClaim {
        final UUID killer; final String killerName; final String victimName; final double reward;
        PendingClaim(UUID killer, String killerName, String victimName, double reward) { this.killer = killer; this.killerName = killerName; this.victimName = victimName; this.reward = reward; }
    }
    private static final class Bounty {
        String name; double total; final List<String> contributors = new ArrayList<>();
        Bounty(String name) { this.name = name; }
    }
    private static final class Combat {
        final long until; final UUID opponent; final long lastHit;
        Combat(long until, UUID opponent, long lastHit) { this.until = until; this.opponent = opponent; this.lastHit = lastHit; }
    }
    private static final class RecentExplosion {
        final Location location; final UUID owner; final long expires;
        RecentExplosion(Location location, UUID owner, long expires) { this.location = location.clone(); this.owner = owner; this.expires = expires; }
    }
    private static final class TimedOwner {
        final UUID owner; final long expires;
        TimedOwner(UUID owner, long expires) { this.owner = owner; this.expires = expires; }
    }
}
