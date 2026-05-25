package org.pvpbotformation;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
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

/**
 * Registers all /pvpbot formation commands.
 *
 * Mass-spawn:
 *   /pvpbot massspawn <n> grid
 *   /pvpbot massspawn <n> circle          [radius] [x y z]
 *   /pvpbot massspawn <n> circle_outward  [radius] [x y z]
 *   /pvpbot massspawn <n> triangle        [radius] [x y z]
 *   /pvpbot massspawn <n> diamond         [radius] [x y z]
 *   /pvpbot massspawn <n> diamond_outward [radius] [x y z]
 *   /pvpbot massspawn <n> hexagon         [radius] [x y z]
 *   /pvpbot massspawn <n> hexagon_outward [radius] [x y z]
 *   /pvpbot massspawn <n> arc             [radius] [x y z]
 *   /pvpbot massspawn <n> wedge           [x y z]
 *   /pvpbot massspawn <n> pincer          [radius] [x y z]
 *
 * Faction rearrange:
 *   /pvpbot faction <f> formation <label> [radius]
 *
 * Group rearrange (pvpbot-groups required):
 *   /pvpbot faction group <g> formation <label> [radius]
 */
public class FormationCommand {

    private static final int SPAWN_SETTLE_TICKS = 10;

    private static final SuggestionProvider<ServerCommandSource> FACTION_SUGGESTIONS =
            (ctx, builder) -> CommandSource.suggestMatching(getAllFactions(), builder);

    private static final SuggestionProvider<ServerCommandSource> GROUP_SUGGESTIONS =
            (ctx, builder) -> CommandSource.suggestMatching(GroupsBridge.getAllGroupNames(), builder);

    // =========================================================================
    // Registration
    // =========================================================================

    public static void register(CommandDispatcher<ServerCommandSource> dispatcher,
                                boolean groupsPresent) {
        registerMassSpawn(dispatcher);
        registerFaction(dispatcher);
        if (groupsPresent) registerGroup(dispatcher);
    }

    // -------------------------------------------------------------------------
    // /pvpbot massspawn <count> <formation> [radius] [x y z]
    // -------------------------------------------------------------------------

    private static void registerMassSpawn(CommandDispatcher<ServerCommandSource> d) {
        for (FormationManager.Formation f : FormationManager.Formation.values()) {
            final FormationManager.Formation formation = f;
            final String label = f.name().toLowerCase();

            // base literal node: executes with player pos + auto radius
            LiteralArgumentBuilder<ServerCommandSource> formNode =
                CommandManager.literal(label)
                    .executes(ctx -> cmdMassSpawnFormation(
                        ctx.getSource(),
                        IntegerArgumentType.getInteger(ctx, "count"),
                        formation, -1, null));

            if (hasRadius(f)) {
                // .then(radius) -> executes + .then(x y z)
                formNode.then(
                    CommandManager.argument("radius", DoubleArgumentType.doubleArg(1.0, 200.0))
                        .executes(ctx -> cmdMassSpawnFormation(
                            ctx.getSource(),
                            IntegerArgumentType.getInteger(ctx, "count"),
                            formation,
                            DoubleArgumentType.getDouble(ctx, "radius"),
                            null))
                        .then(CommandManager.argument("x", DoubleArgumentType.doubleArg())
                            .then(CommandManager.argument("y", DoubleArgumentType.doubleArg())
                                .then(CommandManager.argument("z", DoubleArgumentType.doubleArg())
                                    .executes(ctx -> cmdMassSpawnFormation(
                                        ctx.getSource(),
                                        IntegerArgumentType.getInteger(ctx, "count"),
                                        formation,
                                        DoubleArgumentType.getDouble(ctx, "radius"),
                                        new Vec3d(
                                            DoubleArgumentType.getDouble(ctx, "x"),
                                            DoubleArgumentType.getDouble(ctx, "y"),
                                            DoubleArgumentType.getDouble(ctx, "z")))))))
                );
            } else {
                // no radius — just optional x y z
                formNode.then(
                    CommandManager.argument("x", DoubleArgumentType.doubleArg())
                        .then(CommandManager.argument("y", DoubleArgumentType.doubleArg())
                            .then(CommandManager.argument("z", DoubleArgumentType.doubleArg())
                                .executes(ctx -> cmdMassSpawnFormation(
                                    ctx.getSource(),
                                    IntegerArgumentType.getInteger(ctx, "count"),
                                    formation, -1,
                                    new Vec3d(
                                        DoubleArgumentType.getDouble(ctx, "x"),
                                        DoubleArgumentType.getDouble(ctx, "y"),
                                        DoubleArgumentType.getDouble(ctx, "z"))))))
                );
            }

            d.register(
                CommandManager.literal("pvpbot")
                    .then(CommandManager.literal("massspawn")
                        .then(CommandManager.argument("count", IntegerArgumentType.integer(1, 500))
                            .then(formNode)))
            );
        }
    }

