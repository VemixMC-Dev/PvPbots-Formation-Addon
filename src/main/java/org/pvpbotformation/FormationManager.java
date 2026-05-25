package org.pvpbotformation;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.List;

/**
 * Computes and applies bot formations.
 *
 * Formation catalogue
 * -------------------
 *  GRID            – Rectangular grid (original)
 *  CIRCLE          – Evenly-spaced ring, adjustable radius, bots face inward
 *  CIRCLE_OUTWARD  – Same ring, bots face outward (defence)
 *  TRIANGLE        – Equilateral triangle perimeter, adjustable radius
 *  DIAMOND         – Diamond perimeter, adjustable radius, bots face inward
 *  DIAMOND_OUTWARD – Diamond defence (face outward)
 *  HEXAGON         – Hexagon perimeter, adjustable radius, bots face inward
 *  HEXAGON_OUTWARD – Hexagon defence (face outward)
 *  ARC             – Front semicircle offence, bots face forward
 *  WEDGE           – Arrow-head / V-shape offence, bots face forward
 *  PINCER          – Two flanking arms that curve inward toward a target
 *
 * Slot layout: double[] { x, y, z, yaw }
 */
public class FormationManager {

    // ── Grid defaults ────────────────────────────────────────────────────────
    private static final int    DEFAULT_ROW_WIDTH = 5;
    private static final double COL_SPACING       = 1.0;
    private static final double ROW_SPACING       = 1.0;
    private static final double ROW_START_DIST    = 1.0;

    // ── Formation shape enum ─────────────────────────────────────────────────
    public enum Formation {
        GRID,
        CIRCLE,
        CIRCLE_OUTWARD,
        TRIANGLE,
        DIAMOND,
        DIAMOND_OUTWARD,
        HEXAGON,
        HEXAGON_OUTWARD,
        ARC,
        WEDGE,
        PINCER
    }

    // =========================================================================
    // Public slot-compute entry point
    // =========================================================================

    /**
     * Compute N placement slots for the requested formation.
     *
     * @param anchorPos     Centre / origin of the formation
     * @param anchorYaw     Minecraft yaw of the commanding player
     * @param count         Number of bots
     * @param formation     Which shape to use
     * @param radius        Radius in blocks (ignored by GRID/WEDGE).
     *                      Pass <= 0 for a sensible auto-scaling default.
     * @param defenceTarget If non-null, OUTWARD bots face away from this point
     *                      instead of away from anchorPos.
     */
    public static List<double[]> computeSlots(Vec3d anchorPos,
                                              float  anchorYaw,
                                              int    count,
                                              Formation formation,
                                              double radius,
                                              Vec3d  defenceTarget) {
        switch (formation) {
            case CIRCLE:
                return computeCircle(anchorPos, anchorYaw, count, resolveRadius(radius, count, 3.0), false, null);
            case CIRCLE_OUTWARD:
                return computeCircle(anchorPos, anchorYaw, count, resolveRadius(radius, count, 3.0), true, defenceTarget);
            case TRIANGLE:
                return computeTriangle(anchorPos, anchorYaw, count, resolveRadius(radius, count, 3.0));
            case DIAMOND:
                return computeDiamond(anchorPos, anchorYaw, count, resolveRadius(radius, count, 3.0), false, null);
            case DIAMOND_OUTWARD:
                return computeDiamond(anchorPos, anchorYaw, count, resolveRadius(radius, count, 3.0), true, defenceTarget);
            case HEXAGON:
                return computeHexagon(anchorPos, anchorYaw, count, resolveRadius(radius, count, 4.0), false, null);
            case HEXAGON_OUTWARD:
                return computeHexagon(anchorPos, anchorYaw, count, resolveRadius(radius, count, 4.0), true, defenceTarget);
            case ARC:
                return computeArc(anchorPos, anchorYaw, count, resolveRadius(radius, count, 3.0));
            case WEDGE:
                return computeWedge(anchorPos, anchorYaw, count);
            case PINCER:
                return computePincer(anchorPos, anchorYaw, count, resolveRadius(radius, count, 3.0));
            case GRID:
            default:
                return computeSlots(anchorPos, anchorYaw, count);
        }
    }

    // ── Original grid (unchanged) ─────────────────────────────────────────────

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

