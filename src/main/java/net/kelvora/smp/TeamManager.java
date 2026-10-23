package net.kelvora.smp;

import java.io.File;
import java.io.IOException;
import java.util.*;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

/** Persistent cooperative survival teams, independent of LuckPerms progression/staff ranks. */
final class TeamManager implements Listener, CommandExecutor, TabCompleter {
    private static final String TITLE = "KELVORA | TEAM";
    private final JavaPlugin plugin;
    private final NamespacedKey actionKey;
    private final File file;
    private YamlConfiguration data;
    private final Map<String, Team> teams = new LinkedHashMap<>();
    private final Map<UUID, Map<String, Long>> invitations = new HashMap<>();

    TeamManager(JavaPlugin plugin) {
        this.plugin = plugin;
        this.actionKey = new NamespacedKey(plugin, "team-action");
        this.file = new File(plugin.getDataFolder(), "teams.yml");
    }

    void register() {
        data = YamlConfiguration.loadConfiguration(file);
        load();
        Bukkit.getPluginManager().registerEvents(this, plugin);
        if (plugin.getCommand("team") != null) {
            plugin.getCommand("team").setExecutor(this);
            plugin.getCommand("team").setTabCompleter(this);
        }
    }

    String teamName(Player player) {
        Team team = teamOf(player.getUniqueId());
        return team == null ? "No Team" : trim(team.name, 12);
    }

