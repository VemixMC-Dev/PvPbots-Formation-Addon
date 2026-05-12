package org.pvpbotformation;

import com.mojang.brigadier.CommandDispatcher;
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

    private static final SuggestionProvider<ServerCommandSource> FACTION_SUGGESTIONS =
            (ctx, builder) -> CommandSource.suggestMatching(getAllFactions(), builder);

    private static final SuggestionProvider<ServerCommandSource> GROUP_SUGGESTIONS =
            (ctx, builder) -> CommandSource.suggestMatching(GroupsBridge.getAllGroupNames(), builder);

    // ---- Registration --------------------------------------------------------

    public static void register(CommandDispatcher<ServerCommandSource> dispatcher,
                                boolean groupsPresent) {

        dispatcher.register(
            CommandManager.literal("pvpbot")
                .then(CommandManager.literal("massspawn")
                    .then(CommandManager.argument("count", IntegerArgumentType.integer(1, 500))
                        .then(CommandManager.literal("grid")
                            .executes(ctx -> cmdMassSpawnGrid(
                                ctx.getSource(),
                                IntegerArgumentType.getInteger(ctx, "count")
                            ))
                        )
                    )
                )
        );

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
     *
     * Pre-computes all N grid slots immediately, then polls every 5 ticks.
     * Each bot is teleported into its slot the moment it comes online —
     * no waiting for the full batch.
     */
    private static int cmdMassSpawnGrid(ServerCommandSource src, int count) {
        ServerPlayerEntity caller = src.getPlayer();
        if (caller == null) {
            src.sendError(Text.literal("§cThis command must be run by a player."));
            return 0;
        }

        // Snapshot existing bots BEFORE spawning
        Set<String> existingBots = new HashSet<>(getAllBots());

        // Pre-compute ALL grid slots right now (anchor position frozen at command time)
        Vec3d anchorPos = new Vec3d(caller.getX(), caller.getY(), caller.getZ());
        float anchorYaw = caller.getYaw();
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

        src.sendFeedback(() -> Text.literal(
            "§eMass spawning §f" + count + " §ebots into grid formation..."
        ), false);

        // Poll and place each bot as it appears
        // Hard timeout: count * 5 ticks (spawn rate) + 100 tick buffer
        int timeout = count * 5 + 100;
        placeBotsAsTheySpawn(src.getServer(), src, existingBots, slots, timeout);

        return 1;
    }

    /**
     * Polls every 5 ticks. Any new bot that comes online gets assigned the
     * next free slot and teleported immediately. Stops when all slots are
     * filled or the timeout is reached.
     */
    private static void placeBotsAsTheySpawn(MinecraftServer server,
                                              ServerCommandSource src,
                                              Set<String> existingBots,
                                              List<double[]> slots,
                                              int timeoutTicks) {
        final int POLL = 5;
        final int[] elapsed   = {0};
        final int[] nextSlot  = {0};           // index of the next unfilled slot
        final Set<String> placed = new HashSet<>(); // bots already teleported

        server.execute(new Runnable() {
            @Override
            public void run() {
                // Find new bots not yet placed
                for (String name : getAllBots()) {
                    if (existingBots.contains(name)) continue;
                    if (placed.contains(name)) continue;
                    if (server.getPlayerManager().getPlayer(name) == null) continue;
                    if (nextSlot[0] >= slots.size()) break; // all slots filled

                    FormationManager.placeBot(server, name, slots.get(nextSlot[0]));
                    placed.add(name);
                    nextSlot[0]++;
                }

                // Stop if all slots are filled or we've timed out
                if (nextSlot[0] >= slots.size() || elapsed[0] >= timeoutTicks) {
                    final int n = nextSlot[0];
                    if (n > 0) {
                        src.sendFeedback(() -> Text.literal(
                            "§aGrid complete — §f" + n + " §abots placed."
                        ), true);
                    } else {
                        src.sendFeedback(() -> Text.literal(
                            "§eNo bots detected. Use §a/pvpbot faction <faction> formation grid §eonce bots are ready."
                        ), false);
                    }
                    return;
                }

                elapsed[0] += POLL;
                scheduleIn(server, POLL, this);
            }
        });
    }

    /** Schedules {@code task} to run after exactly {@code ticks} server ticks. */
    private static void scheduleIn(MinecraftServer server, int ticks, Runnable task) {
        if (ticks <= 0) { server.execute(task); return; }
        final int[] r = {ticks};
        server.execute(new Runnable() {
            @Override public void run() {
                if (--r[0] > 0) server.execute(this);
                else server.execute(task);
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