    // =========================================================================
    // Circle
    // =========================================================================

    private static List<double[]> computeCircle(Vec3d anchorPos, float anchorYaw,
                                                int count, double radius,
                                                boolean outward, Vec3d defenceTarget) {
        List<double[]> slots = new ArrayList<>(count);
        double ax = anchorPos.x, ay = anchorPos.y, az = anchorPos.z;
        for (int i = 0; i < count; i++) {
            double angle = Math.toRadians(anchorYaw) + (2 * Math.PI * i / count);
            double bx = ax + radius * (-Math.sin(angle));
            double bz = az + radius *   Math.cos(angle);
            float yaw;
            if (outward) {
                Vec3d pivot = (defenceTarget != null) ? defenceTarget : anchorPos;
                yaw = yawFacingAwayFrom(bx, bz, pivot.x, pivot.z);
            } else {
                yaw = yawFacingToward(bx, bz, ax, az);
            }
            slots.add(new double[]{bx, ay, bz, yaw});
        }
        return slots;
    }

    // =========================================================================
    // Triangle
    // =========================================================================

    private static List<double[]> computeTriangle(Vec3d anchorPos, float anchorYaw,
                                                  int count, double radius) {
        List<double[]> slots = new ArrayList<>(count);
        double ax = anchorPos.x, ay = anchorPos.y, az = anchorPos.z;
        double r = radius;
        int remaining = count;

        while (remaining > 0 && r > 0.5) {
            double baseAngle = Math.toRadians(anchorYaw) - Math.PI / 2;
            List<double[]> ring = new ArrayList<>();
            for (int corner = 0; corner < 3; corner++) {
                double cAngle = baseAngle + (2 * Math.PI / 3) * corner;
                double cx = ax + r * Math.cos(cAngle);
                double cz = az + r * Math.sin(cAngle);
                ring.add(new double[]{cx, anchorPos.y, cz});
                double nAngle = baseAngle + (2 * Math.PI / 3) * ((corner + 1) % 3);
                double nx = ax + r * Math.cos(nAngle);
                double nz = az + r * Math.sin(nAngle);
                double edgeLen = Math.sqrt((nx - cx) * (nx - cx) + (nz - cz) * (nz - cz));
                int edgeBots = Math.max(0, (int)(edgeLen / 1.5) - 1);
                for (int e = 1; e <= edgeBots; e++) {
                    double t = (double) e / (edgeBots + 1);
                    ring.add(new double[]{cx + t*(nx-cx), anchorPos.y, cz + t*(nz-cz)});
                }
            }
            for (double[] p : ring) {
                if (remaining <= 0) break;
                float yaw = yawFacingToward(p[0], p[2], ax, az);
                slots.add(new double[]{p[0], p[1], p[2], yaw});
                remaining--;
            }
            r -= 2.0;
        }
        while (remaining-- > 0) slots.add(new double[]{ax, anchorPos.y, az, anchorYaw});
        return slots;
    }

    // =========================================================================
    // Diamond
    // =========================================================================

    private static List<double[]> computeDiamond(Vec3d anchorPos, float anchorYaw,
                                                 int count, double radius,
                                                 boolean outward, Vec3d defenceTarget) {
        List<double[]> slots = new ArrayList<>(count);
        double ax = anchorPos.x, ay = anchorPos.y, az = anchorPos.z;
        double baseRad = Math.toRadians(anchorYaw);
        double[] cornerAngles = {
            baseRad,
            baseRad + Math.PI / 2,
            baseRad + Math.PI,
            baseRad + 3 * Math.PI / 2
        };
        int perEdge = count / 4;
        int extra   = count % 4;
        for (int edge = 0; edge < 4; edge++) {
            int botsOnEdge = perEdge + (edge < extra ? 1 : 0);
            double aAngle = cornerAngles[edge];
            double bAngle = cornerAngles[(edge + 1) % 4];
            double ax1 = ax + radius * (-Math.sin(aAngle));
            double az1 = az + radius *   Math.cos(aAngle);
            double ax2 = ax + radius * (-Math.sin(bAngle));
            double az2 = az + radius *   Math.cos(bAngle);
            for (int i = 0; i < botsOnEdge; i++) {
                double t  = botsOnEdge == 1 ? 0.5 : (double) i / (botsOnEdge - 1);
                double bx = ax1 + t * (ax2 - ax1);
                double bz = az1 + t * (az2 - az1);
                float yaw;
                if (outward) {
                    Vec3d pivot = (defenceTarget != null) ? defenceTarget : anchorPos;
                    yaw = yawFacingAwayFrom(bx, bz, pivot.x, pivot.z);
                } else {
                    yaw = yawFacingToward(bx, bz, ax, az);
                }
                slots.add(new double[]{bx, ay, bz, yaw});
            }
        }
        return slots;
    }

