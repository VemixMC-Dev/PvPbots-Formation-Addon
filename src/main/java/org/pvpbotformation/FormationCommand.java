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
import net.minecraft.server.world.ServerWorld;
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
 * Spawn:
 *   /pvpbot spawn <name>
 *   /pvpbot spawn <name> <x> <y> <z>
 *   /pvpbot spawn <name> <x> <y> <z> <faction|default>
 *   /pvpbot spawn <name> <x> <y> <z> <faction|default> <kit|default>
 *
 * Mass-spawn:
 *   /pvpbot massspawn <n> <formation> [radius] [x y z]
 *   /pvpbot massspawn <n> <formation> [radius] [x y z] <faction|default>
 *   /pvpbot massspawn <n> <formation> [radius] [x y z] <faction|default> <kit|default>
 *
 * Faction rearrange:
 *   /pvpbot faction <f> formation <label> [radius] [x y z]
 *
 * Group rearrange (pvpbot-groups required):
 *   /pvpbot faction group <g> formation <label> [radius] [x y z]
 *
 * Use "default" for faction or kit to skip assignment.
 */
public class FormationCommand {

    private static final int    SPAWN_SETTLE_TICKS = 10;
    private static final String DEFAULT_KEYWORD    = "default";

    private static final SuggestionProvider<ServerCommandSource> FACTION_SUGGESTIONS =
            (ctx, builder) -> {
                List<String> opts = new ArrayList<>(getAllFactions());
                opts.add(DEFAULT_KEYWORD);
                return CommandSource.suggestMatching(opts, builder);
            };

    private static final SuggestionProvider<ServerCommandSource> KIT_SUGGESTIONS =
            (ctx, builder) -> {
                List<String> opts = new ArrayList<>(getAllKits());
                opts.add(DEFAULT_KEYWORD);
                return CommandSource.suggestMatching(opts, builder);
            };

    private static final SuggestionProvider<ServerCommandSource> GROUP_SUGGESTIONS =
            (ctx, builder) -> CommandSource.suggestMatching(GroupsBridge.getAllGroupNames(), builder);

    // =========================================================================
    // Registration
    // =========================================================================

    public static void register(CommandDispatcher<ServerCommandSource> dispatcher,
                                boolean groupsPresent) {
        registerSpawn(dispatcher);
        registerMassSpawn(dispatcher);
        registerFaction(dispatcher);
        if (groupsPresent) registerGroup(dispatcher);
    }

    // =========================================================================
    // /pvpbot spawn <name> [x y z] [faction|default] [kit|default]
    // =========================================================================

    private static void registerSpawn(CommandDispatcher<ServerCommandSource> d) {
        d.register(
            CommandManager.literal("pvpbot")
                .then(CommandManager.literal("spawn")
                    .then(CommandManager.argument("name", StringArgumentType.word())
                        .executes(ctx -> cmdSpawn(ctx.getSource(),
                            StringArgumentType.getString(ctx, "name"),
                            null, null, null))
                        .then(CommandManager.argument("x", DoubleArgumentType.doubleArg())
                            .then(CommandManager.argument("y", DoubleArgumentType.doubleArg())
                                .then(CommandManager.argument("z", DoubleArgumentType.doubleArg())
                                    .executes(ctx -> cmdSpawn(ctx.getSource(),
                                        StringArgumentType.getString(ctx, "name"),
                                        xyzOf(ctx), null, null))
                                    .then(CommandManager.argument("faction", StringArgumentType.word())
                                        .suggests(FACTION_SUGGESTIONS)
                                        .executes(ctx -> cmdSpawn(ctx.getSource(),
                                            StringArgumentType.getString(ctx, "name"),
                                            xyzOf(ctx),
                                            resolve(StringArgumentType.getString(ctx, "faction")),
                                            null))
                                        .then(CommandManager.argument("kit", StringArgumentType.word())
                                            .suggests(KIT_SUGGESTIONS)
                                            .executes(ctx -> cmdSpawn(ctx.getSource(),
                                                StringArgumentType.getString(ctx, "name"),
                                                xyzOf(ctx),
                                                resolve(StringArgumentType.getString(ctx, "faction")),
                                                resolve(StringArgumentType.getString(ctx, "kit"))))
                                        )
                                    )
                                )
                            )
                        )
                    )
                )
        );
    }

