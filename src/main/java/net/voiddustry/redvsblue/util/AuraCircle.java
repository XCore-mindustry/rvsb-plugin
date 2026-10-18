package net.voiddustry.redvsblue.util;

import arc.graphics.Color;
import arc.math.geom.Vec2;
import arc.util.Timer;
import mindustry.game.MapObjectives.ShapeMarker;
import mindustry.gen.Call;
import mindustry.gen.Groups;
import mindustry.gen.Player;
import mindustry.graphics.Layer;
import mindustry.logic.LMarkerControl;
import mindustry.net.NetConnection;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * A circle on the ground drawn by the clients themselves (two world markers: a translucent disc and a rim).
 * It is created once and stays on screen permanently at almost no network cost.
 * <ul>
 *     <li>Moving circles ({@link #update}) send a position update to a player only when the circle is in their view
 *     and has moved since the last update they got, and resend the whole marker to viewers every 2 seconds
 *     (marker packets are unreliable).</li>
 *     <li>Fixed circles ({@link #fixed}) are sent once and resent to everyone 3 more times in the first 6 seconds,
 *     nothing after that. Players joining later get them with the world data.</li>
 * </ul>
 */
public final class AuraCircle {
    private static final float MOVE_EPSILON = 1f;
    private static final int REFRESH_TICKS = 20;
    private static int nextId = 0x52564200;

    private final int discId = nextId++, rimId = nextId++;
    private final ShapeMarker disc = new ShapeMarker(), rim = new ShapeMarker();
    private final float radius;
    private final Map<NetConnection, Vec2> sent = new HashMap<>();
    private boolean shown;
    private int ticks;

    public AuraCircle(float radius, Color color, float discAlpha, float rimAlpha, float stroke) {
        this.radius = radius;

        disc.radius = radius;
        disc.sides = Math.max(32, (int) (radius / 2f));
        disc.fill = true;
        disc.color = color.cpy().a(discAlpha);
        disc.control(LMarkerControl.drawLayer, Layer.groundUnit - 1f, Double.NaN, Double.NaN);

        rim.radius = radius - 1f;
        rim.sides = disc.sides;
        rim.stroke = stroke;
        rim.outline = false;
        rim.color = color.cpy().a(rimAlpha);
        rim.light = 0; // also glows on dark maps
        rim.control(LMarkerControl.drawLayer, Layer.groundUnit - 0.9f, Double.NaN, Double.NaN);
    }

    /** Strong circle for auras that hurt (kill streak). */
    public AuraCircle(float radius, Color color) {
        this(radius, color, 0.18f, 0.85f, 2f);
    }

    /** Clean, faint radius circle around a station; shown immediately. Call {@link #hide()} when the station goes. */
    public static AuraCircle fixed(float x, float y, float radius, Color color) {
        AuraCircle circle = new AuraCircle(radius, color, 0.06f, 0.4f, 1.5f);
        circle.disc.pos.set(x, y);
        circle.rim.pos.set(x, y);
        circle.create();
        for (float delay : new float[]{1f, 3f, 6f}) {
            Timer.schedule(() -> {
                if (circle.shown) circle.create();
            }, delay);
        }
        return circle;
    }

    private void create() {
        shown = true;
        Call.createMarker(discId, disc); // broadcast + server copy, so players joining later get it with the world
        Call.createMarker(rimId, rim);
    }

    /** Moving circle: call every timer tick while it should be visible. */
    public void update(float x, float y) {
        disc.pos.set(x, y);
        rim.pos.set(x, y);

        if (!shown) {
            sent.clear();
            ticks = 0;
            create();
            for (Player p : Groups.player) if (p.con != null) sent.put(p.con, new Vec2(x, y));
            return;
        }

        boolean refresh = ++ticks % REFRESH_TICKS == 0;
        for (Player p : Groups.player) {
            NetConnection con = p.con;
            if (con == null || !con.isConnected() || !StationFx.sees(con, x, y, radius)) continue;

            Vec2 last = sent.get(con);
            if (refresh) {
                Call.createMarker(con, discId, disc);
                Call.createMarker(con, rimId, rim);
            } else if (last == null || !last.within(x, y, MOVE_EPSILON)) {
                Call.updateMarker(con, discId, LMarkerControl.pos, x / 8f, y / 8f, 0);
                Call.updateMarker(con, rimId, LMarkerControl.pos, x / 8f, y / 8f, 0);
            } else {
                continue;
            }
            if (last == null) sent.put(con, new Vec2(x, y));
            else last.set(x, y);
        }

        if (refresh) {
            Iterator<NetConnection> it = sent.keySet().iterator();
            while (it.hasNext()) if (!it.next().isConnected()) it.remove();
        }
    }

    /** Removes the circle. Safe to call repeatedly. */
    public void hide() {
        if (!shown) return;
        shown = false;
        remove();
        // unreliable packet: repeat it a couple of times unless the circle was shown again meanwhile
        for (float delay : new float[]{1f, 3f}) {
            Timer.schedule(() -> {
                if (!shown) remove();
            }, delay);
        }
    }

    private void remove() {
        Call.removeMarker(discId);
        Call.removeMarker(rimId);
    }
}