    // =========================================================================
    // Hexagon
    // =========================================================================

    private static List<double[]> computeHexagon(Vec3d anchorPos, float anchorYaw,
                                                 int count, double radius,
                                                 boolean outward, Vec3d defenceTarget) {
        List<double[]> slots = new ArrayList<>(count);
        double ax = anchorPos.x, ay = anchorPos.y, az = anchorPos.z;
        double baseRad = Math.toRadians(anchorYaw);
        double[] cornerAngles = new double[6];
        for (int i = 0; i < 6; i++) cornerAngles[i] = baseRad + (Math.PI / 3) * i;
        int perEdge = count / 6;
        int extra   = count % 6;
        for (int edge = 0; edge < 6; edge++) {
            int botsOnEdge = perEdge + (edge < extra ? 1 : 0);
            double aAngle = cornerAngles[edge];
            double bAngle = cornerAngles[(edge + 1) % 6];
            double ax1 = ax + radius * (-Math.sin(aAngle));
            double az1 = az + radius *   Math.cos(aAngle);
            double ax2 = ax + radius * (-Math.sin(bAngle));
            double az2 = az + radius *   Math.cos(bAngle);
            for (int i = 0; i < botsOnEdge; i++) {
                double t  = botsOnEdge == 1 ? 0.5 : (double) i / (botsOnEdge - 1);
                double bx = ax1 + t * (ax2 - ax1);
                double bz = az1 + t * (az2 - az1);
                float yaw;
                if (outward) {
                    Vec3d pivot = (defenceTarget != null) ? defenceTarget : anchorPos;
                    yaw = yawFacingAwayFrom(bx, bz, pivot.x, pivot.z);
                } else {
                    yaw = yawFacingToward(bx, bz, ax, az);
                }
                slots.add(new double[]{bx, ay, bz, yaw});
            }
        }
        return slots;
    }

    // =========================================================================
    // Arc  (front 180° offence — bots face same direction as commander)
    // =========================================================================

    private static List<double[]> computeArc(Vec3d anchorPos, float anchorYaw,
                                             int count, double radius) {
        List<double[]> slots = new ArrayList<>(count);
        double ax = anchorPos.x, ay = anchorPos.y, az = anchorPos.z;
        double baseRad = Math.toRadians(anchorYaw);
        for (int i = 0; i < count; i++) {
            double offset = count == 1 ? 0 : -Math.PI / 2 + Math.PI * i / (count - 1);
            double angle  = baseRad + offset;
            double bx = ax + radius * (-Math.sin(angle));
            double bz = az + radius *   Math.cos(angle);
            slots.add(new double[]{bx, ay, bz, anchorYaw});
        }
        return slots;
    }

    // =========================================================================
    // Wedge  (V / arrow-head offence — bots face forward)
    // =========================================================================

    private static List<double[]> computeWedge(Vec3d anchorPos, float anchorYaw, int count) {
        List<double[]> slots = new ArrayList<>(count);
        double ax = anchorPos.x, ay = anchorPos.y, az = anchorPos.z;
        double rad = Math.toRadians(anchorYaw);
        double fwdX = -Math.sin(rad), fwdZ =  Math.cos(rad);
        double rgtX =  Math.cos(rad), rgtZ =  Math.sin(rad);

        // Tip
        slots.add(new double[]{ax + fwdX * 2, ay, az + fwdZ * 2, anchorYaw});

        int side = 1, row = 1;
        for (int i = 1; i < count; i++) {
            double backDist = row * 1.5;
            double sideDist = row * 1.5;
            double tx = ax + fwdX * (2 - backDist) + rgtX * (side * sideDist);
            double tz = az + fwdZ * (2 - backDist) + rgtZ * (side * sideDist);
            slots.add(new double[]{tx, ay, tz, anchorYaw});
            if (side == 1) { side = -1; } else { side = 1; row++; }
        }
        return slots;
    }