    // -------------------------------------------------------------------------
    // /pvpbot faction formation <label> <faction> [radius] [x y z]
    // -------------------------------------------------------------------------

    private static void registerFaction(CommandDispatcher<ServerCommandSource> d) {
        for (FormationManager.Formation f : FormationManager.Formation.values()) {
            final FormationManager.Formation formation = f;
            final String label = f.name().toLowerCase();

            // faction name arg node — executes at player pos, auto radius
            LiteralArgumentBuilder<ServerCommandSource> formNode =
                CommandManager.literal(label)
                    .then(CommandManager.argument("faction", StringArgumentType.word())
                        .suggests(FACTION_SUGGESTIONS)
                        .executes(ctx -> cmdFactionFormation(
                            ctx.getSource(),
                            StringArgumentType.getString(ctx, "faction"),
                            formation, -1, null))
                        .then(buildFactionRadiusCoords(formation))
                        .then(buildFactionCoordsOnly(formation))
                    );

            d.register(
                CommandManager.literal("pvpbot")
                    .then(CommandManager.literal("faction")
                        .then(CommandManager.literal("formation")
                            .then(formNode)))
            );
        }
    }

    /** radius [x y z] branch for faction commands */
    private static com.mojang.brigadier.builder.RequiredArgumentBuilder<ServerCommandSource, Double>
            buildFactionRadiusCoords(FormationManager.Formation formation) {
        return CommandManager.argument("radius", DoubleArgumentType.doubleArg(1.0, 200.0))
            .executes(ctx -> cmdFactionFormation(
                ctx.getSource(),
                StringArgumentType.getString(ctx, "faction"),
                formation,
                DoubleArgumentType.getDouble(ctx, "radius"),
                null))
            .then(CommandManager.argument("x", DoubleArgumentType.doubleArg())
                .then(CommandManager.argument("y", DoubleArgumentType.doubleArg())
                    .then(CommandManager.argument("z", DoubleArgumentType.doubleArg())
                        .executes(ctx -> cmdFactionFormation(
                            ctx.getSource(),
                            StringArgumentType.getString(ctx, "faction"),
                            formation,
                            DoubleArgumentType.getDouble(ctx, "radius"),
                            new Vec3d(
                                DoubleArgumentType.getDouble(ctx, "x"),
                                DoubleArgumentType.getDouble(ctx, "y"),
                                DoubleArgumentType.getDouble(ctx, "z")))))));
    }

    /** x y z only (no radius) branch for faction commands */
    private static com.mojang.brigadier.builder.RequiredArgumentBuilder<ServerCommandSource, Double>
            buildFactionCoordsOnly(FormationManager.Formation formation) {
        return CommandManager.argument("x", DoubleArgumentType.doubleArg())
            .then(CommandManager.argument("y", DoubleArgumentType.doubleArg())
                .then(CommandManager.argument("z", DoubleArgumentType.doubleArg())
                    .executes(ctx -> cmdFactionFormation(
                        ctx.getSource(),
                        StringArgumentType.getString(ctx, "faction"),
                        formation, -1,
                        new Vec3d(
                            DoubleArgumentType.getDouble(ctx, "x"),
                            DoubleArgumentType.getDouble(ctx, "y"),
                            DoubleArgumentType.getDouble(ctx, "z"))))));
    }

