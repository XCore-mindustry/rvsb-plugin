package net.voiddustry.redvsblue.game.stats;

import arc.Events;
import arc.files.Fi;
import arc.struct.ObjectMap;
import arc.struct.ObjectSet;
import arc.struct.Seq;
import arc.util.Log;
import arc.util.Strings;
import arc.util.Timer;
import mindustry.Vars;
import mindustry.game.EventType;
import mindustry.game.Team;
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
import java.security.MessageDigest;

/**
 * Append-only per-map statistics. The server never reads this file: one JSON line is added when a map
 * ends, everything else is meant to be exported and parsed outside the server.
 */
public final class MapStats {

    private static final int SCHEMA = 1;
    private static final long SAMPLE_INTERVAL_MS = 1000L;
    private static final String DIR = "rvsbStats";
    private static final String FILE = "mapStats.jsonl";
    private static final String[] BALANCE_FILES = {
            "rvsbUnits/spawns.hjson",
            "rvsbUnits/spawns.json",
            "rvsbUnits/cruxSpawning.hjson",
            "rvsbUnits/cruxSpawning.json",
            "rvsbUnits/stages.json",
            "rvsbUnits/evolutions.hjson",
            "rvsbUnits/unitWeights.hjson",
            "rvsbUnits/stagePacing.hjson",
            "rvsbUnits/stationBreaking.hjson"
    };

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
            Session s = session;
            if (s == null) s = ensureSession();
            if (s == null || s.finished) return;
            if (player.team() != Team.blue) return;