    private Team teamOf(UUID uuid) {
        for (Team team : teams.values()) if (team.members.contains(uuid)) return team;
        return null;
    }

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            if (args.length == 2 && args[0].equalsIgnoreCase("admin") && args[1].equalsIgnoreCase("list")) {
                sender.sendMessage("Teams: " + String.join(", ", teams.values().stream().map(t -> t.name).toList()));
                return true;
            }
            sender.sendMessage("Team management commands must be used in-game.");
            return true;
        }
        if (args.length == 0) { openGui(player); return true; }
        String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "create" -> {
                if (args.length != 2) { usage(player, "/team create <name>"); return true; }
                create(player, args[1]);
            }
            case "invite" -> {
                if (args.length != 2) { usage(player, "/team invite <player>"); return true; }
                invite(player, args[1]);
            }
            case "join" -> {
                if (args.length != 2) { usage(player, "/team join <name>"); return true; }
                join(player, args[1]);
            }
            case "leave" -> leave(player);
            case "kick" -> {
                if (args.length != 2) { usage(player, "/team kick <player>"); return true; }
                kick(player, args[1]);
            }
            case "promote" -> {
                if (args.length != 2) { usage(player, "/team promote <player>"); return true; }
                promote(player, args[1]);
            }
            case "info" -> {
                if (args.length > 2) { usage(player, "/team info [name]"); return true; }
                showInfo(player, args.length == 2 ? args[1] : null);
            }
            case "list" -> list(player);
            case "disband" -> {
                if (args.length != 1) { usage(player, "/team disband"); return true; }
                disband(player);
            }
            case "admin" -> {
                if (!isAdmin(player)) { deny(player); return true; }
                if (args.length == 3 && args[1].equalsIgnoreCase("disband")) {
                    Team target = find(args[2]);
                    if (target == null) { player.sendMessage(red("That team does not exist.")); return true; }
                    disbandTeam(target, "Team " + target.name + " was disbanded by staff.");
                    player.sendMessage(green("Team " + target.name + " disbanded."));
                } else usage(player, "/team admin disband <name>");
            }
            default -> { openGui(player); }
        }
        return true;
    }

    private void create(Player player, String rawName) {
        if (teamOf(player.getUniqueId()) != null) { player.sendMessage(red("Leave your current team before creating another.")); return; }
        if (rawName.length() < 3 || rawName.length() > 16 || !rawName.matches("[A-Za-z0-9_]+")) {
            player.sendMessage(red("Team names must be 3–16 characters: letters, numbers, and underscores only.")); return;
        }
        String key = key(rawName);
        if (teams.containsKey(key)) { player.sendMessage(red("A team with that name already exists.")); return; }
        Team team = new Team(rawName);
        team.leader = player.getUniqueId();
        team.members.add(player.getUniqueId());
        teams.put(key, team);
        if (!save()) { teams.remove(key); player.sendMessage(red("Team could not be saved. Please try again.")); return; }
        announce(team, player.getName() + " created the team.");
        player.sendMessage(green("Created team " + team.name + "."));
    }

    private void invite(Player leader, String targetName) {
        Team team = teamOf(leader.getUniqueId());
        if (team == null) { leader.sendMessage(red("You are not in a team.")); return; }
        if (!team.leader.equals(leader.getUniqueId())) { leader.sendMessage(red("Only the team leader can invite players.")); return; }
        Player target = Bukkit.getPlayerExact(targetName);
        if (target == null) { leader.sendMessage(red("That player must be online to receive an invitation.")); return; }
        if (target.getUniqueId().equals(leader.getUniqueId())) { leader.sendMessage(red("You cannot invite yourself.")); return; }
        if (team.members.contains(target.getUniqueId())) { leader.sendMessage(yellow("That player is already in your team.")); return; }
        if (teamOf(target.getUniqueId()) != null) { leader.sendMessage(red("That player is already in another team.")); return; }
        if (team.members.size() >= maxSize()) { leader.sendMessage(red("Your team is full (" + maxSize() + " members).")); return; }
        if (isInvited(target.getUniqueId(), key(team.name))) { leader.sendMessage(yellow("That player already has a pending invitation.")); return; }
        long expires = System.currentTimeMillis() + inviteDuration() * 1000L;
        invitations.computeIfAbsent(target.getUniqueId(), ignored -> new HashMap<>()).put(key(team.name), expires);
        if (!save()) {
            Map<String, Long> pending = invitations.get(target.getUniqueId());
            if (pending != null) pending.remove(key(team.name));
            leader.sendMessage(red("Invitation could not be saved."));
            return;
        }
        leader.sendMessage(green("Invited " + target.getName() + " to " + team.name + "."));
        target.sendMessage(Component.text(leader.getName() + " invited you to team " + team.name + ". Use /team join " + team.name + " or open /team.", NamedTextColor.GREEN));
    }

    private void join(Player player, String rawTeam) {
        if (teamOf(player.getUniqueId()) != null) { player.sendMessage(red("You can only belong to one team. Leave your current team first.")); return; }
        Team team = find(rawTeam);
        if (team == null) { player.sendMessage(red("That team does not exist.")); return; }
        if (!isInvited(player.getUniqueId(), key(team.name))) { player.sendMessage(red("You need a valid invitation to join " + team.name + ".")); return; }
        if (team.members.size() >= maxSize()) { player.sendMessage(red("That team is full.")); removeInvite(player.getUniqueId(), key(team.name)); save(); return; }
        team.members.add(player.getUniqueId());
        removeInvite(player.getUniqueId(), key(team.name));
        if (!save()) {
            team.members.remove(player.getUniqueId());
            invitations.computeIfAbsent(player.getUniqueId(), ignored -> new HashMap<>()).put(key(team.name), System.currentTimeMillis() + inviteDuration() * 1000L);
            player.sendMessage(red("Joining failed because team data could not be saved."));
            return;
        }
        announce(team, player.getName() + " joined the team.");
        player.sendMessage(green("You joined " + team.name + "."));
    }

    private void leave(Player player) {
        Team team = teamOf(player.getUniqueId());
        if (team == null) { player.sendMessage(red("You are not in a team.")); return; }
        if (team.leader.equals(player.getUniqueId()) && team.members.size() > 1) {
            player.sendMessage(red("Transfer leadership with /team promote <player> before leaving."));
            return;
        }
        if (team.members.size() == 1) {
            disbandTeam(team, "Team " + team.name + " was closed because its last member left.");
            player.sendMessage(yellow("You left and disbanded " + team.name + "."));
            return;
        }
        team.members.remove(player.getUniqueId());
        if (!save()) { team.members.add(player.getUniqueId()); player.sendMessage(red("Could not save your team change.")); return; }
        announce(team, player.getName() + " left the team.");
        player.sendMessage(yellow("You left " + team.name + "."));
    }

    private void kick(Player leader, String targetName) {
        Team team = teamOf(leader.getUniqueId());
        if (team == null) { leader.sendMessage(red("You are not in a team.")); return; }
        if (!team.leader.equals(leader.getUniqueId()) && !isAdmin(leader)) { deny(leader); return; }
        Player target = Bukkit.getPlayerExact(targetName);
        UUID targetId = target == null ? findMember(team, targetName) : target.getUniqueId();
        if (targetId == null || !team.members.contains(targetId)) { leader.sendMessage(red("That player is not in your team.")); return; }
        if (targetId.equals(team.leader)) { leader.sendMessage(red("Transfer leadership before removing the leader.")); return; }
        team.members.remove(targetId);
        removeInvite(targetId, key(team.name));
        if (!save()) { team.members.add(targetId); leader.sendMessage(red("Could not save the team change.")); return; }
        String name = target != null ? target.getName() : Optional.ofNullable(Bukkit.getOfflinePlayer(targetId).getName()).orElse("Player");
        announce(team, name + " was removed from the team.");
        if (target != null) target.sendMessage(yellow("You were removed from team " + team.name + "."));
    }

    private void promote(Player leader, String targetName) {
        Team team = teamOf(leader.getUniqueId());
        if (team == null) { leader.sendMessage(red("You are not in a team.")); return; }
        if (!team.leader.equals(leader.getUniqueId())) { leader.sendMessage(red("Only the team leader can transfer leadership.")); return; }
        UUID target = findMember(team, targetName);
        if (target == null || target.equals(leader.getUniqueId())) { leader.sendMessage(red("Choose another member of your team.")); return; }
        UUID oldLeader = team.leader;
        team.leader = target;
        if (!save()) { team.leader = oldLeader; leader.sendMessage(red("Could not save the leadership transfer.")); return; }
        announce(team, Bukkit.getOfflinePlayer(target).getName() + " is now team leader.");
    }

    private void disband(Player player) {
        Team team = teamOf(player.getUniqueId());
        if (team == null) { player.sendMessage(red("You are not in a team.")); return; }
        if (!team.leader.equals(player.getUniqueId())) { player.sendMessage(red("Only the team leader can disband the team.")); return; }
        disbandTeam(team, "Team " + team.name + " was disbanded.");
        player.sendMessage(yellow("You disbanded your team."));
    }

    private void disbandTeam(Team team, String message) {
        teams.remove(key(team.name));
        for (Map<String, Long> pending : invitations.values()) pending.remove(key(team.name));
        invitations.values().removeIf(Map::isEmpty);
        save();
        for (UUID member : team.members) {
            Player online = Bukkit.getPlayer(member);
            if (online != null) online.sendMessage(yellow(message));
        }
    }

    private void showInfo(Player player, String requested) {
        Team team = requested == null ? teamOf(player.getUniqueId()) : find(requested);
        if (team == null) { player.sendMessage(yellow(requested == null ? "You are not in a team." : "That team does not exist.")); return; }
        player.sendMessage(Component.text("Team " + team.name, NamedTextColor.GREEN));
        player.sendMessage(Component.text("Leader: " + Optional.ofNullable(Bukkit.getOfflinePlayer(team.leader).getName()).orElse("Unknown"), NamedTextColor.GOLD));
        player.sendMessage(Component.text("Members (" + team.members.size() + "/" + maxSize() + "): " +
            String.join(", ", team.members.stream().map(id -> Optional.ofNullable(Bukkit.getOfflinePlayer(id).getName()).orElse("Unknown")).toList()), NamedTextColor.WHITE));
    }

    private void list(Player player) {
        if (teams.isEmpty()) { player.sendMessage(yellow("There are no teams yet.")); return; }
        player.sendMessage(Component.text("Teams (" + teams.size() + "):", NamedTextColor.GREEN));
        for (Team team : teams.values()) player.sendMessage(Component.text("• " + team.name + " — " + team.members.size() + "/" + maxSize() + " members", NamedTextColor.AQUA));
    }

    private void openGui(Player player) {
        Inventory inv = Bukkit.createInventory(null, 54, Component.text(TITLE, NamedTextColor.DARK_GREEN));
        fill(inv);
        Team own = teamOf(player.getUniqueId());
        add(inv, 4, own == null ? Material.EMERALD : Material.PLAYER_HEAD,
            own == null ? "&aCreate a Team" : "&aTeam: " + own.name,
            own == null ? List.of("&7Use /team create <name>") : List.of("&7Leader: " + Bukkit.getOfflinePlayer(own.leader).getName(), "&7Members: " + own.members.size() + "/" + maxSize(), "&7Use /team info for details"),
            own == null ? "create-help" : "info");
        int slot = 10;
        if (own != null) {
            for (UUID memberId : own.members) {
                if (slot >= 35) break;
                OfflinePlayer offline = Bukkit.getOfflinePlayer(memberId);
                ItemStack head = new ItemStack(Material.PLAYER_HEAD);
                SkullMeta meta = (SkullMeta) head.getItemMeta();
                meta.setOwningPlayer(offline);
                meta.displayName(Component.text(Optional.ofNullable(offline.getName()).orElse("Member") + (memberId.equals(own.leader) ? " (Leader)" : ""), memberId.equals(own.leader) ? NamedTextColor.GOLD : NamedTextColor.WHITE));
                meta.lore(List.of(Component.text("Team member", NamedTextColor.GRAY)));
                head.setItemMeta(meta);
                inv.setItem(slot++, head);
            }
            if (own.leader.equals(player.getUniqueId())) {
                for (Player online : Bukkit.getOnlinePlayers()) {
                    if (slot >= 35) break;
                    if (own.members.contains(online.getUniqueId()) || teamOf(online.getUniqueId()) != null) continue;
                    add(inv, slot++, Material.PLAYER_HEAD, "&bInvite " + online.getName(),
                        List.of("&7Click to invite this player"), "invite:" + online.getName());
                }
            }
        } else {
            int inviteSlot = 28;
            for (String teamKey : validInvitations(player.getUniqueId())) {
                Team invited = teams.get(teamKey);
                if (invited == null || inviteSlot > 34) continue;
                add(inv, inviteSlot++, Material.EMERALD, "&aJoin " + invited.name,
                    List.of("&7Invitation received", "&eClick to accept"), "join:" + invited.name);
            }
        }
        add(inv, 45, Material.PAPER, "&eTeam List", List.of("&7Use /team list"), "list");
        add(inv, 49, Material.BARRIER, "&cClose", List.of(), "close");
        if (own != null) add(inv, 53, Material.OAK_DOOR, "&cLeave Team", List.of("&7Use /team leave", "&7Leaders must transfer leadership first"), "leave-help");
        player.openInventory(inv);
    }

    private void fill(Inventory inv) {
        ItemStack pane = new ItemStack(Material.BLACK_STAINED_GLASS_PANE);
        ItemMeta meta = pane.getItemMeta();
        meta.displayName(Component.text(" "));
        pane.setItemMeta(meta);
        for (int i = 0; i < inv.getSize(); i++) inv.setItem(i, pane);
        for (int i = 10; i <= 16; i++) inv.setItem(i, null);
        for (int i = 19; i <= 25; i++) inv.setItem(i, null);
        for (int i = 28; i <= 34; i++) inv.setItem(i, null);
    }

    private void add(Inventory inv, int slot, Material material, String name, List<String> lore, String action) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.legacyAmpersand().deserialize(name));
        meta.lore(lore.stream().map(s -> net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.legacyAmpersand().deserialize(s)).toList());
        meta.getPersistentDataContainer().set(actionKey, PersistentDataType.STRING, action);
        item.setItemMeta(meta);
        inv.setItem(slot, item);
    }

    @EventHandler public void onInventoryClick(InventoryClickEvent event) {
        if (!TITLE.equals(ChatColor.stripColor(event.getView().getTitle()))) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player) || event.getClickedInventory() != event.getView().getTopInventory()) return;
        ItemStack item = event.getCurrentItem();
        if (item == null || !item.hasItemMeta()) return;
        String action = item.getItemMeta().getPersistentDataContainer().get(actionKey, PersistentDataType.STRING);
        if (action == null) return;
        if (action.equals("close")) { player.closeInventory(); return; }
        if (action.equals("create-help")) { player.closeInventory(); player.sendMessage(yellow("Create your team with /team create <name>.")); return; }
        if (action.equals("info")) { player.closeInventory(); showInfo(player, null); return; }
        if (action.equals("list")) { player.closeInventory(); list(player); return; }
        if (action.equals("leave-help")) { player.closeInventory(); player.sendMessage(yellow("Use /team leave. A leader must transfer leadership first.")); return; }
        if (action.startsWith("invite:")) { invite(player, action.substring(7)); openGui(player); return; }
        if (action.startsWith("join:")) { join(player, action.substring(5)); openGui(player); }
    }

    @EventHandler public void onInventoryDrag(InventoryDragEvent event) {
        if (TITLE.equals(ChatColor.stripColor(event.getView().getTitle()))) event.setCancelled(true);
    }

    @EventHandler public void onQuit(PlayerQuitEvent event) { /* Team membership is persistent; no online state to clean up. */ }

    @Override public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) return filter(List.of("create","invite","join","leave","kick","promote","info","list","disband","admin"), args[0]);
        if (args.length == 2 && args[0].equalsIgnoreCase("join")) return filter(teams.values().stream().map(t -> t.name).toList(), args[1]);
        if (args.length == 2 && Set.of("invite","kick","promote").contains(args[0].toLowerCase(Locale.ROOT))) return filter(Bukkit.getOnlinePlayers().stream().map(Player::getName).toList(), args[1]);
        if (args.length == 2 && args[0].equalsIgnoreCase("info")) return filter(teams.values().stream().map(t -> t.name).toList(), args[1]);
        return Collections.emptyList();
    }

    private List<String> filter(List<String> values, String prefix) {
        String lower = prefix.toLowerCase(Locale.ROOT);
        return values.stream().filter(s -> s.toLowerCase(Locale.ROOT).startsWith(lower)).toList();
    }

    private List<String> validInvitations(UUID uuid) {
        Map<String, Long> pending = invitations.get(uuid);
        if (pending == null) return List.of();
        long now = System.currentTimeMillis();
        pending.entrySet().removeIf(e -> e.getValue() <= now || !teams.containsKey(e.getKey()));
        return new ArrayList<>(pending.keySet());
    }

    private boolean isInvited(UUID uuid, String teamKey) {
        return validInvitations(uuid).contains(teamKey);
    }

    private void removeInvite(UUID uuid, String teamKey) {
        Map<String, Long> pending = invitations.get(uuid);
        if (pending == null) return;
        pending.remove(teamKey);
        if (pending.isEmpty()) invitations.remove(uuid);
    }

    private UUID findMember(Team team, String name) {
        for (UUID uuid : team.members) {
            String known = Bukkit.getOfflinePlayer(uuid).getName();
            if (known != null && known.equalsIgnoreCase(name)) return uuid;
        }
        return null;
    }

    private Team find(String name) { return teams.get(key(name)); }
    private String key(String name) { return name.toLowerCase(Locale.ROOT); }
    private int maxSize() { return Math.max(2, Math.min(32, plugin.getConfig().getInt("teams.max-size", 8))); }
    private long inviteDuration() { return Math.max(60L, plugin.getConfig().getLong("teams.invite-duration-seconds", 86400L)); }
    private boolean isAdmin(Player player) { return player.isOp() || player.hasPermission("kelvora.team.admin"); }
    private void deny(Player player) { player.sendMessage(red("You do not have permission to manage this team.")); }
    private void usage(Player player, String usage) { player.sendMessage(Component.text("Usage: " + usage, NamedTextColor.YELLOW)); }
    private Component red(String text) { return Component.text(text, NamedTextColor.RED); }
    private Component green(String text) { return Component.text(text, NamedTextColor.GREEN); }
    private Component yellow(String text) { return Component.text(text, NamedTextColor.YELLOW); }
    private void announce(Team team, String message) {
        for (UUID uuid : team.members) {
            Player member = Bukkit.getPlayer(uuid);
            if (member != null) member.sendMessage(Component.text("[Team " + team.name + "] " + message, NamedTextColor.AQUA));
        }
    }

    private void load() {
        teams.clear();
        invitations.clear();
        ConfigurationSection teamSection = data.getConfigurationSection("teams");
        if (teamSection != null) for (String teamKey : teamSection.getKeys(false)) {
            ConfigurationSection section = teamSection.getConfigurationSection(teamKey);
            if (section == null) continue;
            String name = section.getString("name", teamKey);
            Team team = new Team(name);
            try { team.leader = UUID.fromString(section.getString("leader", "")); } catch (IllegalArgumentException ex) { continue; }
            for (String member : section.getStringList("members")) {
                try { team.members.add(UUID.fromString(member)); } catch (IllegalArgumentException ignored) { }
            }
            if (!team.members.contains(team.leader)) team.members.add(0, team.leader);
            if (!team.members.isEmpty()) teams.put(key(name), team);
        }
        ConfigurationSection inviteSection = data.getConfigurationSection("invitations");
        if (inviteSection != null) for (String uuidText : inviteSection.getKeys(false)) {
            UUID uuid;
            try { uuid = UUID.fromString(uuidText); } catch (IllegalArgumentException ex) { continue; }
            ConfigurationSection entries = inviteSection.getConfigurationSection(uuidText);
            if (entries == null) continue;
            Map<String, Long> pending = new HashMap<>();
            for (String teamKey : entries.getKeys(false)) {
                long expires = entries.getLong(teamKey, 0);
                if (expires > System.currentTimeMillis() && teams.containsKey(teamKey)) pending.put(teamKey, expires);
            }
            if (!pending.isEmpty()) invitations.put(uuid, pending);
        }
        // Repair stale/malformed entries and persist the canonical state.
        save();
    }

    private boolean save() {
        data.set("teams", null);
        for (Map.Entry<String, Team> entry : teams.entrySet()) {
            Team team = entry.getValue();
            String path = "teams." + entry.getKey();
            data.set(path + ".name", team.name);
            data.set(path + ".leader", team.leader.toString());
            data.set(path + ".members", team.members.stream().map(UUID::toString).toList());
        }
        data.set("invitations", null);
        for (Map.Entry<UUID, Map<String, Long>> entry : invitations.entrySet()) {
            for (Map.Entry<String, Long> invite : entry.getValue().entrySet()) {
                if (invite.getValue() > System.currentTimeMillis() && teams.containsKey(invite.getKey())) {
                    data.set("invitations." + entry.getKey() + "." + invite.getKey(), invite.getValue());
                }
            }
        }
        try { data.save(file); return true; }
        catch (IOException ex) {
            plugin.getLogger().severe("Could not save team data: " + ex.getMessage());
            return false;
        }
    }

    private String trim(String name, int max) { return name.length() <= max ? name : name.substring(0, max); }

    private static final class Team {
        final String name;
        UUID leader;
        final List<UUID> members = new ArrayList<>();
        Team(String name) { this.name = name; }
    }
}
