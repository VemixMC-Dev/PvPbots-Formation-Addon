package org.pvpbotformation;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.minecraft.command.CommandSource;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class FormationCommand {

    /**
     * How many ticks to wait after a new bot is detected online before
     * teleporting it into formation.  This lets the base mod's own spawn /
     * join handler finish first so our teleport isn't immediately overwritten
     * (the root cause of bots ending up off-formation after massspawn).
     */
    private static final int SPAWN_SETTLE_TICKS = 10;

    private static final SuggestionProvider<ServerCommandSource> FACTION_SUGGESTIONS =
            (ctx, builder) -> CommandSource.suggestMatching(getAllFactions(), builder);

    private static final SuggestionProvider<ServerCommandSource> GROUP_SUGGESTIONS =
            (ctx, builder) -> CommandSource.suggestMatching(GroupsBridge.getAllGroupNames(), builder);

    // ---- Registration --------------------------------------------------------

    public static void register(CommandDispatcher<ServerCommandSource> dispatcher,
                                boolean groupsPresent) {

        // /pvpbot massspawn <count> grid
        // /pvpbot massspawn <count> grid <x> <y> <z>
        dispatcher.register(
            CommandManager.literal("pvpbot")
                .then(CommandManager.literal("massspawn")
                    .then(CommandManager.argument("count", IntegerArgumentType.integer(1, 500))
                        .then(CommandManager.literal("grid")
                            .executes(ctx -> cmdMassSpawnGrid(
                                ctx.getSource(),
                                IntegerArgumentType.getInteger(ctx, "count"),
                                null
                            ))
                            .then(CommandManager.argument("x", DoubleArgumentType.doubleArg())
                                .then(CommandManager.argument("y", DoubleArgumentType.doubleArg())
                                    .then(CommandManager.argument("z", DoubleArgumentType.doubleArg())
                                        .executes(ctx -> cmdMassSpawnGrid(
                                            ctx.getSource(),
                                            IntegerArgumentType.getInteger(ctx, "count"),
                                            new Vec3d(
                                                DoubleArgumentType.getDouble(ctx, "x"),
                                                DoubleArgumentType.getDouble(ctx, "y"),
                                                DoubleArgumentType.getDouble(ctx, "z")
                                            )
                                        ))
                                    )
                                )
                            )
                        )
                    )
                )
        );

        // /pvpbot faction <faction> formation grid
        dispatcher.register(
            CommandManager.literal("pvpbot")
                .then(CommandManager.literal("faction")
                    .then(CommandManager.argument("faction", StringArgumentType.word())
                        .suggests(FACTION_SUGGESTIONS)
                        .then(CommandManager.literal("formation")
                            .then(CommandManager.literal("grid")
                                .executes(ctx -> cmdFactionGrid(
                                    ctx.getSource(),
                                    StringArgumentType.getString(ctx, "faction")
                                ))
                            )
                        )
                    )
                )
        );

        if (groupsPresent) {
            dispatcher.register(
                CommandManager.literal("pvpbot")
                    .then(CommandManager.literal("faction")
                        .then(CommandManager.literal("group")
                            .then(CommandManager.argument("group", StringArgumentType.word())
                                .suggests(GROUP_SUGGESTIONS)
                                .then(CommandManager.literal("formation")
                                    .then(CommandManager.literal("grid")
                                        .executes(ctx -> cmdGroupGrid(
                                            ctx.getSource(),
                                            StringArgumentType.getString(ctx, "group")
                                        ))
                                    )
                                )
                            )
                        )
                    )
            );
        }
    }

    // ---- Handlers ------------------------------------------------------------

    /**
     * /pvpbot massspawn <count> grid
     * /pvpbot massspawn <count> grid <x> <y> <z>
     *
     * Pre-computes all N grid slots immediately, then polls every 5 ticks.
     * Each bot is scheduled for teleport SPAWN_SETTLE_TICKS after it comes
     * online so the base mod's join handler runs first (fixes the formation
     * bug where bots ended up off-position after spawn).
     *
     * When x/y/z are supplied the formation is centred on those coordinates.
     * Otherwise the calling player's position is used as the anchor.
     */
    private static int cmdMassSpawnGrid(ServerCommandSource src, int count, Vec3d overridePos) {
        ServerPlayerEntity caller = src.getPlayer();

        Vec3d anchorPos;
        float anchorYaw;

        if (overridePos != null) {
            anchorPos = overridePos;
            // Preserve player facing for formation direction if available
            anchorYaw = (caller != null) ? caller.getYaw() : 0f;
        } else {
            if (caller == null) {
                src.sendError(Text.literal("§cThis command must be run by a player, or supply x y z coords."));
                return 0;
            }
            anchorPos = new Vec3d(caller.getX(), caller.getY(), caller.getZ());
            anchorYaw = caller.getYaw();
        }

        // Snapshot existing bots BEFORE spawning
        Set<String> existingBots = new HashSet<>(getAllBots());

        // Pre-compute ALL grid slots now (anchor position frozen at command time)
        List<double[]> slots = FormationManager.computeSlots(anchorPos, anchorYaw, count);

        // Trigger the base massspawn
        try {
            src.getServer()
               .getCommandManager()
               .getDispatcher()
               .execute("pvpbot massspawn " + count, src.getServer().getCommandSource());
        } catch (Exception e) {
            src.sendError(Text.literal("§cFailed to trigger massspawn: " + e.getMessage()));
            return 0;
        }

        String coordHint = overridePos != null
            ? String.format(" §7(centre %.1f %.1f %.1f)", anchorPos.x, anchorPos.y, anchorPos.z)
            : "";
        src.sendFeedback(() -> Text.literal(
            "§eMass spawning §f" + count + " §ebots into grid formation..." + coordHint
        ), false);

        // Poll and place each bot as it appears
        int timeout = count * 5 + 100;
        placeBotsAsTheySpawn(src.getServer(), src, existingBots, slots, timeout);

        return 1;
    }

    /**
     * Polls every 5 ticks for newly-online bots and teleports each one.
     *
     * Stop condition: we keep polling until placed == slots.size().
     * We never exit early based on pending state or slot counters alone,
     * because the base mod adds bot names to its registry BEFORE the player
     * is actually online, and because HashSet iteration order is random so a
     * pending bot could be "seen" before all slots appear full.
     *
     * The only two exit conditions are:
     *   1. placed.size() == slots.size()  (every slot has a confirmed teleport)
     *   2. Hard timeout (gives up gracefully)
     */
    private static void placeBotsAsTheySpawn(MinecraftServer server,
                                              ServerCommandSource src,
                                              Set<String> existingBots,
                                              List<double[]> slots,
                                              int timeoutTicks) {
        final int POLL = 5;
        final int[] elapsed      = {0};
        final int[] nextSlot     = {0};
        final Set<String> placed = new HashSet<>();

        server.execute(new Runnable() {
            @Override
            public void run() {
                // Scan ALL bots the base mod knows about.
                for (String name : getAllBots()) {
                    if (existingBots.contains(name)) continue; // pre-existing bot
                    if (placed.contains(name))       continue; // already assigned
                    if (nextSlot[0] >= slots.size()) continue; // all slots taken

                    // Only assign once the player is actually online.
                    ServerPlayerEntity player = server.getPlayerManager().getPlayer(name);
                    if (player == null) continue; // not online yet — revisit next poll

                    final double[] slot    = slots.get(nextSlot[0]);
                    final String   botName = name;
                    placed.add(name);
                    nextSlot[0]++;

                    // Do NOT teleport immediately — the base mod's join/spawn handler
                    // fires after the player is detected online and will overwrite any
                    // teleport we issue right now, leaving the bot stuck at spawn.
                    // Only issue the teleport after SPAWN_SETTLE_TICKS so the base mod
                    // finishes its own setup first.
                    scheduleIn(server, SPAWN_SETTLE_TICKS,
                        () -> FormationManager.placeBot(server, botName, slot));
                }

                // Once all slots are assigned, wait SPAWN_SETTLE_TICKS more before
                // declaring done — this ensures all the delayed placeBot calls have
                // actually fired before we stop polling.
                if (placed.size() >= slots.size()) {
                    scheduleIn(server, SPAWN_SETTLE_TICKS + 2, () -> {
                        final int n = placed.size();
                        src.sendFeedback(() -> Text.literal(
                            "\u00a7aGrid complete \u2014 \u00a7f" + n + " \u00a7abots placed."
                        ), true);
                    });
                    return;
                }

                if (elapsed[0] >= timeoutTicks) {
                    final int n = placed.size();
                    if (n > 0) {
                        src.sendFeedback(() -> Text.literal(
                            "\u00a7eGrid timed out \u2014 \u00a7f" + n + "/" + slots.size() + " \u00a7ebots placed."
                        ), true);
                    } else {
                        src.sendFeedback(() -> Text.literal(
                            "\u00a7eNo bots detected. Use \u00a7a/pvpbot faction <faction> formation grid \u00a7eonce bots are ready."
                        ), false);
                    }
                    return;
                }

                elapsed[0] += POLL;
                scheduleIn(server, POLL, this);
            }
        });
    }



    /**
     * Schedules {@code task} to run after exactly {@code ticks} server ticks.
     *
     * Uses the server's tick scheduler so we actually wait full ticks, rather
     * than re-queueing with server.execute() which runs in the *same* tick.
     */
    private static void scheduleIn(MinecraftServer server, int ticks, Runnable task) {
        if (ticks <= 0) { server.execute(task); return; }
        long targetTick = server.getTicks() + ticks;
        server.execute(new Runnable() {
            @Override public void run() {
                if (server.getTicks() < targetTick) {
                    server.execute(this); // re-queue until target tick reached
                } else {
                    task.run();
                }
            }
        });
    }

    // ---- Faction / group handlers --------------------------------------------

    private static int cmdFactionGrid(ServerCommandSource src, String faction) {
        ServerPlayerEntity caller = src.getPlayer();
        if (caller == null) {
            src.sendError(Text.literal("§cThis command must be run by a player."));
            return 0;
        }
        if (!getAllFactions().contains(faction)) {
            src.sendError(Text.literal("§cFaction '" + faction + "' does not exist."));
            return 0;
        }
        List<String> bots = getActiveFactionBots(src, faction);
        if (bots.isEmpty()) {
            src.sendError(Text.literal("§cNo active bots found in faction '" + faction + "'."));
            return 0;
        }
        int placed = FormationManager.applyGrid(src.getServer(), caller, bots);
        final int c = placed;
        src.sendFeedback(() -> Text.literal(
            "§aGrid formation applied: §f" + c + " §abots in faction §f'" + faction + "'§a arranged."
        ), true);
        return placed;
    }

    private static int cmdGroupGrid(ServerCommandSource src, String group) {
        ServerPlayerEntity caller = src.getPlayer();
        if (caller == null) {
            src.sendError(Text.literal("§cThis command must be run by a player."));
            return 0;
        }
        if (!GroupsBridge.groupExists(group)) {
            src.sendError(Text.literal("§cGroup '" + group + "' does not exist."));
            return 0;
        }
        Set<String> members = GroupsBridge.getGroupMembers(group);
        List<String> bots = new ArrayList<>();
        for (String name : members) {
            if (src.getServer().getPlayerManager().getPlayer(name) != null) bots.add(name);
        }
        if (bots.isEmpty()) {
            src.sendError(Text.literal("§cNo active bots found in group '" + group + "'."));
            return 0;
        }
        int placed = FormationManager.applyGrid(src.getServer(), caller, bots);
        final int c = placed;
        src.sendFeedback(() -> Text.literal(
            "§aGrid formation applied: §f" + c + " §abots in group §f'" + group + "'§a arranged."
        ), true);
        return placed;
    }

    // ---- Reflection helpers --------------------------------------------------

    @SuppressWarnings("unchecked")
    private static Set<String> getAllFactions() {
        try {
            Class<?> c = Class.forName("org.stepan1411.pvp_bot.bot.BotFaction");
            return (Set<String>) c.getMethod("getAllFactions").invoke(null);
        } catch (Exception e) { return Collections.emptySet(); }
    }

    @SuppressWarnings("unchecked")
    static Set<String> getAllBots() {
        try {
            Class<?> c = Class.forName("org.stepan1411.pvp_bot.bot.BotManager");
            return (Set<String>) c.getMethod("getAllBots").invoke(null);
        } catch (Exception e) { return Collections.emptySet(); }
    }

    @SuppressWarnings("unchecked")
    private static List<String> getActiveFactionBots(ServerCommandSource src, String faction) {
        try {
            Class<?> fc = Class.forName("org.stepan1411.pvp_bot.bot.BotFaction");
            Set<String> members = (Set<String>) fc.getMethod("getMembers", String.class).invoke(null, faction);
            Class<?> mc = Class.forName("org.stepan1411.pvp_bot.bot.BotManager");
            Set<String> allBots = (Set<String>) mc.getMethod("getAllBots").invoke(null);
            List<String> result = new ArrayList<>();
            for (String name : members) {
                if (allBots.contains(name) && src.getServer().getPlayerManager().getPlayer(name) != null)
                    result.add(name);
            }
            return result;
        } catch (Exception e) {
            PvpBotFormationAddon.LOGGER.error("Formation: pvpbot API error — {}", e.getMessage());
            return Collections.emptyList();
        }
    }
}