    // =========================================================================
    // Pincer  (two flanking arms curving around a target)
    // =========================================================================

    private static List<double[]> computePincer(Vec3d anchorPos, float anchorYaw,
                                                int count, double radius) {
        List<double[]> slots = new ArrayList<>(count);
        double ax = anchorPos.x, ay = anchorPos.y, az = anchorPos.z;
        double baseRad = Math.toRadians(anchorYaw);

        // Focal point bots will face toward
        double targetX = ax + radius * (-Math.sin(baseRad));
        double targetZ = az + radius *   Math.cos(baseRad);

        int leftCount  = count / 2;
        int rightCount = count - leftCount;

        for (int i = 0; i < leftCount; i++) {
            double t     = leftCount == 1 ? 0.5 : (double) i / (leftCount - 1);
            double angle = baseRad + Math.toRadians(-30 - 120 * t);
            double bx = ax + radius * (-Math.sin(angle));
            double bz = az + radius *   Math.cos(angle);
            slots.add(new double[]{bx, ay, bz, yawFacingToward(bx, bz, targetX, targetZ)});
        }
        for (int i = 0; i < rightCount; i++) {
            double t     = rightCount == 1 ? 0.5 : (double) i / (rightCount - 1);
            double angle = baseRad + Math.toRadians(30 + 120 * t);
            double bx = ax + radius * (-Math.sin(angle));
            double bz = az + radius *   Math.cos(angle);
            slots.add(new double[]{bx, ay, bz, yawFacingToward(bx, bz, targetX, targetZ)});
        }
        return slots;
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private static double resolveRadius(double requested, int count, double minRadius) {
        if (requested > 0) return requested;
        return Math.max(minRadius, count * 2.0 / (2 * Math.PI));
    }

    /**
     * Minecraft yaw to face TOWARD (tx,tz).
     * Minecraft yaw convention: 0=south(+Z), 90=west(-X), -90=east(+X), ±180=north(-Z).
     */
    static float yawFacingToward(double bx, double bz, double tx, double tz) {
        double dx = tx - bx, dz = tz - bz;
        return (float) Math.toDegrees(Math.atan2(-dx, dz));
    }

    static float yawFacingAwayFrom(double bx, double bz, double px, double pz) {
        return yawFacingToward(bx, bz, 2 * bx - px, 2 * bz - pz);
    }

    // =========================================================================
    // Teleport
    // =========================================================================

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

    // =========================================================================
    // Convenience wrappers (faction / group commands — original API intact)
    // =========================================================================

    public static int applyGrid(MinecraftServer server,
                                ServerPlayerEntity anchor,
                                List<String> botNames) {
        return applyFormationAtPos(server,
            new Vec3d(anchor.getX(), anchor.getY(), anchor.getZ()),
            anchor.getYaw(), botNames, Formation.GRID, -1, null);
    }

    public static int applyGridAtPos(MinecraftServer server,
                                     Vec3d anchorPos,
                                     float anchorYaw,
                                     List<String> botNames) {
        return applyFormationAtPos(server, anchorPos, anchorYaw, botNames, Formation.GRID, -1, null);
    }

    public static int applyFormation(MinecraftServer server,
                                     ServerPlayerEntity anchor,
                                     List<String> botNames,
                                     Formation formation,
                                     double radius,
                                     Vec3d defenceTarget) {
        return applyFormationAtPos(server,
            new Vec3d(anchor.getX(), anchor.getY(), anchor.getZ()),
            anchor.getYaw(), botNames, formation, radius, defenceTarget);
    }

    public static int applyFormationAtPos(MinecraftServer server,
                                          Vec3d anchorPos,
                                          float anchorYaw,
                                          List<String> botNames,
                                          Formation formation,
                                          double radius,
                                          Vec3d defenceTarget) {
        List<double[]> slots = computeSlots(anchorPos, anchorYaw,
            botNames.size(), formation, radius, defenceTarget);
        int placed = 0;
        for (int i = 0; i < botNames.size(); i++) {
            if (placeBot(server, botNames.get(i), slots.get(i))) placed++;
        }
        return placed;
    }
}
