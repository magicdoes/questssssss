# MagicQuests

Players choose one of the three active quests in `/quests`. Only the selected quest gains progress. After claiming its reward, the player can immediately select another available quest.

Global rotating quests for Paper 1.21.4+, with per-player progress, Vault money
rewards, a live `/quests` GUI timer, and PlaceholderAPI support.

Every online player also receives a boss bar at the top of their screen. It
cycles through their active quests every five seconds and shows progress,
reward, and time until reset.

## Commands

- `/quests` - open the GUI
- `/quests reload` - reload configuration
- `/quests reset` - immediately choose new quests
- `/quests settime 15m` - change the reset interval
- `/quests setnpc` - save the quest NPC location where you are standing

When a quest is completed, its reward becomes claimable. The player receives a
clickable chat button that teleports them to the saved NPC location and pays the
Vault reward. Each reward can only be claimed once and expires at the next reset.

## Placeholders

- `%magicquests_time%`
- `%magicquests_interval%`
- `%magicquests_active%`

## NPC setup

`/npc action Quests RIGHT_CLICK add player_command quests`

Use `%magicquests_time%` in a FancyHolograms line linked to the NPC.
