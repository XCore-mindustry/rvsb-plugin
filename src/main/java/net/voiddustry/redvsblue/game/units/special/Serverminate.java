package net.voiddustry.redvsblue.game.units.special;

import arc.Events;
import arc.math.Mathf;
import arc.struct.IntMap;
import arc.util.Time;
import mindustry.Vars;
import mindustry.ai.types.MissileAI;
import mindustry.game.EventType;
import mindustry.game.EventType.Trigger;
import mindustry.gen.ClientSnapshotCallPacket;
import mindustry.gen.Groups;
import mindustry.gen.Player;
import mindustry.gen.Unit;
import mindustry.net.Administration.Config;
import mindustry.net.NetConnection;
import mindustry.type.StatusEffect;
import mindustry.type.UnitType;

//slop code for fixing bad mindustry anticheat code making terminate unplayable
public final class Serverminate {
    public static final String UNIT = "dp-serverminate-unit";
    public static final String TRIGGER = "dp-serverminate-dash-trigger";
    public static final String DASH = "dp-serverminate-dash";
    public static final String NO_DRAW_HITBOX = "dp-no-draw-hitbox";
    public static final String NO_REGISTER_KILLS = "dp-no-register-kills";

    /** dash buff length, ticks (same as Terminate) */
    static final float DASH_TICKS = 18f;
    /** time after the buff during which the client may still finish its dash, ticks (0.15 s) */
    static final float GRACE = 9f;
    /** catch-up speed, in dash speeds */
    static final float CATCH_UP = 2f;
    /** a queued dash that couldn't be applied within this many ticks is dropped */
    static final float QUEUE_LIFE = 30f;
    /** AI units have no client to sync with, so they get the buff at once */
    static final boolean AI_IMMEDIATE = true;

    static final class State {
        /** Time.time of queued dashes that are still waiting for a snapshot (at most 3 in flight) */
        final float[] queue = new float[4];
        int queued;
        /** extra movement left for the current dash window, world units */
        float budget;
        /** speed without the dash buff and dash speed, world units per tick, from the last applied dash */
        float baseSpeed, dashSpeed;
        /** Time.time until which the window (buff + grace) lasts */
        float windowEnd = -1f;
    }

    private static final IntMap<State> states = new IntMap<>();

    private static UnitType unitType, triggerType;
    private static StatusEffect dash, noDraw, noRegister;

    private Serverminate() {}

    public static void init() {
        Vars.net.handleServer(ClientSnapshotCallPacket.class, Serverminate::snapshot);
        Events.run(Trigger.afterGameUpdate, Serverminate::update);
        Events.on(EventType.WorldLoadEvent.class, e -> {
            states.clear();
            resolve();
        });
    }

    /** Looked up every frame: data-asset content is re-created on every map load, cached objects would go stale. */
    private static boolean resolve() {
        unitType = Vars.content.unit(UNIT);
        triggerType = Vars.content.unit(TRIGGER);
        dash = Vars.content.statusEffect(DASH);
        noDraw = Vars.content.statusEffect(NO_DRAW_HITBOX);
        noRegister = Vars.content.statusEffect(NO_REGISTER_KILLS);
        return unitType != null && triggerType != null && dash != null;
    }

    /** @return the hide-hitbox status, or null if the assets aren't loaded */
    public static StatusEffect noDrawHitbox() {
        return noDraw = Vars.content.statusEffect(NO_DRAW_HITBOX);
    }

    /** Runs after all entities updated, right before NetServer sends snapshots in the same frame. */
    private static void update() {
        if (!Vars.state.isGame()) return;
        if (!resolve()) return;

        // the next entity snapshot goes out at the end of this frame
        boolean snapshotNow = Time.timeSinceMillis(Vars.netServer.snapshotSyncTime) >= Config.snapshotInterval.num() - 1;

        Groups.unit.each(u -> {
            if (u.type == triggerType) {
                trigger(u);
            } else if (u.type == unitType) {
                State st = states.get(u.id);
                if (st != null) tick(u, st, snapshotNow);
            }
        });

        // drop states of units that are gone
        if (states.size > 0) {
            IntMap.Keys keys = states.keys();
            while (keys.hasNext) {
                int id = keys.next();
                Unit u = Groups.unit.getByID(id);
                if (u == null || u.dead || u.type != unitType) keys.remove();
            }
        }
    }

