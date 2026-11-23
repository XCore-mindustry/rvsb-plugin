package net.voiddustry.redvsblue.game.stats;

import arc.Events;
import arc.files.Fi;
import arc.struct.IntMap;
import arc.struct.ObjectMap;
import arc.struct.Seq;
import arc.util.Log;
import arc.util.Strings;
import arc.util.Timer;
import mindustry.Vars;
import mindustry.game.EventType;
import mindustry.game.Team;
import mindustry.gen.Bullet;
import mindustry.gen.Entityc;
import mindustry.gen.Groups;
import mindustry.gen.Player;
import mindustry.gen.Unit;
import mindustry.maps.Map;
import mindustry.type.UnitType;
import net.voiddustry.redvsblue.PlayerData;
import net.voiddustry.redvsblue.RedVsBluePlugin;
import net.voiddustry.redvsblue.evolution.Evolution;
import net.voiddustry.redvsblue.evolution.Evolutions;
import net.voiddustry.redvsblue.util.Utils;

import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;

/**
 * Append-only per-map statistics. The server never reads this file: one JSON line is added when a map
 * ends, everything else is meant to be exported and parsed outside the server.
 */
public final class MapStats {

    private static final int SCHEMA = 2;
    private static final long SAMPLE_INTERVAL_MS = 1000L;
    private static final String DIR = "rvsbStats";
    private static final String FILE = "mapStats.jsonl";

    private static final Object FILE_LOCK = new Object();
    private static boolean initialized;
    private static boolean errorLogged;

    private static Session session;
    private static long lastSampleMs;

    private MapStats() {
    }

    public static void init() {
        if (initialized) return;
        initialized = true;
        Events.on(EventType.WorldLoadEvent.class, e -> safe("world-load", MapStats::onWorldLoad));
        Events.on(EventType.DisposeEvent.class, e -> safe("dispose", MapStats::onDispose));
        Events.on(EventType.PlayerLeave.class, e -> safe("player-leave", () -> onPlayerLeave(e.player)));
        Events.on(EventType.UnitDamageEvent.class, e -> safe("unit-damage", () -> attributeDeath(e.unit, e.bullet)));
        Events.on(EventType.UnitBulletDestroyEvent.class, e -> safe("unit-bullet-destroy", () -> attributeDeath(e.unit, e.bullet)));
        Timer.schedule(() -> safe("sample", MapStats::sample), 0f, 1f);
        lastSampleMs = System.currentTimeMillis();
    }

    /** Console command: wipe the file. The current match keeps accumulating and becomes its first line. */
    public static void reset() {
        synchronized (FILE_LOCK) {
            try {
                Fi dir = statsDir();
                if (!dir.exists()) dir.mkdirs();
                Fi file = dir.child(FILE);
                file.writeString("", false, "UTF-8");
                Log.info("[MapStats] cleared @", file.absolutePath());
            } catch (Throwable t) {
                Log.err("[MapStats] failed to reset stats file", t);
            }
        }
    }

    /** Called right before a blue player is converted to crux: the unit they were controlling just died. */
    public static void onBlueUnitDeath(Player player, Unit unit) {
        try {
            if (player == null || unit == null || !RedVsBluePlugin.playing || !Utils.gameRun) return;
            if (player.team() != Team.blue) return;
            // ignore a stale association: after an evolution the player already owns a fresh, living unit
            if (player.unit() != null && player.unit() != unit && !player.unit().dead()) return;
            Session s = session;
            if (s == null) s = ensureSession();
            if (s == null || s.finished) return;

            long now = System.currentTimeMillis();
            int stageIndex = clampStage(s, RedVsBluePlugin.stage);

            BlueStat stat = blueStat(s, unit.type);
            stat.deaths++;
            stage(s, stageIndex).blueDeaths++;
            s.maxStage = Math.max(s.maxStage, stageIndex);

            closeLife(s, player.uuid(), now);
            // a flying unit fires its lethal bullet event before this hook, so an early marker may already exist
            if (!s.pendingDeaths.containsKey(unit.id)) {
                s.pendingDeaths.put(unit.id, new Pending(unit.type.name, stageIndex));
            }
        } catch (Throwable t) {
            logOnce("blue-death", t);
        }
    }