    // -------------------------------------------------------------------------
    // /pvpbot faction group formation <label> <group> [radius] [x y z]
    // -------------------------------------------------------------------------

    private static void registerGroup(CommandDispatcher<ServerCommandSource> d) {
        for (FormationManager.Formation f : FormationManager.Formation.values()) {
            final FormationManager.Formation formation = f;
            final String label = f.name().toLowerCase();

            LiteralArgumentBuilder<ServerCommandSource> formNode =
                CommandManager.literal(label)
                    .then(CommandManager.argument("group", StringArgumentType.word())
                        .suggests(GROUP_SUGGESTIONS)
                        .executes(ctx -> cmdGroupFormation(
                            ctx.getSource(),
                            StringArgumentType.getString(ctx, "group"),
                            formation, -1, null))
                        .then(buildGroupRadiusCoords(formation))
                        .then(buildGroupCoordsOnly(formation))
                    );

            d.register(
                CommandManager.literal("pvpbot")
                    .then(CommandManager.literal("faction")
                        .then(CommandManager.literal("group")
                            .then(CommandManager.literal("formation")
                                .then(formNode))))
            );
        }
    }

    private static com.mojang.brigadier.builder.RequiredArgumentBuilder<ServerCommandSource, Double>
            buildGroupRadiusCoords(FormationManager.Formation formation) {
        return CommandManager.argument("radius", DoubleArgumentType.doubleArg(1.0, 200.0))
            .executes(ctx -> cmdGroupFormation(
                ctx.getSource(),
                StringArgumentType.getString(ctx, "group"),
                formation,
                DoubleArgumentType.getDouble(ctx, "radius"),
                null))
            .then(CommandManager.argument("x", DoubleArgumentType.doubleArg())
                .then(CommandManager.argument("y", DoubleArgumentType.doubleArg())
                    .then(CommandManager.argument("z", DoubleArgumentType.doubleArg())
                        .executes(ctx -> cmdGroupFormation(
                            ctx.getSource(),
                            StringArgumentType.getString(ctx, "group"),
                            formation,
                            DoubleArgumentType.getDouble(ctx, "radius"),
                            new Vec3d(
                                DoubleArgumentType.getDouble(ctx, "x"),
                                DoubleArgumentType.getDouble(ctx, "y"),
                                DoubleArgumentType.getDouble(ctx, "z")))))));
    }

    private static com.mojang.brigadier.builder.RequiredArgumentBuilder<ServerCommandSource, Double>
            buildGroupCoordsOnly(FormationManager.Formation formation) {
        return CommandManager.argument("x", DoubleArgumentType.doubleArg())
            .then(CommandManager.argument("y", DoubleArgumentType.doubleArg())
                .then(CommandManager.argument("z", DoubleArgumentType.doubleArg())
                    .executes(ctx -> cmdGroupFormation(
                        ctx.getSource(),
                        StringArgumentType.getString(ctx, "group"),
                        formation, -1,
                        new Vec3d(
                            DoubleArgumentType.getDouble(ctx, "x"),
                            DoubleArgumentType.getDouble(ctx, "y"),
                            DoubleArgumentType.getDouble(ctx, "z"))))));
    }

    // =========================================================================
    // Handler — mass-spawn
    // =========================================================================

