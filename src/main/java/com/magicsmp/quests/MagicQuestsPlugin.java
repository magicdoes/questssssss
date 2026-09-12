package com.magicsmp.quests;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.bossbar.BossBar;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.block.data.Ageable;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class MagicQuestsPlugin extends JavaPlugin implements Listener {
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacySection();
    private static final Pattern HEX = Pattern.compile("&#([A-Fa-f0-9]{6})");
    private final Map<String, Quest> quests = new LinkedHashMap<>();
    private final List<String> active = new ArrayList<>();
    private final Map<UUID, Map<String, Integer>> progress = new HashMap<>();
    private final Map<UUID, Set<String>> completed = new HashMap<>();
    private final Map<UUID, Set<String>> claimable = new HashMap<>();
    private final Map<UUID, String> selectedQuest = new HashMap<>();
    private final ConcurrentHashMap<UUID, Inventory> openMenus = new ConcurrentHashMap<>();
    private final Map<UUID, BossBar> bossBars = new HashMap<>();
    private Economy economy;
    private long intervalMillis;
    private long nextResetAt;
    private boolean dirty;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        RegisteredServiceProvider<Economy> registration = getServer().getServicesManager().getRegistration(Economy.class);
        if (registration == null) {
            getLogger().severe("No Vault economy provider found. Disabling MagicQuests.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        economy = registration.getProvider();
        Bukkit.getPluginManager().registerEvents(this, this);
        loadPluginData();
        if (Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) new QuestPlaceholders(this).register();
        Bukkit.getScheduler().runTaskTimer(this, this::tick, 20L, 20L);
        Bukkit.getScheduler().runTaskTimer(this, () -> { if (dirty) savePluginData(); }, 100L, 100L);
    }

    @Override
    public void onDisable() {
        for (Player player : Bukkit.getOnlinePlayers()) removeBossBar(player);
        if (economy != null) savePluginData();
    }

    private void loadPluginData() {
        reloadConfig();
        loadQuestDefinitions();
        intervalMillis = parseDuration(getConfig().getString("reset-interval", "15m"));
        File file = new File(getDataFolder(), "data.yml");
        YamlConfiguration data = YamlConfiguration.loadConfiguration(file);
        active.clear();
        for (String id : data.getStringList("active-quests")) if (quests.containsKey(id)) active.add(id);
        nextResetAt = data.getLong("next-reset-at", 0L);
        loadPlayerData(data);
        int wanted = Math.max(1, getConfig().getInt("active-quest-count", 3));
        if (active.isEmpty() || active.size() > wanted || nextResetAt <= System.currentTimeMillis()) {
            selectNewQuests(false);
        } else {
            savePluginData();
        }
    }

    private void loadQuestDefinitions() {
        quests.clear();
        ConfigurationSection root = getConfig().getConfigurationSection("quests");
        if (root == null) return;
        for (String id : root.getKeys(false)) {
            ConfigurationSection section = root.getConfigurationSection(id);
            if (section == null) continue;
            try {
                QuestType type = QuestType.valueOf(section.getString("type", "BREAK").toUpperCase(Locale.ROOT));
                String target = section.getString("target", "ANY").toUpperCase(Locale.ROOT);
                int required = Math.max(1, section.getInt("required", 1));
                double reward = Math.max(0, section.getDouble("reward", 0));
                String name = section.getString("name", id);
                Material icon = Material.matchMaterial(section.getString("icon", "BOOK"));
                if (icon == null) icon = Material.BOOK;
                if ((type == QuestType.BREAK || type == QuestType.PLACE) && Material.matchMaterial(target) == null) continue;
                if (type == QuestType.KILL) EntityType.valueOf(target);
                quests.put(id, new Quest(id, type, target, required, reward, name, icon));
            } catch (IllegalArgumentException exception) {
                getLogger().warning("Invalid quest: " + id);
            }
        }
    }

    private void loadPlayerData(YamlConfiguration data) {
        progress.clear();
        completed.clear();
        claimable.clear();
        selectedQuest.clear();
        ConfigurationSection root = data.getConfigurationSection("players");
        if (root == null) return;
        for (String uuidText : root.getKeys(false)) {
            try {
                UUID uuid = UUID.fromString(uuidText);
                Map<String, Integer> values = new HashMap<>();
                ConfigurationSection progressSection = root.getConfigurationSection(uuidText + ".progress");
                if (progressSection != null) {
                    for (String id : progressSection.getKeys(false)) values.put(id, progressSection.getInt(id));
                }
                progress.put(uuid, values);
                completed.put(uuid, new HashSet<>(root.getStringList(uuidText + ".completed")));
                claimable.put(uuid, new HashSet<>(root.getStringList(uuidText + ".claimable")));
                String selected = root.getString(uuidText + ".selected-quest");
                if (selected != null && quests.containsKey(selected) && active.contains(selected)) selectedQuest.put(uuid, selected);
            } catch (IllegalArgumentException ignored) { }
        }
    }

    private void tick() {
        if (System.currentTimeMillis() >= nextResetAt) selectNewQuests(true);
        refreshMenus();
        for (Player player : Bukkit.getOnlinePlayers()) removeBossBar(player);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Bukkit.getScheduler().runTaskLater(this, () -> removeBossBar(event.getPlayer()), 20L);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        removeBossBar(event.getPlayer());
        openMenus.remove(event.getPlayer().getUniqueId());
    }

    private void updateBossBar(Player player) {
        if (!getConfig().getBoolean("bossbar.enabled", true) || active.isEmpty()) {
            removeBossBar(player);
            return;
        }
        UUID uuid = player.getUniqueId();
        String selectedId = selectedQuest.get(uuid);
        Quest quest = selectedId == null ? null : quests.get(selectedId);
        if (quest == null || !active.contains(selectedId)) {
            String title = "&#ff9900&lQUESTS &8• &fChoose a quest with &#7afc00/quests &8• &fReset: &#ff9900" + formatRemaining();
            BossBar bar = bossBars.get(uuid);
            if (bar == null) {
                bar = BossBar.bossBar(component(title), 0.0f, bossBarColor(), BossBar.Overlay.PROGRESS);
                bossBars.put(uuid, bar);
                player.showBossBar(bar);
            } else {
                bar.name(component(title));
                bar.progress(0.0f);
                bar.color(bossBarColor());
            }
            return;
        }
        int value = progress.getOrDefault(player.getUniqueId(), Map.of()).getOrDefault(quest.id(), 0);
        boolean ready = claimable.getOrDefault(uuid, Set.of()).contains(quest.id());
        float barProgress = Math.max(0.0f, Math.min(1.0f, (float) value / quest.required()));
        String title = ready
                ? "&#7afc00&lQUEST COMPLETE &8• &fClick the message in chat to claim"
                : "&#ff9900&lQUEST &8• &f" + quest.name() + " &#7afc00" + value + "&7/&#7afc00" + quest.required()
                  + " &8• &#7afc00$" + money(quest.reward()) + " &8• &fReset: &#ff9900" + formatRemaining();
        BossBar bar = bossBars.get(player.getUniqueId());
        if (bar == null) {
            bar = BossBar.bossBar(component(title), barProgress, bossBarColor(), BossBar.Overlay.PROGRESS);
            bossBars.put(player.getUniqueId(), bar);
            player.showBossBar(bar);
        } else {
            bar.name(component(title));
            bar.progress(barProgress);
            bar.color(bossBarColor());
        }
    }

    private BossBar.Color bossBarColor() {
        try { return BossBar.Color.valueOf(getConfig().getString("bossbar.color", "YELLOW").toUpperCase(Locale.ROOT)); }
        catch (Exception ignored) { return BossBar.Color.YELLOW; }
    }

    private void removeBossBar(Player player) {
        BossBar bar = bossBars.remove(player.getUniqueId());
        if (bar != null) player.hideBossBar(bar);
    }

    private void selectNewQuests(boolean announce) {
        List<String> pool = new ArrayList<>(quests.keySet());
        if (pool.size() > active.size()) pool.removeAll(active);
        Collections.shuffle(pool);
        active.clear();
        int count = Math.min(Math.max(1, getConfig().getInt("active-quest-count", 3)), pool.size());
        active.addAll(pool.subList(0, count));
        progress.clear();
        completed.clear();
        claimable.clear();
        selectedQuest.clear();
        nextResetAt = System.currentTimeMillis() + intervalMillis;
        dirty = true;
        savePluginData();
        refreshMenus();
        if (announce) Bukkit.broadcast(component(message("reset")));
    }

    private void savePluginData() {
        YamlConfiguration data = new YamlConfiguration();
        data.set("next-reset-at", nextResetAt);
        data.set("active-quests", active);
        Set<UUID> players = new HashSet<>();
        players.addAll(progress.keySet());
        players.addAll(completed.keySet());
        players.addAll(claimable.keySet());
        players.addAll(selectedQuest.keySet());
        for (UUID uuid : players) {
            String base = "players." + uuid;
            for (var questEntry : progress.getOrDefault(uuid, Map.of()).entrySet())
                data.set(base + ".progress." + questEntry.getKey(), questEntry.getValue());
            data.set(base + ".completed", new ArrayList<>(completed.getOrDefault(uuid, Set.of())));
            data.set(base + ".claimable", new ArrayList<>(claimable.getOrDefault(uuid, Set.of())));
            data.set(base + ".selected-quest", selectedQuest.get(uuid));
        }
        try {
            data.save(new File(getDataFolder(), "data.yml"));
            dirty = false;
        } catch (IOException exception) {
            getLogger().warning("Could not save quest data: " + exception.getMessage());
        }
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (args.length > 0) {
            if (args[0].equalsIgnoreCase("claim")) {
                if (!(sender instanceof Player player) || args.length != 2) return true;
                returnToNpc(player, args[1]);
                return true;
            }
            if (args[0].equalsIgnoreCase("return")) {
                if (!(sender instanceof Player player) || args.length != 2) return true;
                returnToNpc(player, args[1]);
                return true;
            }
            if (!sender.hasPermission("magicquests.admin")) {
                sender.sendMessage(component("&cYou don't have permission."));
                return true;
            }
            switch (args[0].toLowerCase(Locale.ROOT)) {
                case "reload" -> {
                    loadPluginData();
                    sender.sendMessage(component(message("reload")));
                    return true;
                }
                case "reset" -> {
                    selectNewQuests(true);
                    return true;
                }
                case "settime" -> {
                    if (args.length != 2) {
                        sender.sendMessage(component("&cUsage: /quests settime <15m|30m|1h>"));
                        return true;
                    }
                    long parsed = parseDuration(args[1]);
                    if (parsed < 10_000L) {
                        sender.sendMessage(component("&cThe interval must be at least 10 seconds."));
                        return true;
                    }
                    intervalMillis = parsed;
                    getConfig().set("reset-interval", args[1].toLowerCase(Locale.ROOT));
                    saveConfig();
                    nextResetAt = System.currentTimeMillis() + intervalMillis;
                    dirty = true;
                    savePluginData();
                    sender.sendMessage(component(message("time-changed").replace("%time%", args[1])));
                    return true;
                }
                case "setnpc" -> {
                    if (!(sender instanceof Player player)) {
                        sender.sendMessage("Only a player can set the NPC location.");
                        return true;
                    }
                    Location location = player.getLocation();
                    getConfig().set("npc-location.world", location.getWorld().getName());
                    getConfig().set("npc-location.x", location.getX());
                    getConfig().set("npc-location.y", location.getY());
                    getConfig().set("npc-location.z", location.getZ());
                    getConfig().set("npc-location.yaw", location.getYaw());
                    getConfig().set("npc-location.pitch", location.getPitch());
                    saveConfig();
                    sender.sendMessage(component(message("npc-set")));
                    return true;
                }
                default -> {
                    sender.sendMessage(component("&#ff9900/quests reload, /quests reset, /quests settime <time>, /quests setnpc"));
                    return true;
                }
            }
        }
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Only players can open the quests menu.");
            return true;
        }
        openMenu(player);
        return true;
    }

    @EventHandler(ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        if (event.getBlock().getBlockData() instanceof Ageable crop && crop.getAge() < crop.getMaximumAge()) return;
        addMatchingProgress(event.getPlayer(), QuestType.BREAK, event.getBlock().getType().name(), 1);
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        addMatchingProgress(event.getPlayer(), QuestType.PLACE, event.getBlockPlaced().getType().name(), 1);
    }

    @EventHandler(ignoreCancelled = true)
    public void onKill(EntityDeathEvent event) {
        Player killer = event.getEntity().getKiller();
        if (killer != null) addMatchingProgress(killer, QuestType.KILL, event.getEntityType().name(), 1);
    }

    @EventHandler(ignoreCancelled = true)
    public void onFish(PlayerFishEvent event) {
        if (event.getState() == PlayerFishEvent.State.CAUGHT_FISH)
            addMatchingProgress(event.getPlayer(), QuestType.FISH, "ANY", 1);
    }

    private void addMatchingProgress(Player player, QuestType type, String target, int amount) {
        String id = selectedQuest.get(player.getUniqueId());
        if (id == null || !active.contains(id)) return;
        Quest quest = quests.get(id);
        if (quest == null || quest.type() != type || (!quest.target().equals("ANY") && !quest.target().equals(target))) return;
        addProgress(player, quest, amount);
    }

    private void addProgress(Player player, Quest quest, int amount) {
        UUID uuid = player.getUniqueId();
        Set<String> done = completed.computeIfAbsent(uuid, ignored -> new HashSet<>());
        Set<String> ready = claimable.computeIfAbsent(uuid, ignored -> new HashSet<>());
        if (done.contains(quest.id()) || ready.contains(quest.id())) return;
        Map<String, Integer> values = progress.computeIfAbsent(uuid, ignored -> new HashMap<>());
        int old = values.getOrDefault(quest.id(), 0);
        int updated = Math.min(quest.required(), old + amount);
        values.put(quest.id(), updated);
        dirty = true;
        int step = Math.max(1, quest.required() / 10);
        if (updated == quest.required()) {
            ready.add(quest.id());
            player.sendMessage(component(message("completed").replace("%quest%", quest.name())));
            Component claimButton = component(message("click-to-claim").replace("%reward%", money(quest.reward())))
                    .clickEvent(ClickEvent.runCommand("/quests return " + quest.id()))
                    .hoverEvent(HoverEvent.showText(component(message("claim-hover").replace("%reward%", money(quest.reward())))));
            player.sendMessage(claimButton);
            play(player, "complete");
            savePluginData();
        } else if (updated / step > old / step) {
            player.sendActionBar(component(message("progress")
                    .replace("%quest%", quest.name())
                    .replace("%progress%", String.valueOf(updated))
                    .replace("%required%", String.valueOf(quest.required()))));
            play(player, "progress");
        }
        refreshMenu(player);
    }

    private void returnToNpc(Player player, String questId) {
        Quest quest = quests.get(questId);
        if (quest == null || !active.contains(questId)) {
            player.sendMessage(component(message("claim-expired")));
            return;
        }
        UUID uuid = player.getUniqueId();
        if (completed.getOrDefault(uuid, Set.of()).contains(questId)) {
            player.sendMessage(component(message("already-claimed")));
            return;
        }
        Set<String> ready = claimable.getOrDefault(uuid, Set.of());
        if (!ready.contains(questId)) {
            player.sendMessage(component(message("claim-expired")));
            return;
        }
        Location npcLocation = npcLocation();
        if (npcLocation == null) {
            player.sendMessage(component(message("npc-not-set")));
            return;
        }
        player.teleportAsync(npcLocation).thenAccept(success -> Bukkit.getScheduler().runTask(this, () -> {
            if (!success || !player.isOnline()) return;
            player.sendMessage(component(message("returned-to-npc").replace("%quest%", quest.name())));
            openMenu(player);
        }));
    }

    private void claimReward(Player player, String questId) {
        Quest quest = quests.get(questId);
        UUID uuid = player.getUniqueId();
        if (quest == null || !active.contains(questId)
                || !claimable.getOrDefault(uuid, Set.of()).contains(questId)) {
            player.sendMessage(component(message("claim-expired")));
            play(player, "error");
            return;
        }
        Location npcLocation = npcLocation();
        double claimRadius = Math.max(1.0, getConfig().getDouble("claim-radius", 6.0));
        if (npcLocation == null || !player.getWorld().equals(npcLocation.getWorld())
                || player.getLocation().distanceSquared(npcLocation) > claimRadius * claimRadius) {
            player.sendMessage(component(message("claim-near-npc")));
            play(player, "error");
            return;
        }
        Set<String> currentReady = claimable.computeIfAbsent(uuid, ignored -> new HashSet<>());
        if (!economy.hasAccount(player)) economy.createPlayerAccount(player);
        EconomyResponse response = economy.depositPlayer(player, quest.reward());
        if (!response.transactionSuccess()) {
            player.sendMessage(component(message("payout-failed")));
            play(player, "error");
            getLogger().warning("Vault could not pay " + player.getName() + " $" + money(quest.reward())
                    + " for quest " + questId + ": " + response.errorMessage);
            return;
        }
        currentReady.remove(questId);
            completed.computeIfAbsent(uuid, ignored -> new HashSet<>()).add(questId);
            selectedQuest.remove(uuid);
        player.sendMessage(component(message("claimed")
                .replace("%quest%", quest.name())
                .replace("%reward%", money(quest.reward()))));
        play(player, "complete");
        dirty = true;
        savePluginData();
        refreshMenu(player);
    }

    private Location npcLocation() {
        String worldName = getConfig().getString("npc-location.world", "");
        if (worldName.isBlank() || Bukkit.getWorld(worldName) == null) return null;
        return new Location(Bukkit.getWorld(worldName),
                getConfig().getDouble("npc-location.x"),
                getConfig().getDouble("npc-location.y"),
                getConfig().getDouble("npc-location.z"),
                (float) getConfig().getDouble("npc-location.yaw"),
                (float) getConfig().getDouble("npc-location.pitch"));
    }

    private void openMenu(Player player) {
        QuestHolder holder = new QuestHolder();
        Inventory inventory = Bukkit.createInventory(holder, 54, component("&8ᴍᴀɢɪᴄѕᴍᴘ ǫᴜᴇѕᴛѕ"));
        holder.inventory = inventory;
        openMenus.put(player.getUniqueId(), inventory);
        fillMenu(player, inventory);
        player.openInventory(inventory);
    }

    private void fillMenu(Player player, Inventory inventory) {
        ItemStack filler = item(Material.GRAY_STAINED_GLASS_PANE, "&7 ", List.of());
        for (int slot = 0; slot < 54; slot++) inventory.setItem(slot, filler);
        int[] slots = {20, 22, 24, 29, 31, 33};
        UUID uuid = player.getUniqueId();
        String selectedId = selectedQuest.get(uuid);
        for (int i = 0; i < active.size() && i < slots.length; i++) {
            Quest quest = quests.get(active.get(i));
            int value = progress.getOrDefault(player.getUniqueId(), Map.of()).getOrDefault(quest.id(), 0);
            boolean done = completed.getOrDefault(player.getUniqueId(), Set.of()).contains(quest.id());
            boolean ready = claimable.getOrDefault(player.getUniqueId(), Set.of()).contains(quest.id());
            List<String> lore = new ArrayList<>();
            lore.add("&fTask: &e" + description(quest));
            lore.add("&fProgress: &#7afc00" + value + "&7/&#7afc00" + quest.required());
            lore.add("&fReward: &#7afc00$" + money(quest.reward()));
            lore.add("");
            if (done) lore.add("&#7afc00&lREWARD CLAIMED");
            else if (ready) lore.add("&#7afc00&lCLICK TO RECEIVE $" + money(quest.reward()));
            else if (quest.id().equals(selectedId)) lore.add("&#7afc00&lSELECTED QUEST");
            else if (selectedId != null) lore.add("&cFinish your selected quest first.");
            else lore.add("&eClick to select this quest!");
            inventory.setItem(slots[i], item(quest.icon(), "&#ff9900&l" + quest.name(), lore));
        }
        inventory.setItem(49, item(Material.CLOCK, "&#ff9900&lTIME UNTIL NEW QUESTS",
                List.of("&f" + formatRemaining(), "", "&7Reset interval: &f" + formatDuration(intervalMillis))));
    }

    private void refreshMenus() {
        for (UUID uuid : new ArrayList<>(openMenus.keySet())) {
            Player player = Bukkit.getPlayer(uuid);
            if (player == null || !(player.getOpenInventory().getTopInventory().getHolder() instanceof QuestHolder)) {
                openMenus.remove(uuid);
            } else fillMenu(player, openMenus.get(uuid));
        }
    }

    private void refreshMenu(Player player) {
        Inventory inventory = openMenus.get(player.getUniqueId());
        if (inventory != null) fillMenu(player, inventory);
    }

    @EventHandler
    public void onMenuClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof QuestHolder)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player) || event.getClickedInventory() != event.getView().getTopInventory()) return;
        int[] slots = {20, 22, 24, 29, 31, 33};
        for (int i = 0; i < active.size() && i < slots.length; i++) {
            if (event.getRawSlot() == slots[i]) {
                String questId = active.get(i);
                if (claimable.getOrDefault(player.getUniqueId(), Set.of()).contains(questId)) {
                    claimReward(player, questId);
                } else {
                    selectQuest(player, questId);
                }
                return;
            }
        }
    }

    private void selectQuest(Player player, String questId) {
        UUID uuid = player.getUniqueId();
        String current = selectedQuest.get(uuid);
        if (current != null) {
            player.sendMessage(component(current.equals(questId) ? message("already-selected") : message("finish-selected")));
            play(player, "error");
            return;
        }
        Quest quest = quests.get(questId);
        if (quest == null || !active.contains(questId)) return;
        selectedQuest.put(uuid, questId);
        progress.computeIfAbsent(uuid, ignored -> new HashMap<>()).put(questId, 0);
        player.sendMessage(component(message("selected").replace("%quest%", quest.name())));
        play(player, "selected");
        dirty = true;
        savePluginData();
        refreshMenu(player);
    }

    @EventHandler
    public void onMenuClose(InventoryCloseEvent event) {
        if (event.getInventory().getHolder() instanceof QuestHolder) openMenus.remove(event.getPlayer().getUniqueId());
    }

    public String formatRemaining() {
        long seconds = Math.max(0, (nextResetAt - System.currentTimeMillis() + 999) / 1000);
        long hours = seconds / 3600;
        long minutes = (seconds % 3600) / 60;
        long secs = seconds % 60;
        return hours > 0 ? String.format(Locale.ROOT, "%dh %02dm %02ds", hours, minutes, secs)
                : String.format(Locale.ROOT, "%02dm %02ds", minutes, secs);
    }

    public String configuredInterval() { return formatDuration(intervalMillis); }
    public String activeQuestNames() {
        return active.stream().map(quests::get).filter(Objects::nonNull).map(Quest::name).reduce((a, b) -> a + ", " + b).orElse("None");
    }

    private String description(Quest quest) {
        String verb = switch (quest.type()) { case BREAK -> "Break"; case PLACE -> "Place"; case KILL -> "Defeat"; case FISH -> "Catch"; };
        String target = quest.target().equals("ANY") ? "fish" : pretty(quest.target());
        return verb + " " + quest.required() + " " + target;
    }

    private String money(double value) { return value == Math.rint(value) ? String.valueOf((long) value) : String.format(Locale.ROOT, "%.2f", value); }
    private String message(String key) { return getConfig().getString("messages." + key, ""); }
    private void play(Player player, String key) {
        try { player.playSound(player.getLocation(), Sound.valueOf(getConfig().getString("sounds." + key, "UI_BUTTON_CLICK")), 1f, 1f); }
        catch (Exception ignored) { }
    }

    private ItemStack item(Material material, String name, List<String> lore) {
        ItemStack stack = new ItemStack(material);
        ItemMeta meta = stack.getItemMeta();
        meta.displayName(component(name));
        meta.lore(lore.stream().map(this::component).toList());
        stack.setItemMeta(meta);
        return stack;
    }

    private Component component(String input) { return LEGACY.deserialize(color(input)); }
    private String color(String input) {
        Matcher matcher = HEX.matcher(input == null ? "" : input);
        StringBuffer output = new StringBuffer();
        while (matcher.find()) {
            StringBuilder replacement = new StringBuilder("§x");
            for (char character : matcher.group(1).toCharArray()) replacement.append('§').append(character);
            matcher.appendReplacement(output, Matcher.quoteReplacement(replacement.toString()));
        }
        matcher.appendTail(output);
        return ChatColor.translateAlternateColorCodes('&', output.toString());
    }

    private String pretty(String material) {
        StringBuilder result = new StringBuilder();
        for (String word : material.toLowerCase(Locale.ROOT).split("_"))
            result.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1)).append(' ');
        return result.toString().trim();
    }

    private long parseDuration(String input) {
        if (input == null || input.isBlank()) return 900_000L;
        String value = input.trim().toLowerCase(Locale.ROOT);
        try {
            if (value.endsWith("s")) return Long.parseLong(value.substring(0, value.length() - 1)) * 1000L;
            if (value.endsWith("m")) return Long.parseLong(value.substring(0, value.length() - 1)) * 60_000L;
            if (value.endsWith("h")) return Long.parseLong(value.substring(0, value.length() - 1)) * 3_600_000L;
        } catch (NumberFormatException ignored) { }
        return 900_000L;
    }

    private String formatDuration(long millis) {
        long seconds = Math.max(1, millis / 1000);
        if (seconds % 3600 == 0) return (seconds / 3600) + "h";
        if (seconds % 60 == 0) return (seconds / 60) + "m";
        return seconds + "s";
    }

    private enum QuestType { BREAK, PLACE, KILL, FISH }
    private record Quest(String id, QuestType type, String target, int required, double reward, String name, Material icon) { }
    private static final class QuestHolder implements InventoryHolder {
        private Inventory inventory;
        @Override public @NotNull Inventory getInventory() { return Objects.requireNonNull(inventory); }
    }
}