    /** Called when a blue player picks a starting unit or evolves. */
    public static void onPick(Player player, UnitType type) {
        try {
            if (player == null || type == null) return;
            Session s = session;
            if (s == null) s = ensureSession();
            if (s == null || s.finished) return;

            long now = System.currentTimeMillis();
            closeLife(s, player.uuid(), now);

            BlueStat stat = blueStat(s, type);
            stat.picks++;
            stat.picksByStage[clampStage(s, RedVsBluePlugin.stage) - 1]++;
            // a pick made during the start countdown only begins ticking when the game actually starts
            s.lives.put(player.uuid(), new Life(type, Utils.gameRun ? now : 0L));
        } catch (Throwable t) {
            logOnce("pick", t);
        }
    }

    /**
     * Opens a lifetime for every blue player that has none. The random unit handed out before the
     * countdown (and a unit from a mid-game join or redeem) never goes through the starting menu,
     * so without this those picks and lifetimes would be missing.
     */
    public static void ensurePicks() {
        try {
            if (!RedVsBluePlugin.playing || !Utils.gameRun) return;
            for (Player player : Groups.player) {
                if (player == null || player.team() != Team.blue) continue;
                Session s = session;
                if (s == null) s = ensureSession();
                if (s == null || s.finished) return;

                Life life = s.lives.get(player.uuid());
                if (life != null) {
                    if (life.startedAt <= 0) life.startedAt = System.currentTimeMillis();
                    continue;
                }

                PlayerData data = RedVsBluePlugin.players.get(player.uuid());
                Unit unit = data != null ? data.getUnit() : player.unit();
                if (unit == null || unit.dead() || unit.type == null) continue;
                onPick(player, unit.type);
            }
        } catch (Throwable t) {
            logOnce("ensure-picks", t);
        }
    }

    /** Called when a blue player gets kill credit. */
    public static void onKill(Player player) {
        try {
            if (player == null || !RedVsBluePlugin.playing || !Utils.gameRun) return;
            Session s = session;
            if (s == null) s = ensureSession();
            if (s == null || s.finished) return;

            UnitType type = null;
            PlayerData data = RedVsBluePlugin.players.get(player.uuid());
            if (data != null && data.getUnit() != null) type = data.getUnit().type;
            else if (player.unit() != null) type = player.unit().type;
            if (type == null) return;

            blueStat(s, type).kills++;
        } catch (Throwable t) {
            logOnce("kill", t);
        }
    }

    /** Must be called when the game is decided, before player data is cleared. */
    public static void onGameOver(Team winner) {
        try {
            Session s = session;
            if (s == null) s = ensureSession();
            if (s == null || s.finished) return;
            long now = System.currentTimeMillis();
            s.endedAt = now;
            s.playersAtEnd = Groups.player.size();
            if (s.startedAt <= 0) s.startedAt = now;
            s.maxStage = Math.max(s.maxStage, clampStage(s, RedVsBluePlugin.stage));
            closeAllLives(s, now);
            finish(s);
            session = null;
        } catch (Throwable t) {
            logOnce("game-over", t);
        }
    }

    // =====================================================================================================
    // lifecycle / sampling
    // =====================================================================================================

    private static void onWorldLoad() {
        long now = System.currentTimeMillis();
        Session old = session;
        if (old != null && !old.finished) {
            old.endedAt = now;
            old.playersAtEnd = Groups.player.size();
            closeAllLives(old, now);
            finish(old);
        }

        session = null;
        lastSampleMs = now;
        errorLogged = false;
    }

    private static void onDispose() {
        long now = System.currentTimeMillis();
        Session s = session;
        if (s != null && !s.finished) {
            s.endedAt = now;
            s.playersAtEnd = Groups.player.size();
            closeAllLives(s, now);
            finish(s);
        }
        session = null;
    }

    private static void onPlayerLeave(Player player) {
        if (player == null) return;
        Session s = session;
        if (s == null || s.finished) return;
        closeLife(s, player.uuid(), System.currentTimeMillis());
    }