    private static int cmdMassSpawnFormation(ServerCommandSource src, int count,
                                             FormationManager.Formation formation,
                                             double radius, Vec3d overridePos) {
        ServerPlayerEntity caller = src.getPlayer();
        Vec3d anchorPos;
        float anchorYaw;

        if (overridePos != null) {
            anchorPos = overridePos;
            anchorYaw = (caller != null) ? caller.getYaw() : 0f;
        } else {
            if (caller == null) {
                src.sendError(Text.literal("§cThis command must be run by a player, or supply x y z coords."));
                return 0;
            }
            anchorPos = new Vec3d(caller.getX(), caller.getY(), caller.getZ());
            anchorYaw = caller.getYaw();
        }

        Set<String> existingBots = new HashSet<>(getAllBots());
        List<double[]> slots = FormationManager.computeSlots(
            anchorPos, anchorYaw, count, formation, radius, null);

        try {
            src.getServer()
               .getCommandManager()
               .getDispatcher()
               .execute("pvpbot massspawn " + count, src.getServer().getCommandSource());
        } catch (Exception e) {
            src.sendError(Text.literal("§cFailed to trigger massspawn: " + e.getMessage()));
            return 0;
        }

        String formLabel  = prettyName(formation);
        String radiusHint = (radius > 0) ? String.format(" §7r=%.1f", radius) : "";
        String coordHint  = overridePos != null
            ? String.format(" §7(centre %.1f %.1f %.1f)", anchorPos.x, anchorPos.y, anchorPos.z)
            : "";
        src.sendFeedback(() -> Text.literal(
            "§eMass spawning §f" + count + " §ebots into §f" + formLabel
            + " §eformation..." + radiusHint + coordHint
        ), false);

        int timeout = count * 5 + 100;
        placeBotsAsTheySpawn(src.getServer(), src, existingBots, slots, timeout, formLabel);
        return 1;
    }

    // =========================================================================
    // Handler — faction
    // =========================================================================