    // =========================================================================
    // /pvpbot massspawn <n> <formation> [radius] [x y z] [faction|default] [kit|default]
    // =========================================================================

    private static void registerMassSpawn(CommandDispatcher<ServerCommandSource> d) {
        for (FormationManager.Formation f : FormationManager.Formation.values()) {
            final FormationManager.Formation formation = f;
            final String label = f.name().toLowerCase();

            LiteralArgumentBuilder<ServerCommandSource> formNode =
                CommandManager.literal(label)
                    .executes(ctx -> cmdMassSpawnFormation(
                        ctx.getSource(),
                        IntegerArgumentType.getInteger(ctx, "count"),
                        formation, -1, null, null, null));

            if (hasRadius(f)) {
                formNode.then(
                    CommandManager.argument("radius", radiusArg(formation))
                        .executes(ctx -> cmdMassSpawnFormation(
                            ctx.getSource(),
                            IntegerArgumentType.getInteger(ctx, "count"),
                            formation,
                            DoubleArgumentType.getDouble(ctx, "radius"),
                            null, null, null))
                        .then(buildMassTail(formation, true))
                );
                formNode.then(buildMassTail(formation, false));
            } else {
                formNode.then(buildMassTail(formation, false));
            }

            d.register(
                CommandManager.literal("pvpbot")
                    .then(CommandManager.literal("massspawn")
                        .then(CommandManager.argument("count", IntegerArgumentType.integer(1, 500))
                            .then(formNode)))
            );
        }
    }

    private static com.mojang.brigadier.builder.RequiredArgumentBuilder<ServerCommandSource, Double>
            buildMassTail(FormationManager.Formation formation, boolean radiusParsed) {
        return CommandManager.argument("x", DoubleArgumentType.doubleArg())
            .then(CommandManager.argument("y", DoubleArgumentType.doubleArg())
                .then(CommandManager.argument("z", DoubleArgumentType.doubleArg())
                    .executes(ctx -> cmdMassSpawnFormation(ctx.getSource(),
                        IntegerArgumentType.getInteger(ctx, "count"),
                        formation,
                        radiusParsed ? DoubleArgumentType.getDouble(ctx, "radius") : -1,
                        xyzOf(ctx), null, null))
                    .then(CommandManager.argument("faction", StringArgumentType.word())
                        .suggests(FACTION_SUGGESTIONS)
                        .executes(ctx -> cmdMassSpawnFormation(ctx.getSource(),
                            IntegerArgumentType.getInteger(ctx, "count"),
                            formation,
                            radiusParsed ? DoubleArgumentType.getDouble(ctx, "radius") : -1,
                            xyzOf(ctx),
                            resolve(StringArgumentType.getString(ctx, "faction")),
                            null))
                        .then(CommandManager.argument("kit", StringArgumentType.word())
                            .suggests(KIT_SUGGESTIONS)
                            .executes(ctx -> cmdMassSpawnFormation(ctx.getSource(),
                                IntegerArgumentType.getInteger(ctx, "count"),
                                formation,
                                radiusParsed ? DoubleArgumentType.getDouble(ctx, "radius") : -1,
                                xyzOf(ctx),
                                resolve(StringArgumentType.getString(ctx, "faction")),
                                resolve(StringArgumentType.getString(ctx, "kit"))))
                        )
                    )
                )
            );
    }

    // =========================================================================
    // /pvpbot faction formation <label> <faction> [radius] [x y z]
    // =========================================================================