    /**
     * Credits a blue unit's death to the red unit whose bullet killed it. Ground units reach
     * {@link #onBlueUnitDeath} on the same stack as the damage, before the engine fires the bullet event;
     * a flying unit is only converted on the next frame, so the lethal damage event itself may arrive first.
     * Both orders are covered by the pending marker, which is created here when the death hook has not run yet.
     */
    private static void attributeDeath(Unit unit, Bullet bullet) {
        if (unit == null || bullet == null) return;
        Session s = session;
        if (s == null || s.finished) return;

        Pending pending = s.pendingDeaths.get(unit.id);
        if (pending == null) {
            if (!unit.dead() || unit.team != Team.blue || !unit.isPlayer() || unit.type == null) return;
            // an early marker: this damage event is the one that killed a flying unit
            pending = new Pending(unit.type.name, clampStage(s, RedVsBluePlugin.stage));
            s.pendingDeaths.put(unit.id, pending);
        }
        if (pending.resolved) return;
        pending.resolved = true;

        Entityc owner = bullet.shooter != null ? bullet.shooter : bullet.owner;
        if (!(owner instanceof Unit killer) || killer.team != Team.crux || killer.type == null) return;

        creditKill(s, killer.type, pending);
    }

    /**
     * A red ability killed a blue player's unit directly, without a bullet (the boss aura).
     * Called right before {@code victim.kill()}, so the pending marker is already there when the
     * death hook runs and the death is still counted exactly once.
     */
    public static void onRedDirectKill(Unit killer, Unit victim) {
        try {
            if (killer == null || victim == null || killer.type == null || victim.type == null) return;
            if (!RedVsBluePlugin.playing || !Utils.gameRun) return;
            if (killer.team != Team.crux || victim.team != Team.blue) return;
            // only player instances take part in the statistics
            if (!victim.isPlayer() || victim.getPlayer() == null) return;

            Session s = session;
            if (s == null) s = ensureSession();
            if (s == null || s.finished) return;

            Pending pending = s.pendingDeaths.get(victim.id);
            if (pending == null) {
                pending = new Pending(victim.type.name, clampStage(s, RedVsBluePlugin.stage));
                s.pendingDeaths.put(victim.id, pending);
            }
            if (pending.resolved) return;
            pending.resolved = true;

            creditKill(s, killer.type, pending);
        } catch (Throwable t) {
            logOnce("red-direct-kill", t);
        }
    }

    private static void creditKill(Session s, UnitType redType, Pending pending) {
        RedStat stat = redStat(s, redType);
        stat.kills++;
        stat.byStage[pending.stageIndex - 1]++;
        Integer current = stat.victims.get(pending.blueType);
        stat.victims.put(pending.blueType, current == null ? 1 : current + 1);
    }

    private static void sample() {
        try {
            long now = System.currentTimeMillis();
            if (!RedVsBluePlugin.playing || !Utils.gameRun) {
                lastSampleMs = now;
                return;
            }

            Session s = session;
            if (s == null) {
                s = ensureSession();
                if (s == null) return;
            }

            if (s.startedAt <= 0) {
                s.startedAt = now;
                lastSampleMs = now;
            }

            long delta = now - lastSampleMs;
            lastSampleMs = now;
            if (delta <= 0 || delta > 5000L) delta = SAMPLE_INTERVAL_MS;
            int seconds = Math.max(1, (int) Math.round(delta / 1000.0));

            int stageIndex = clampStage(s, RedVsBluePlugin.stage);
            stage(s, stageIndex).seconds += seconds;
            s.maxStage = Math.max(s.maxStage, stageIndex);
        } catch (Throwable t) {
            logOnce("sample", t);
        }
    }

    private static Session ensureSession() {
        Session s = session;
        if (s != null && !s.finished) return s;
        MapInfo info = readMapInfo();
        if (info == null) return null;
        s = new Session(info);
        session = s;
        return s;
    }

    private static void finish(Session s) {
        if (s == null || s.finished) return;
        s.finished = true;
        if (s.startedAt <= 0) return;
        if (s.endedAt <= 0) s.endedAt = System.currentTimeMillis();
        // maps that end with a nearly empty server are not representative
        if (s.playersAtEnd < 3) {
            Log.info("[MapStats] skipped @: only @ player(s) at the end", s.info.key, s.playersAtEnd);
            return;
        }
        try {
            write(s);
        } catch (Throwable t) {
            logOnce("write", t);
        }
    }

    // =====================================================================================================
    // file / JSON
    // =====================================================================================================

    private static void write(Session s) throws Exception {
        String line = toJson(s);
        synchronized (FILE_LOCK) {
            Fi dir = statsDir();
            if (!dir.exists() && !dir.mkdirs()) {
                throw new IllegalStateException("cannot create " + dir.absolutePath());
            }

            Fi file = dir.child(FILE);
            byte[] bytes = (line + "\n").getBytes(StandardCharsets.UTF_8);
            try (RandomAccessFile out = new RandomAccessFile(file.file(), "rw")) {
                // a previous crash may have left a partial line: drop it instead of gluing it to the next record
                repairTail(out);
                out.seek(out.length());
                out.write(bytes);
            }
        }
    }