    private static int cmdFactionFormation(ServerCommandSource src, String faction,
                                           FormationManager.Formation formation,
                                           double radius, Vec3d overridePos) {
        ServerPlayerEntity caller = src.getPlayer();
        if (caller == null && overridePos == null) {
            src.sendError(Text.literal("§cThis command must be run by a player, or supply x y z coords."));
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
        int placed;
        if (overridePos != null) {
            float yaw = (caller != null) ? caller.getYaw() : 0f;
            placed = FormationManager.applyFormationAtPos(
                src.getServer(), overridePos, yaw, bots, formation, radius, null);
        } else {
            placed = FormationManager.applyFormation(
                src.getServer(), caller, bots, formation, radius, null);
        }
        final int c = placed;
        String label = prettyName(formation);
        String coordHint = overridePos != null
            ? String.format(" §7(centre %.1f %.1f %.1f)", overridePos.x, overridePos.y, overridePos.z)
            : "";
        src.sendFeedback(() -> Text.literal(
            "§a" + label + " formation applied: §f" + c
            + " §abots in faction §f'" + faction + "'§a arranged." + coordHint
        ), true);
        return placed;
    }

    // =========================================================================
    // Handler — group
    // =========================================================================

    private static int cmdGroupFormation(ServerCommandSource src, String group,
                                         FormationManager.Formation formation,
                                         double radius, Vec3d overridePos) {
        ServerPlayerEntity caller = src.getPlayer();
        if (caller == null && overridePos == null) {
            src.sendError(Text.literal("§cThis command must be run by a player, or supply x y z coords."));
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
        int placed;
        if (overridePos != null) {
            float yaw = (caller != null) ? caller.getYaw() : 0f;
            placed = FormationManager.applyFormationAtPos(
                src.getServer(), overridePos, yaw, bots, formation, radius, null);
        } else {
            placed = FormationManager.applyFormation(
                src.getServer(), caller, bots, formation, radius, null);
        }
        final int c = placed;
        String label = prettyName(formation);
        String coordHint = overridePos != null
            ? String.format(" §7(centre %.1f %.1f %.1f)", overridePos.x, overridePos.y, overridePos.z)
            : "";
        src.sendFeedback(() -> Text.literal(
            "§a" + label + " formation applied: §f" + c
            + " §abots in group §f'" + group + "'§a arranged." + coordHint
        ), true);
        return placed;
    }

    // =========================================================================
    // Deferred placement loop
    // =========================================================================

    private static void placeBotsAsTheySpawn(MinecraftServer server,
                                              ServerCommandSource src,
                                              Set<String> existingBots,
                                              List<double[]> slots,
                                              int timeoutTicks,
                                              String formLabel) {
        final int POLL = 5;
        final int[] elapsed  = {0};
        final int[] nextSlot = {0};
        final Set<String> placed = new HashSet<>();

        server.execute(new Runnable() {
            @Override
            public void run() {
                for (String name : getAllBots()) {
                    if (existingBots.contains(name)) continue;
                    if (placed.contains(name))       continue;
                    if (nextSlot[0] >= slots.size()) continue;
                    ServerPlayerEntity player = server.getPlayerManager().getPlayer(name);
                    if (player == null) continue;

                    final double[] slot    = slots.get(nextSlot[0]);
                    final String   botName = name;
                    placed.add(name);
                    nextSlot[0]++;
                    scheduleIn(server, SPAWN_SETTLE_TICKS,
                        () -> FormationManager.placeBot(server, botName, slot));
                }

                if (placed.size() >= slots.size()) {
                    scheduleIn(server, SPAWN_SETTLE_TICKS + 2, () -> {
                        final int n = placed.size();
                        src.sendFeedback(() -> Text.literal(
                            "§a" + formLabel + " complete \u2014 §f" + n + " §abots placed."
                        ), true);
                    });
                    return;
                }

                if (elapsed[0] >= timeoutTicks) {
                    final int n = placed.size();
                    if (n > 0) {
                        src.sendFeedback(() -> Text.literal(
                            "§e" + formLabel + " timed out \u2014 §f"
                            + n + "/" + slots.size() + " §ebots placed."
                        ), true);
                    } else {
                        src.sendFeedback(() -> Text.literal(
                            "§eNo bots detected. Use §a/pvpbot faction <faction> formation "
                            + formLabel.toLowerCase()
                            + " §eonce bots are ready."
                        ), false);
                    }
                    return;
                }

                elapsed[0] += POLL;
                scheduleIn(server, POLL, this);
            }
        });
    }

    // =========================================================================
    // Tick scheduler
    // =========================================================================

    private static void scheduleIn(MinecraftServer server, int ticks, Runnable task) {
        if (ticks <= 0) { server.execute(task); return; }
        long targetTick = server.getTicks() + ticks;
        server.execute(new Runnable() {
            @Override
            public void run() {
                if (server.getTicks() < targetTick) server.execute(this);
                else task.run();
            }
        });
    }

    // =========================================================================
    // Reflection helpers
    // =========================================================================

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
            Set<String> members = (Set<String>) fc.getMethod("getMembers", String.class)
                .invoke(null, faction);
            Class<?> mc = Class.forName("org.stepan1411.pvp_bot.bot.BotManager");
            Set<String> allBots = (Set<String>) mc.getMethod("getAllBots").invoke(null);
            List<String> result = new ArrayList<>();
            for (String name : members) {
                if (allBots.contains(name)
                        && src.getServer().getPlayerManager().getPlayer(name) != null) {
                    result.add(name);
                }
            }
            return result;
        } catch (Exception e) {
            PvpBotFormationAddon.LOGGER.error("Formation: pvpbot API error — {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    // =========================================================================
    // Utility
    // =========================================================================

    private static boolean hasRadius(FormationManager.Formation f) {
        switch (f) {
            case CIRCLE: case CIRCLE_OUTWARD:
            case TRIANGLE:
            case DIAMOND: case DIAMOND_OUTWARD:
            case HEXAGON: case HEXAGON_OUTWARD:
            case ARC:
            case PINCER:
                return true;
            default:
                return false;
        }
    }

    private static String prettyName(FormationManager.Formation f) {
        switch (f) {
            case GRID:            return "Grid";
            case CIRCLE:          return "Circle";
            case CIRCLE_OUTWARD:  return "Circle (defence)";
            case TRIANGLE:        return "Triangle";
            case DIAMOND:         return "Diamond";
            case DIAMOND_OUTWARD: return "Diamond (defence)";
            case HEXAGON:         return "Hexagon";
            case HEXAGON_OUTWARD: return "Hexagon (defence)";
            case ARC:             return "Arc";
            case WEDGE:           return "Wedge";
            case PINCER:          return "Pincer";
            default:              return f.name();
        }
    }
}
