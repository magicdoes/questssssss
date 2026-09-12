package com.magicsmp.quests;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public final class QuestPlaceholders extends PlaceholderExpansion {
    private final MagicQuestsPlugin plugin;

    public QuestPlaceholders(MagicQuestsPlugin plugin) { this.plugin = plugin; }
    @Override public @NotNull String getIdentifier() { return "magicquests"; }
    @Override public @NotNull String getAuthor() { return "MagicSMP"; }
    @Override public @NotNull String getVersion() { return plugin.getPluginMeta().getVersion(); }
    @Override public boolean persist() { return true; }

    @Override
    public @Nullable String onRequest(OfflinePlayer player, @NotNull String params) {
        return switch (params.toLowerCase()) {
            case "time", "next_reset", "nextreset" -> plugin.formatRemaining();
            case "interval" -> plugin.configuredInterval();
            case "active", "active_quests" -> plugin.activeQuestNames();
            default -> null;
        };
    }
}