    /** Drops a trailing partial line left by a crash. Complete records always end with a newline. */
    private static void repairTail(RandomAccessFile out) throws Exception {
        long length = out.length();
        if (length == 0) return;

        out.seek(length - 1);
        if (out.read() == '\n') return;

        long pos = length - 1;
        while (pos > 0) {
            pos--;
            out.seek(pos);
            if (out.read() == '\n') {
                out.setLength(pos + 1);
                return;
            }
        }
        out.setLength(0);
    }

    private static String toJson(Session s) {
        StringBuilder b = new StringBuilder(1024);
        b.append("{\"v\":").append(SCHEMA);
        b.append(",\"map\":\"").append(esc(s.info.key)).append('"');
        b.append(",\"mapName\":\"").append(esc(s.info.name)).append('"');
        b.append(",\"finalStage\":").append(Math.max(1, s.maxStage));

        b.append(",\"stages\":[");
        for (int i = 1; i <= s.stageSlots; i++) {
            if (i > 1) b.append(',');
            StageStat st = s.stages[i];
            b.append("{\"stage\":").append(i)
                    .append(",\"seconds\":").append(st.seconds)
                    .append(",\"blueDeaths\":").append(st.blueDeaths)
                    .append('}');
        }
        b.append(']');

        Seq<BlueStat> blues = new Seq<>();
        for (BlueStat stat : s.blue.values()) blues.add(stat);
        blues.sort((a, c) -> a.name.compareToIgnoreCase(c.name));

        b.append(",\"blue\":[");
        for (int i = 0; i < blues.size; i++) {
            if (i > 0) b.append(',');
            BlueStat u = blues.get(i);
            b.append("{\"name\":\"").append(esc(u.name)).append('"')
                    .append(",\"tier\":").append(u.tier)
                    .append(",\"picks\":").append(u.picks)
                    .append(",\"kills\":").append(u.kills)
                    .append(",\"deaths\":").append(u.deaths)
                    .append(",\"lifeSec\":").append(u.lifeMs / 1000L)
                    .append(",\"lifeCount\":").append(u.lifeCount)
                    .append(",\"picksByStage\":[");
            for (int stage = 0; stage < s.stageSlots; stage++) {
                if (stage > 0) b.append(',');
                b.append(u.picksByStage[stage]);
            }
            b.append("]}");
        }
        b.append(']');

        Seq<RedStat> reds = new Seq<>();
        for (RedStat stat : s.red.values()) reds.add(stat);
        reds.sort((a, c) -> a.name.compareToIgnoreCase(c.name));

        b.append(",\"red\":[");
        for (int i = 0; i < reds.size; i++) {
            if (i > 0) b.append(',');
            RedStat u = reds.get(i);
            b.append("{\"name\":\"").append(esc(u.name)).append('"')
                    .append(",\"kills\":").append(u.kills)
                    .append(",\"byStage\":[");
            for (int stage = 0; stage < s.stageSlots; stage++) {
                if (stage > 0) b.append(',');
                b.append(u.byStage[stage]);
            }
            b.append("],\"victims\":{");
            int written = 0;
            for (ObjectMap.Entry<String, Integer> e : u.victims) {
                if (written++ > 0) b.append(',');
                b.append('"').append(esc(e.key)).append("\":").append(e.value);
            }
            b.append("}}");
        }
        b.append("]}");
        return b.toString();
    }

    private static MapInfo readMapInfo() {
        try {
            Map map = Vars.state.map;
            if (map == null) return null;

            String key = map.file != null ? map.file.nameWithoutExtension() : Strings.stripColors(map.name());
            if (key == null || key.isEmpty()) key = "unknown";
            String name = Strings.stripColors(map.name());
            if (name == null) name = key;

            return new MapInfo(key, name);
        } catch (Throwable t) {
            logOnce("map-info", t);
            return null;
        }
    }

