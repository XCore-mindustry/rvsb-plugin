package net.voiddustry.redvsblue.game.stations;

import arc.files.Fi;
import arc.struct.ObjectMap;
import arc.util.Log;
import arc.util.serialization.Jval;
import mindustry.Vars;
import mindustry.content.Blocks;
import mindustry.world.Block;

/**
 * Deconstruction time per station, loaded from config/rvsbUnits/stationBreaking.hjson.
 * Values are seconds; the owner has to keep the deconstruction plan and stay in range.
 */
public final class StationBreaking {

    private static final String CONFIG = "rvsbUnits/stationBreaking";
    private static final float DEFAULT_SECONDS = 10f;
    private static final float MIN_SECONDS = 0.2f;

    private static float defaultSeconds = DEFAULT_SECONDS;
    private static final ObjectMap<String, Float> seconds = new ObjectMap<>();

    private StationBreaking() {
    }

    public static void load() {
        defaultSeconds = DEFAULT_SECONDS;
        seconds.clear();

        Fi file = configFile();
        if (!file.exists()) {
            Log.warn("[StationBreaking] @ not found, using defaults", file.absolutePath());
            return;
        }

        try {
            Jval root = Jval.read(file.readString());
            Jval value = root.has("stationBreaking") ? root.get("stationBreaking") : root;

            defaultSeconds = Math.max(MIN_SECONDS, value.getFloat("defaultSeconds", DEFAULT_SECONDS));

            if (value.has("stations")) {
                for (var entry : value.get("stations").asObject()) {
                    float time = Math.max(MIN_SECONDS, entry.value.asFloat());
                    seconds.put(entry.key.trim(), time);
                }
            }

            Log.info("Loaded station breaking times from @ (default=@s, @ overrides)",
                    file.path(), defaultSeconds, seconds.size);
        } catch (Exception e) {
            Log.err("[StationBreaking] failed to read @, using defaults", file.path());
            Log.err(e);
        }
    }

    /** Seconds needed to deconstruct this station. */
    public static float seconds(Block block) {
        if (block == null) return defaultSeconds;
        Float value = seconds.get(key(block));
        if (value == null) value = seconds.get(block.name);
        return value == null ? defaultSeconds : value;
    }

    /** Progress step for one server tick (the updater runs every 0.2 s). */
    public static float step(Block block) {
        return 0.2f / seconds(block);
    }

    private static String key(Block block) {
        if (block == Blocks.phaseWall) return "suppressorTower";
        if (block == Blocks.pulverizer) return "miner";
        if (block == Blocks.mender) return "repairPoint";
        if (block == Vars.content.block("dp-workbench-station")) return "armorWorkbench";
        if (block == Vars.content.block("dp-laboratory-station")) return "laboratory";
        if (block == Blocks.slagIncinerator) return "recycler";
        if (block == Blocks.beamNode) return "booster";
        return block.name;
    }

    private static Fi configFile() {
        Fi hjson = Vars.dataDirectory.child(CONFIG + ".hjson");
        Fi json = Vars.dataDirectory.child(CONFIG + ".json");
        return !hjson.exists() && json.exists() ? json : hjson;
    }
}
