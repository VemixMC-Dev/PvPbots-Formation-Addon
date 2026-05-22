package org.pvpbotformation;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.List;

public class FormationManager {

    private static final int    DEFAULT_ROW_WIDTH = 5;
    private static final double COL_SPACING       = 1.0;
    private static final double ROW_SPACING       = 1.0;
    private static final double ROW_START_DIST    = 1.0;

    // -------------------------------------------------------------------------
    // Pre-compute grid slots (x, y, z, yaw) for a given anchor & count.
    // Slots are in fill order (left→right, row by row, last row centred).
    // -------------------------------------------------------------------------

    public static List<double[]> computeSlots(Vec3d anchorPos, float anchorYaw, int count) {
        int n        = count;
        int rowWidth = n <= 4 ? 4 : DEFAULT_ROW_WIDTH;
        int numRows  = (int) Math.ceil((double) n / rowWidth);

        double ax  = anchorPos.x, ay = anchorPos.y, az = anchorPos.z;
        double rad = Math.toRadians(anchorYaw);
        double fwdX = -Math.sin(rad), fwdZ =  Math.cos(rad);
        double rgtX =  Math.cos(rad), rgtZ =  Math.sin(rad);

        List<double[]> slots = new ArrayList<>(n);

        for (int row = 0; row < numRows; row++) {
            int startIdx  = row * rowWidth;
            int endIdx    = Math.min(startIdx + rowWidth, n);
            int botsInRow = endIdx - startIdx;
            double colShift = (rowWidth - botsInRow) / 2.0;
            double rowDist  = ROW_START_DIST + row * ROW_SPACING;

            for (int col = 0; col < botsInRow; col++) {
                double adjustedCol = col + colShift;
                double colOffset   = (adjustedCol - (rowWidth - 1) / 2.0) * COL_SPACING;
                double tx = ax + fwdX * rowDist + rgtX * colOffset;
                double tz = az + fwdZ * rowDist + rgtZ * colOffset;
                slots.add(new double[]{tx, ay, tz, anchorYaw});
            }
        }

        return slots;
    }

    // -------------------------------------------------------------------------
    // Teleport a single bot to a pre-computed slot.
    // -------------------------------------------------------------------------

    public static boolean placeBot(MinecraftServer server, String botName, double[] slot) {
        try {
            ServerPlayerEntity bot = server.getPlayerManager().getPlayer(botName);
            if (bot == null) return false;
            bot.networkHandler.requestTeleport(slot[0], slot[1], slot[2], (float) slot[3], 0f);
            return true;
        } catch (Exception e) {
            PvpBotFormationAddon.LOGGER.warn(
                "Formation: failed to teleport '{}': {}", botName, e.getMessage());
            return false;
        }
    }

    // -------------------------------------------------------------------------
    // Convenience wrappers used by faction/group commands (apply all at once).
    // -------------------------------------------------------------------------

    public static int applyGrid(MinecraftServer server,
                                ServerPlayerEntity anchor,
                                List<String> botNames) {
        return applyGridAtPos(
            server,
            new Vec3d(anchor.getX(), anchor.getY(), anchor.getZ()),
            anchor.getYaw(),
            botNames
        );
    }

    public static int applyGridAtPos(MinecraftServer server,
                                     Vec3d anchorPos,
                                     float anchorYaw,
                                     List<String> botNames) {
        List<double[]> slots = computeSlots(anchorPos, anchorYaw, botNames.size());
        int placed = 0;
        for (int i = 0; i < botNames.size(); i++) {
            if (placeBot(server, botNames.get(i), slots.get(i))) placed++;
        }
        return placed;
    }
}