    private static String esc(String value) {
        if (value == null) return "";
        StringBuilder b = new StringBuilder(value.length() + 8);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                default -> {
                    if (c < 0x20) b.append(String.format("\\u%04x", (int) c));
                    else b.append(c);
                }
            }
        }
        return b.toString();
    }

    private static Fi statsDir() {
        return Vars.dataDirectory.child(DIR);
    }

    // =====================================================================================================
    // in-memory current map (cleared on every world load)
    // =====================================================================================================

    private static BlueStat blueStat(Session s, UnitType type) {
        BlueStat stat = s.blue.get(type);
        if (stat == null) {
            stat = new BlueStat(type.name, tier(type), s.stageSlots);
            s.blue.put(type, stat);
        }
        return stat;
    }

    private static RedStat redStat(Session s, UnitType type) {
        RedStat stat = s.red.get(type);
        if (stat == null) {
            stat = new RedStat(type.name, s.stageSlots);
            s.red.put(type, stat);
        }
        return stat;
    }

    private static int tier(UnitType type) {
        Evolution evolution = Evolutions.evolutions.get(type.name);
        return evolution == null ? 0 : evolution.tier;
    }

    private static int clampStage(Session s, int stage) {
        return Math.max(1, Math.min(s.stageSlots, stage));
    }

    private static StageStat stage(Session s, int index) {
        return s.stages[Math.max(1, Math.min(s.stageSlots, index))];
    }

    /** Ends the player's current unit instance and adds its lifetime to the type's average. */
    private static void closeLife(Session s, String uuid, long now) {
        if (uuid == null) return;
        Life life = s.lives.remove(uuid);
        addLife(s, life, now);
    }

    private static void closeAllLives(Session s, long now) {
        for (ObjectMap.Entry<String, Life> e : s.lives) addLife(s, e.value, now);
        s.lives.clear();
    }

    private static void addLife(Session s, Life life, long now) {
        if (life == null || life.type == null || life.startedAt <= 0) return;
        BlueStat stat = blueStat(s, life.type);
        stat.lifeMs += Math.max(0L, now - life.startedAt);
        stat.lifeCount++;
    }

    private static void safe(String where, Runnable action) {
        try {
            action.run();
        } catch (Throwable t) {
            logOnce(where, t);
        }
    }

    private static void logOnce(String where, Throwable t) {
        if (errorLogged) return;
        errorLogged = true;
        Log.err("[MapStats] @ failed; stats skipped for this map", where);
        Log.err(t);
    }

    private static final class MapInfo {
        final String key, name;

        MapInfo(String key, String name) {
            this.key = key;
            this.name = name;
        }
    }

    private static final class Session {
        final MapInfo info;
        final int stageSlots;
        final StageStat[] stages;
        final ObjectMap<UnitType, BlueStat> blue = new ObjectMap<>();
        final ObjectMap<UnitType, RedStat> red = new ObjectMap<>();
        final ObjectMap<String, Life> lives = new ObjectMap<>();
        final IntMap<Pending> pendingDeaths = new IntMap<>();

        long startedAt, endedAt;
        int playersAtEnd;
        int maxStage = 1;
        boolean finished;

        Session(MapInfo info) {
            this.info = info;
            this.stageSlots = Math.max(1, RedVsBluePlugin.finalStage);
            this.stages = new StageStat[stageSlots + 1];
            for (int i = 0; i < stages.length; i++) stages[i] = new StageStat();
        }
    }

    private static final class StageStat {
        int seconds;
        int blueDeaths;
    }

    private static final class BlueStat {
        final String name;
        final int tier;
        final int[] picksByStage;

        int picks;
        int kills;
        int deaths;
        long lifeMs;
        int lifeCount;

        BlueStat(String name, int tier, int stageSlots) {
            this.name = name;
            this.tier = tier;
            this.picksByStage = new int[stageSlots];
        }
    }

    private static final class RedStat {
        final String name;
        final int[] byStage;
        final ObjectMap<String, Integer> victims = new ObjectMap<>();

        int kills;

        RedStat(String name, int stageSlots) {
            this.name = name;
            this.byStage = new int[stageSlots];
        }
    }

    private static final class Life {
        final UnitType type;
        long startedAt;

        Life(UnitType type, long startedAt) {
            this.type = type;
            this.startedAt = startedAt;
        }
    }

    private static final class Pending {
        final String blueType;
        final int stageIndex;
        boolean resolved;

        Pending(String blueType, int stageIndex) {
            this.blueType = blueType;
            this.stageIndex = stageIndex;
        }
    }
}