    private static void registerFaction(CommandDispatcher<ServerCommandSource> d) {
        for (FormationManager.Formation f : FormationManager.Formation.values()) {
            final FormationManager.Formation formation = f;
            final String label = f.name().toLowerCase();

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

    private static com.mojang.brigadier.builder.RequiredArgumentBuilder<ServerCommandSource, Double>
            buildFactionRadiusCoords(FormationManager.Formation formation) {
        return CommandManager.argument("radius", radiusArg(formation))
            .executes(ctx -> cmdFactionFormation(
                ctx.getSource(),
                StringArgumentType.getString(ctx, "faction"),
                formation, DoubleArgumentType.getDouble(ctx, "radius"), null))
            .then(CommandManager.argument("x", DoubleArgumentType.doubleArg())
                .then(CommandManager.argument("y", DoubleArgumentType.doubleArg())
                    .then(CommandManager.argument("z", DoubleArgumentType.doubleArg())
                        .executes(ctx -> cmdFactionFormation(
                            ctx.getSource(),
                            StringArgumentType.getString(ctx, "faction"),
                            formation,
                            DoubleArgumentType.getDouble(ctx, "radius"),
                            xyzOf(ctx))))));
    }

    private static com.mojang.brigadier.builder.RequiredArgumentBuilder<ServerCommandSource, Double>
            buildFactionCoordsOnly(FormationManager.Formation formation) {
        return CommandManager.argument("x", DoubleArgumentType.doubleArg())
            .then(CommandManager.argument("y", DoubleArgumentType.doubleArg())
                .then(CommandManager.argument("z", DoubleArgumentType.doubleArg())
                    .executes(ctx -> cmdFactionFormation(
                        ctx.getSource(),
                        StringArgumentType.getString(ctx, "faction"),
                        formation, -1, xyzOf(ctx)))));
    }

    // =========================================================================
    // /pvpbot faction group formation <label> <group> [radius] [x y z]
    // =========================================================================

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
        return CommandManager.argument("radius", radiusArg(formation))
            .executes(ctx -> cmdGroupFormation(
                ctx.getSource(),
                StringArgumentType.getString(ctx, "group"),
                formation, DoubleArgumentType.getDouble(ctx, "radius"), null))
            .then(CommandManager.argument("x", DoubleArgumentType.doubleArg())
                .then(CommandManager.argument("y", DoubleArgumentType.doubleArg())
                    .then(CommandManager.argument("z", DoubleArgumentType.doubleArg())
                        .executes(ctx -> cmdGroupFormation(
                            ctx.getSource(),
                            StringArgumentType.getString(ctx, "group"),
                            formation,
                            DoubleArgumentType.getDouble(ctx, "radius"),
                            xyzOf(ctx))))));
    }

    private static com.mojang.brigadier.builder.RequiredArgumentBuilder<ServerCommandSource, Double>
            buildGroupCoordsOnly(FormationManager.Formation formation) {
        return CommandManager.argument("x", DoubleArgumentType.doubleArg())
            .then(CommandManager.argument("y", DoubleArgumentType.doubleArg())
                .then(CommandManager.argument("z", DoubleArgumentType.doubleArg())
                    .executes(ctx -> cmdGroupFormation(
                        ctx.getSource(),
                        StringArgumentType.getString(ctx, "group"),
                        formation, -1, xyzOf(ctx)))));
    }

    // =========================================================================
    // Handler — /pvpbot spawn
    // =========================================================================

    private static int cmdSpawn(ServerCommandSource src, String botName,
                                Vec3d overridePos, String faction, String kit) {
        if (faction != null && !getAllFactions().contains(faction)) {
            src.sendError(Text.literal("§cFaction '" + faction + "' does not exist."));
            return 0;
        }
        if (kit != null && !getAllKits().contains(kit)) {
            src.sendError(Text.literal("§cKit '" + kit + "' does not exist."));
            return 0;
        }

        Set<String> existingBots = new HashSet<>(getAllBots());

        try {
            spawnBot(src.getServer(), botName, src);
        } catch (Exception e) {
            src.sendError(Text.literal("§cFailed to spawn bot: " + e.getMessage()));
            return 0;
        }

        src.sendFeedback(() -> Text.literal(
            "§eSpawning §f" + botName + "§e..."
            + coordHint(overridePos) + factionHint(faction) + kitHint(kit)
        ), false);

        final int POLL = 5;
        final int[] elapsed = {0};
        src.getServer().execute(new Runnable() {
            @Override public void run() {
                ServerPlayerEntity bot = src.getServer().getPlayerManager().getPlayer(botName);
                if (bot != null && !existingBots.contains(botName)) {
                    if (overridePos != null)
                        bot.teleport((ServerWorld) bot.getEntityWorld(),
                            overridePos.x, overridePos.y, overridePos.z,
                            java.util.Set.of(), bot.getYaw(), bot.getPitch(), false);
                    if (faction != null) addBotToFaction(botName, faction);
                    if (kit     != null) applyKit(src.getServer(), botName, kit, src);
                    if (faction != null || kit != null || overridePos != null) {
                        src.sendFeedback(() -> Text.literal(
                            "§a" + botName + " ready."
                            + factionHint(faction) + kitHint(kit)
                        ), true);
                    }
                    return;
                }
                if (elapsed[0] >= 200) {
                    src.sendFeedback(() -> Text.literal(
                        "§e'" + botName + "' did not come online in time."), false);
                    return;
                }
                elapsed[0] += POLL;
                scheduleIn(src.getServer(), POLL, this);
            }
        });
        return 1;
    }

    // =========================================================================
    // Handler — /pvpbot massspawn <formation>
    // =========================================================================

    private static int cmdMassSpawnFormation(ServerCommandSource src, int count,
                                             FormationManager.Formation formation,
                                             double radius, Vec3d overridePos,
                                             String faction, String kit) {
        if (faction != null && !getAllFactions().contains(faction)) {
            src.sendError(Text.literal("§cFaction '" + faction + "' does not exist."));
            return 0;
        }
        if (kit != null && !getAllKits().contains(kit)) {
            src.sendError(Text.literal("§cKit '" + kit + "' does not exist."));
            return 0;
        }

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
               .execute("pvpbot bot-management mass-spawn " + count, src.getServer().getCommandSource());
        } catch (Exception e) {
            src.sendError(Text.literal("§cFailed to trigger mass-spawn: " + e.getMessage()));
            return 0;
        }

        String formLabel  = prettyName(formation);
        String radiusHint = (radius > 0) ? String.format(" §7r=%.1f", radius) : "";
        Vec3d  anchor     = anchorPos;
        String cHint      = overridePos != null
            ? String.format(" §7(centre %.1f %.1f %.1f)", anchor.x, anchor.y, anchor.z) : "";

        src.sendFeedback(() -> Text.literal(
            "§eMass spawning §f" + count + " §ebots into §f" + formLabel
            + " §eformation..." + radiusHint + cHint
            + factionHint(faction) + kitHint(kit)
        ), false);

        placeBotsAsTheySpawn(src.getServer(), src, existingBots, slots,
                             count * 5 + 100, formLabel, faction, kit);
        return 1;
    }

