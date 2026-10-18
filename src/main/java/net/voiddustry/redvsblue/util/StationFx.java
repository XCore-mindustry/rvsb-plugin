package net.voiddustry.redvsblue.util;

import arc.graphics.Color;
import arc.math.Mathf;
import arc.math.geom.Vec2;
import mindustry.content.Fx;
import mindustry.entities.Effect;
import mindustry.gen.Call;
import mindustry.gen.Groups;
import mindustry.gen.Player;
import mindustry.gen.Unit;
import mindustry.graphics.Pal;
import mindustry.net.NetConnection;
import mindustry.world.Block;

/**
 * Station / aura visuals built from a few vanilla effects that draw a whole shape per packet (rings, hexagons,
 * beams, seeking particles) instead of rings made of 19-38 separate effects.
 * Every packet only goes to players whose camera is near the effect (the client reports its view rectangle in each
 * snapshot), so players on the other side of the map get nothing.
 */
public final class StationFx {
    public static final Color
            repair = Pal.heal,
            boost = Color.valueOf("ffa53f"),
            recycle = Color.valueOf("b8b8b8"),
            armor = Color.valueOf("5f7bff"),
            suppress = Pal.sap,
            lab = Color.valueOf("b45cff"),
            rot = Color.valueOf("d1263a");

    private StationFx() {
    }

    /** Sends an effect to the players that can see (x, y) give or take {@code radius}. */
    public static void at(Effect effect, float x, float y, float rotation, Color color, Object data, float radius) {
        for (Player p : Groups.player) {
            NetConnection con = p.con;
            if (con == null || !con.isConnected() || !sees(con, x, y, radius)) continue;
            if (data == null) Call.effect(con, effect, x, y, rotation, color);
            else Call.effect(con, effect, x, y, rotation, color, data);
        }
    }

    /** Culled version of Call.label. */
    public static void label(String text, float duration, float x, float y) {
        for (Player p : Groups.player) {
            NetConnection con = p.con;
            if (con == null || !con.isConnected() || !sees(con, x, y, 16f)) continue;
            Call.label(con, text, duration, x, y);
        }
    }

    public static boolean sees(NetConnection con, float x, float y, float radius) {
        // no snapshot yet: don't know where the camera is, send it
        if (con.viewWidth <= 0f || con.viewHeight <= 0f) return true;
        float margin = radius + 48f;
        return Math.abs(x - con.viewX) <= con.viewWidth / 2f + margin
                && Math.abs(y - con.viewY) <= con.viewHeight / 2f + margin;
    }

    // ------------------------------------------------------------------------------------------------------------
    // shapes

    /** Thin ring growing to exactly {@code radius}. */
    public static void ring(float x, float y, float radius, Color color) {
        at(Fx.overdriveWave, x, y, radius, color, null, radius);
    }

    /** Thicker, faster ring growing to exactly {@code radius}. */
    public static void pulse(float x, float y, float radius, Color color) {
        at(Fx.healWaveMend, x, y, radius, color, null, radius);
    }

    /** Hexagon outline that fades out (vanilla shield break). */
    public static void hexagon(float x, float y, float radius, Color color) {
        at(Fx.shieldBreak, x, y, radius, color, null, radius);
    }

    /** Ring plus four spikes reaching about 1.5x {@code size}. */
    public static void star(float x, float y, float size, Color color) {
        at(Fx.dynamicSpikes, x, y, size, color, null, size * 1.5f);
    }

    /** Short beam that follows the unit while it fades. */
    public static void beam(float x, float y, Unit target, Color color) {
        at(Fx.pointBeam, x, y, 0f, color, target, x == target.x && y == target.y ? 8f : Mathf.dst(x, y, target.x, target.y));
    }

    /** Lightning arc from (x, y) to the target position. */
    public static void zap(float x, float y, float tx, float ty, Color color) {
        at(Fx.chainLightning, x, y, 0f, color, new Vec2(tx, ty), Mathf.dst(x, y, tx, ty));
    }

    /** A particle that drifts along a curve from (x, y) to the target over 2-3 s. */
    public static void seek(float x, float y, float tx, float ty, Color color) {
        at(Fx.regenSuppressSeek, x, y, 0f, color, new Vec2(tx, ty), Mathf.dst(x, y, tx, ty));
    }

    /** Briefly tints the block sprite. */
    public static void flash(Block block, float x, float y, Color color) {
        if (block == null) return;
        at(Fx.healBlockFull, x, y, 0f, color, block, block.size * 8f);
    }

    /** Small burst of sparks. */
    public static void sparks(float x, float y, Color color) {
        at(Fx.circleColorSpark, x, y, 0f, color, null, 32f);
    }
}