            UnitStat stat = unitStat(s, unit.type);
            stat.deaths++;
            StageStat stage = stage(s, RedVsBluePlugin.stage);
            stage.blueDeaths++;
            s.maxStage = Math.max(s.maxStage, stageIndex(s, RedVsBluePlugin.stage));
            if (player.uuid() != null) s.uniqueBlue.add(player.uuid());
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
            unitStat(s, type).picks++;
            if (player.uuid() != null) s.uniqueBlue.add(player.uuid());
        } catch (Throwable t) {
            logOnce("pick", t);
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

            unitStat(s, type).kills++;
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
            s.endedAt = System.currentTimeMillis();
            s.playersAtEnd = Groups.player.size();
            if (s.startedAt <= 0) s.startedAt = s.endedAt;
            s.endReason = winner == Team.blue ? "blueWin" : winner == Team.crux ? "cruxWin" : "gameOver";
            s.winner = winner == null ? "none" : winner.name;
            s.maxStage = Math.max(s.maxStage, Math.max(1, RedVsBluePlugin.stage));
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
            old.endReason = "mapChange";
            old.winner = "none";
            finish(old);
        }

        session = null;
        lastSampleMs = now;
        errorLogged = false;
    }

    private static void onDispose() {
        Session s = session;
        if (s != null && !s.finished) {
            s.endedAt = System.currentTimeMillis();
            s.playersAtEnd = Groups.player.size();
            s.endReason = "shutdown";
            s.winner = "none";
            finish(s);
        }
        session = null;
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

            StageStat stage = stage(s, RedVsBluePlugin.stage);
            stage.seconds += seconds;
            s.maxStage = Math.max(s.maxStage, stageIndex(s, RedVsBluePlugin.stage));

            int blue = 0;
            int crux = 0;
            for (Player player : Groups.player) {
                if (player == null) continue;

                if (player.team() == Team.crux) {
                    crux++;
                    continue;
                }
                if (player.team() != Team.blue) continue;

                blue++;
                if (player.uuid() != null) s.uniqueBlue.add(player.uuid());

                PlayerData data = RedVsBluePlugin.players.get(player.uuid());
                Unit unit = data == null ? null : data.getUnit();
                if (unit == null || unit.dead || !unit.isAdded() || unit.type == null) continue;

                UnitStat stat = unitStat(s, unit.type);
                stat.seconds += seconds;
                s.blueSeconds += seconds;
                stage.blueSeconds += seconds;
            }

            s.peakBlue = Math.max(s.peakBlue, blue);
            s.peakCrux = Math.max(s.peakCrux, crux);
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
        // maps that end with a nearly empty server are not representative
        if (s.playersAtEnd < 3) {
            Log.info("[MapStats] skipped @: only @ player(s) at the end", s.info.key, s.playersAtEnd);
            return;
        }
        if (s.endedAt <= 0) s.endedAt = System.currentTimeMillis();
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
        MapInfo info = s.info;
        long startedAt = s.startedAt / 1000L;
        long endedAt = s.endedAt / 1000L;
        long duration = Math.max(0L, (s.endedAt - s.startedAt) / 1000L);

        StringBuilder b = new StringBuilder(1024);
        b.append("{\"v\":").append(SCHEMA);
        b.append(",\"startedAt\":").append(startedAt);
        b.append(",\"endedAt\":").append(endedAt);
        b.append(",\"durationSec\":").append(duration);
        b.append(",\"map\":\"").append(esc(info.key)).append('"');
        b.append(",\"mapName\":\"").append(esc(info.name)).append('"');
        b.append(",\"mapHash\":\"").append(esc(info.hash)).append('"');
        b.append(",\"mapWidth\":").append(info.width);
        b.append(",\"mapHeight\":").append(info.height);
        b.append(",\"balance\":\"").append(esc(info.balance)).append('"');
        b.append(",\"plugin\":\"").append(esc(info.plugin)).append('"');
        b.append(",\"endReason\":\"").append(esc(s.endReason)).append('"');
        b.append(",\"winner\":\"").append(esc(s.winner)).append('"');
        b.append(",\"finalStage\":").append(Math.max(1, s.maxStage));
        b.append(",\"playersAtEnd\":").append(s.playersAtEnd);
        b.append(",\"blue\":{\"unique\":").append(s.uniqueBlue.size)
                .append(",\"peak\":").append(s.peakBlue)
                .append(",\"seconds\":").append(s.blueSeconds)
                .append('}');
        b.append(",\"crux\":{\"peak\":").append(s.peakCrux).append('}');

        b.append(",\"stages\":[");
        int maxStage = Math.min(s.stages.length - 1, Math.max(s.maxStage, RedVsBluePlugin.finalStage));
        for (int i = 1; i <= maxStage; i++) {
            if (i > 1) b.append(',');
            StageStat st = s.stages[i];
            b.append("{\"stage\":").append(i)
                    .append(",\"seconds\":").append(st.seconds)
                    .append(",\"blueSeconds\":").append(st.blueSeconds)
                    .append(",\"blueDeaths\":").append(st.blueDeaths)
                    .append('}');
        }
        b.append(']');

        Seq<UnitStat> units = new Seq<>();
        for (UnitStat stat : s.units.values()) units.add(stat);
        units.sort((a, c) -> a.name.compareToIgnoreCase(c.name));

        b.append(",\"units\":[");
        for (int i = 0; i < units.size; i++) {
            if (i > 0) b.append(',');
            UnitStat u = units.get(i);
            b.append("{\"name\":\"").append(esc(u.name)).append('"')
                    .append(",\"tier\":").append(u.tier)
                    .append(",\"picks\":").append(u.picks)
                    .append(",\"seconds\":").append(u.seconds)
                    .append(",\"deaths\":").append(u.deaths)
                    .append(",\"kills\":").append(u.kills)
                    .append('}');
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

            String hash;
            if (map.file != null && map.file.exists()) {
                hash = sha256(map.file.readBytes());
            } else {
                hash = sha256((key + "|" + map.width + "x" + map.height).getBytes(StandardCharsets.UTF_8));
            }

            return new MapInfo(key, name, hash, balanceHash(), pluginVersion(), map.width, map.height);
        } catch (Throwable t) {
            logOnce("map-info", t);
            return null;
        }
    }

    private static String balanceHash() {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String path : BALANCE_FILES) {
                digest.update(path.getBytes(StandardCharsets.UTF_8));
                Fi file = Vars.dataDirectory.child(path);
                if (file.exists()) digest.update(file.readBytes());
                else digest.update((byte) 0);
            }
            digest.update(pluginVersion().getBytes(StandardCharsets.UTF_8));
            return hex(digest.digest());
        } catch (Throwable t) {
            logOnce("balance-hash", t);
            return "unknown";
        }
    }

    private static String pluginVersion() {
        try {
            return Vars.mods.getMod("red-vs-blue").meta.version;
        } catch (Throwable t) {
            return "unknown";
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return hex(digest.digest(bytes));
        } catch (Throwable t) {
            return "unknown";
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder b = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            b.append(Character.forDigit((value >> 4) & 0xf, 16));
            b.append(Character.forDigit(value & 0xf, 16));
        }
        return b.toString();
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

    private static UnitStat unitStat(Session s, UnitType type) {
        UnitStat stat = s.units.get(type);
        if (stat == null) {
            stat = new UnitStat(type.name, tier(type));
            s.units.put(type, stat);
        }
        return stat;
    }

    private static int tier(UnitType type) {
        Evolution evolution = Evolutions.evolutions.get(type.name);
        return evolution == null ? 0 : evolution.tier;
    }

    private static StageStat stage(Session s, int index) {
        return s.stages[stageIndex(s, index)];
    }

    private static int stageIndex(Session s, int index) {
        return Math.max(1, Math.min(s.stages.length - 1, index));
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
        final String key, name, hash, balance, plugin;
        final int width, height;

        MapInfo(String key, String name, String hash, String balance, String plugin, int width, int height) {
            this.key = key;
            this.name = name;
            this.hash = hash;
            this.balance = balance;
            this.plugin = plugin;
            this.width = width;
            this.height = height;
        }
    }

    private static final class Session {
        final MapInfo info;
        final StageStat[] stages;
        final ObjectMap<UnitType, UnitStat> units = new ObjectMap<>();
        final ObjectSet<String> uniqueBlue = new ObjectSet<>();

        long startedAt, endedAt;
        int peakBlue, peakCrux, blueSeconds;
        int playersAtEnd;
        int maxStage = 1;
        String endReason = "unknown";
        String winner = "none";
        boolean finished;

        Session(MapInfo info) {
            this.info = info;
            this.stages = new StageStat[Math.max(32, RedVsBluePlugin.finalStage + 2)];
            for (int i = 0; i < stages.length; i++) stages[i] = new StageStat();
        }
    }

    private static final class StageStat {
        int seconds;
        int blueSeconds;
        int blueDeaths;
    }

    private static final class UnitStat {
        final String name;
        final int tier;

        int picks;
        int seconds;
        int deaths;
        int kills;

        UnitStat(String name, int tier) {
            this.name = name;
            this.tier = tier;
        }
    }
}
