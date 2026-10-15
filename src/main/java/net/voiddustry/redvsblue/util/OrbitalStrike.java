package net.voiddustry.redvsblue.util;

import arc.util.Time;
import mindustry.Vars;
import mindustry.content.StatusEffects;
import mindustry.game.Team;
import mindustry.gen.BlockUnitc;
import mindustry.gen.Call;
import mindustry.gen.Groups;
import mindustry.gen.Player;
import mindustry.gen.Unit;
import mindustry.type.UnitType;

import static mindustry.Vars.state;

/**
 * Smite execution. The visuals and sounds are the death effect of the data unit {@code dp-orbital-strike}
 * (config/assets/content/units/orbital-strike.json): it is spawned on the victim, dies after 0.5 s and its
 * death effect plays the whole 10 s orbital cannon sequence. Meanwhile the victim is frozen in place
 * (unmoving, and teleported back 10 times a second if it moved at all); after 10 s its unit dies instantly.
 */
public class OrbitalStrike {
    /** ticks from the vote passing to the kill; must equal lifetime + impact delay in orbital-strike.json */
    private static final float DURATION = 600f;
    private static final float LOCK_INTERVAL = 6f;
    private static final String EFFECT_UNIT = "dp-orbital-strike";

    private static String uuid;
    private static float lockX, lockY, time = -1f, lockTimer;

    public static boolean active() {
        return time >= 0f;
    }

    public static void start(Player target) {
        cancel();
        uuid = target.uuid();
        Unit unit = target.unit();
        lockX = unit != null && !unit.dead ? unit.x : target.x;
        lockY = unit != null && !unit.dead ? unit.y : target.y;
        time = 0f;
        lockTimer = 0f;

        UnitType effect = Vars.content.unit(EFFECT_UNIT);
        if (effect != null) {
            effect.spawn(Team.derelict, lockX, lockY);
        }
        lock(target);
    }

    public static void cancel() {
        if (time >= 0f && uuid != null) {
            Player target = find();
            if (target != null && target.unit() != null) target.unit().unapply(StatusEffects.unmoving);
        }
        time = -1f;
        uuid = null;
    }

    /** Called every frame. */
    public static void update() {
        if (time < 0f) return;
        if (!state.isPlaying()) {
            if (!state.isPaused()) cancel();
            return;
        }
        Player target = find();
        if (target == null || target.team() != Team.blue) {
            Utils.sendBundled("f.vote.escaped", new Object[0]);
            cancel();
            return;
        }

        time += Time.delta;
        lockTimer += Time.delta;
        if (lockTimer >= LOCK_INTERVAL) {
            lockTimer %= LOCK_INTERVAL;
            lock(target);
        }

        if (time >= DURATION) {
            Unit unit = target.unit();
            if (unit == null || unit.dead || !unit.isValid()) {
                Utils.sendBundled("f.vote.escaped", new Object[0]);
            } else {
                // no item explosion / crash damage: only the target dies
                unit.clearItem();
                unit.spawnedByCore = true;
                unit.kill();
            }
            cancel();
        }
    }

    private static void lock(Player target) {
        Unit unit = target.unit();
        if (unit == null || unit.dead || unit instanceof BlockUnitc) return;
        unit.apply(StatusEffects.unmoving, DURATION - time + LOCK_INTERVAL * 2f);
        if (Math.abs(unit.x - lockX) > 0.01f || Math.abs(unit.y - lockY) > 0.01f) {
            unit.set(lockX, lockY);
            unit.vel.setZero();
            unit.snapInterpolation();
            if (target.con != null) Call.setPosition(target.con, lockX, lockY);
        }
    }

    private static Player find() {
        return uuid == null ? null : Groups.player.find(p -> p.uuid().equals(uuid));
    }
}