    // =========================================================================
    // Handler — faction rearrange
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
        String ch    = overridePos != null
            ? String.format(" §7(centre %.1f %.1f %.1f)", overridePos.x, overridePos.y, overridePos.z) : "";
        src.sendFeedback(() -> Text.literal(
            "§a" + label + " formation applied: §f" + c
            + " §abots in faction §f'" + faction + "'§a arranged." + ch
        ), true);
        return placed;
    }

    // =========================================================================
    // Handler — group rearrange
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
        String ch    = overridePos != null
            ? String.format(" §7(centre %.1f %.1f %.1f)", overridePos.x, overridePos.y, overridePos.z) : "";
        src.sendFeedback(() -> Text.literal(
            "§a" + label + " formation applied: §f" + c
            + " §abots in group §f'" + group + "'§a arranged." + ch
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
                                              String formLabel,
                                              String faction,
                                              String kit) {
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
                    if (server.getPlayerManager().getPlayer(name) == null) continue;

                    final double[] slot    = slots.get(nextSlot[0]);
                    final String   botName = name;
                    placed.add(name);
                    nextSlot[0]++;
                    scheduleIn(server, SPAWN_SETTLE_TICKS, () -> {
                        FormationManager.placeBot(server, botName, slot);
                        if (faction != null) addBotToFaction(botName, faction);
                        if (kit     != null) applyKit(server, botName, kit, src);
                    });
                }

                if (placed.size() >= slots.size()) {
                    scheduleIn(server, SPAWN_SETTLE_TICKS + 2, () -> {
                        final int n = placed.size();
                        src.sendFeedback(() -> Text.literal(
                            "§a" + formLabel + " complete \u2014 §f" + n + " §abots placed."
                            + factionHint(faction) + kitHint(kit)
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
                            + formLabel.toLowerCase() + " §eonce bots are ready."
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
    // Reflection helpers — spawn
    // =========================================================================

    private static void spawnBot(MinecraftServer server, String botName,
                                  ServerCommandSource src) throws Exception {
        try {
            Class<?> c = Class.forName("org.stepan1411.pvp_bot.bot.BotManager");
            try {
                c.getMethod("spawnBot", MinecraftServer.class, String.class, ServerCommandSource.class)
                 .invoke(null, server, botName, src);
                return;
            } catch (NoSuchMethodException ignored) {}
            try {
                c.getMethod("spawnBot", MinecraftServer.class, String.class)
                 .invoke(null, server, botName);
                return;
            } catch (NoSuchMethodException ignored) {}
        } catch (ClassNotFoundException ignored) {}
        // fallback — delegate to pvpbot spawn command
        server.getCommandManager().getDispatcher()
              .execute("pvpbot spawn " + botName, server.getCommandSource());
    }

    // =========================================================================
    // Reflection helpers — factions
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

    private static void addBotToFaction(String botName, String faction) {
        try {
            Class<?> fc = Class.forName("org.stepan1411.pvp_bot.bot.BotFaction");
            try { fc.getMethod("addMember", String.class, String.class).invoke(null, faction, botName); return; }
            catch (NoSuchMethodException ignored) {}
            try { fc.getMethod("addBot", String.class, String.class).invoke(null, faction, botName); return; }
            catch (NoSuchMethodException ignored) {}
            fc.getMethod("joinFaction", String.class, String.class).invoke(null, botName, faction);
        } catch (Exception e) {
            PvpBotFormationAddon.LOGGER.error(
                "Formation: could not add {} to faction '{}': {}", botName, faction, e.getMessage());
        }
    }

    // =========================================================================
    // Reflection helpers — kits
    // =========================================================================

    @SuppressWarnings("unchecked")
    private static Set<String> getAllKits() {
        // pvp_bot 0.0.15: org.stepan1411.pvp_bot.bot.BotKits.getKitNames() -> Set<String>
        String[] classes = {
            "org.stepan1411.pvp_bot.bot.BotKits",
            "org.stepan1411.pvp_bot.kit.KitManager",
            "org.stepan1411.pvp_bot.kit.KitRegistry",
            "org.stepan1411.pvp_bot.KitManager"
        };
        String[] methods = { "getKitNames", "getAllKits", "getKits" };
        for (String cls : classes) {
            try {
                Class<?> c = Class.forName(cls);
                for (String m : methods) {
                    try {
                        Object r = c.getMethod(m).invoke(null);
                        if (r instanceof Set)  return (Set<String>) r;
                        if (r instanceof List) return new HashSet<>((List<String>) r);
                    } catch (NoSuchMethodException ignored) {}
                }
            } catch (ClassNotFoundException ignored) {}
            catch (Exception e) {
                PvpBotFormationAddon.LOGGER.warn("Formation: kit reflection error ({}): {}", cls, e.getMessage());
            }
        }
        return Collections.emptySet();
    }

    private static void applyKit(MinecraftServer server, String botName, String kit,
                                  ServerCommandSource src) {
        // pvp_bot 0.0.15: org.stepan1411.pvp_bot.bot.BotKits.giveKit(String kitName, ServerPlayerEntity bot)
        try {
            ServerPlayerEntity bot = server.getPlayerManager().getPlayer(botName);
            if (bot != null) {
                Class<?> c = Class.forName("org.stepan1411.pvp_bot.bot.BotKits");
                try {
                    c.getMethod("giveKit", String.class, ServerPlayerEntity.class).invoke(null, kit, bot);
                    return;
                } catch (NoSuchMethodException ignored) {}
            }
        } catch (ClassNotFoundException ignored) {}
        catch (Exception e) {
            PvpBotFormationAddon.LOGGER.warn("Formation: kit apply error (BotKits): {}", e.getMessage());
        }

        // Older/alternate kit APIs, kept as a fallback in case a different pvp_bot build is present.
        String[] classes = {
            "org.stepan1411.pvp_bot.kit.KitManager",
            "org.stepan1411.pvp_bot.kit.KitRegistry",
            "org.stepan1411.pvp_bot.KitManager"
        };
        String[] methods = { "giveKit", "applyKit", "setKit", "assignKit" };
        for (String cls : classes) {
            try {
                Class<?> c = Class.forName(cls);
                for (String m : methods) {
                    try {
                        c.getMethod(m, String.class, String.class).invoke(null, botName, kit);
                        return;
                    } catch (NoSuchMethodException ignored) {}
                }
            } catch (ClassNotFoundException ignored) {}
            catch (Exception e) {
                PvpBotFormationAddon.LOGGER.warn("Formation: kit apply error: {}", e.getMessage());
            }
        }

        // Last resort: the real pvp_bot command for giving a kit to a single player.
        try {
            server.getCommandManager().getDispatcher()
                  .execute("pvpbot kit give-kit " + botName + " " + kit, server.getCommandSource());
        } catch (Exception e) {
            PvpBotFormationAddon.LOGGER.error(
                "Formation: could not apply kit '{}' to {}: {}", kit, botName, e.getMessage());
        }
    }

    // =========================================================================
    // Utility
    // =========================================================================

    private static String resolve(String value) {
        return DEFAULT_KEYWORD.equalsIgnoreCase(value) ? null : value;
    }

    private static Vec3d xyzOf(com.mojang.brigadier.context.CommandContext<ServerCommandSource> ctx) {
        return new Vec3d(
            DoubleArgumentType.getDouble(ctx, "x"),
            DoubleArgumentType.getDouble(ctx, "y"),
            DoubleArgumentType.getDouble(ctx, "z"));
    }

    private static String coordHint(Vec3d pos) {
        return pos != null ? String.format(" §7at %.1f %.1f %.1f", pos.x, pos.y, pos.z) : "";
    }

    private static String factionHint(String faction) {
        return faction != null ? " §7→ faction §f'" + faction + "'" : "";
    }

    private static String kitHint(String kit) {
        return kit != null ? " §7[kit: §f" + kit + "§7]" : "";
    }

    /**
     * Returns the appropriate DoubleArgumentType for the radius parameter.
     * LINE uses -1 to mean "depth line", so we open the lower bound to -1.
     * All other formations accept 1..200.
     */
    private static DoubleArgumentType radiusArg(FormationManager.Formation f) {
        return (f == FormationManager.Formation.LINE)
            ? DoubleArgumentType.doubleArg(-1.0, 200.0)
            : DoubleArgumentType.doubleArg(1.0,  200.0);
    }

    private static boolean hasRadius(FormationManager.Formation f) {
        switch (f) {
            case CIRCLE: case CIRCLE_OUTWARD:
            case TRIANGLE:
            case DIAMOND: case DIAMOND_OUTWARD:
            case HEXAGON: case HEXAGON_OUTWARD:
            case ARC:
            case PINCER:
            case SQUARE:
            case LINE:
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
            case SQUARE:          return "Square";
            case LINE:            return "Line";
            default:              return f.name();
        }
    }
}