    private static void trigger(Unit missile) {
        if (!(missile.controller() instanceof MissileAI ai)) return;
        Unit shooter = ai.shooter;
        if (shooter == null) return; // already handled
        ai.shooter = null;

        if (noRegister != null) missile.apply(noRegister, 60f);
        if (noDraw != null) missile.apply(noDraw, 60f);

        if (shooter.dead || !shooter.isAdded() || shooter.type != unitType) return;
        State st = states.get(shooter.id);
        if (st == null) states.put(shooter.id, st = new State());
        if (st.queued < st.queue.length) st.queue[st.queued++] = Time.time;
    }

    private static void tick(Unit u, State st, boolean snapshotNow) {
        // forget queued dashes that waited too long
        while (st.queued > 0 && Time.time - st.queue[0] > QUEUE_LIFE) pop(st);

        if (st.queued > 0 && !(u.hasEffect(dash) && u.getDuration(dash) > 1f)) {
            boolean player = u.isPlayer();
            if (snapshotNow || (AI_IMMEDIATE && !player)) {
                pop(st);
                applyDash(u, st, player);
            }
        }

        if (Time.time > st.windowEnd) st.budget = 0f;
    }

    private static void pop(State st) {
        System.arraycopy(st.queue, 1, st.queue, 0, st.queue.length - 1);
        st.queued--;
    }

    private static void applyDash(Unit u, State st, boolean player) {
        float mult = dash.speedMultiplier;
        // speed without the dash buff (strain, overclock etc. included); strafe penalty ignored (it only lowers speed)
        float base = u.type.speed * u.speedMultiplier / (u.hasEffect(dash) ? mult : 1f);
        st.baseSpeed = base;
        st.dashSpeed = base * mult;
        // a fresh window: leftovers of an earlier dash don't carry over
        if (Time.time > st.windowEnd) st.budget = 0f;
        st.budget += st.dashSpeed * DASH_TICKS;
        st.windowEnd = Time.time + DASH_TICKS + GRACE;

        u.apply(dash, DASH_TICKS);
    }

    /** Client position snapshot. Must always end in packet.handleServer(con). */
    private static void snapshot(NetConnection con, ClientSnapshotCallPacket p) {
        Player player = con.player;
        Unit unit = player == null || player.dead() ? null : player.unit();
        State st = unit == null || unit.type != unitType || unit.id != p.unitID || p.dead
            || p.snapshotID < con.lastReceivedClientSnapshot ? null : states.get(unit.id);
        boolean window = st != null && Time.time <= st.windowEnd;

        if (window && Vars.netServer.admins.isStrict() && !Float.isNaN(p.x) && !Float.isNaN(p.y)
            && !Float.isInfinite(p.x) && !Float.isInfinite(p.y)) {
            long last = con.lastReceivedClientTime == 0 ? Time.millis() - 16 : con.lastReceivedClientTime;
            float ticks = Math.min(Time.timeSinceMillis(last), 1500) / 1000f * 60f;
            float vanillaMax = ticks * unit.speed() * 1.1f;
            float baseMax = ticks * st.baseSpeed * 1.1f;
            float dst = Mathf.dst(unit.x, unit.y, p.x, p.y);

            float allowed = Math.max(vanillaMax, Math.min(ticks * CATCH_UP * st.dashSpeed, baseMax + st.budget));
            float move = Math.min(dst, allowed);
            st.budget = Math.max(st.budget - Math.max(move - baseMax, 0f), 0f);

            // move the part vanilla would refuse ourselves; vanilla then moves the rest (up to its own limit)
            float pre = move - vanillaMax;
            if (pre > 0f && dst > 0.001f) {
                float dx = (p.x - unit.x) / dst * pre, dy = (p.y - unit.y) / dst * pre;
                if (unit.isFlying()) unit.trns(dx, dy);
                else unit.move(dx, dy);
            }
        }

        p.handleServer(con);
    }
}
