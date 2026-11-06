package net.kelvora.smp;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;

/** PlaceholderAPI bridge for Boardy and other PAPI-compatible display plugins. */
final class KelvoraPlaceholderExpansion extends PlaceholderExpansion {
    private final KelvoraSMP plugin;
    KelvoraPlaceholderExpansion(KelvoraSMP plugin) { this.plugin = plugin; }
    @Override public String getIdentifier() { return "kelvora"; }
    @Override public String getAuthor() { return "Kelvora SMP"; }
    @Override public String getVersion() { return plugin.getDescription().getVersion(); }
    @Override public boolean persist() { return true; }
    @Override public String onRequest(OfflinePlayer player, String params) { return plugin.placeholderValue(player, params); }
}