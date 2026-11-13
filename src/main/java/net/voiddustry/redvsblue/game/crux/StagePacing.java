package net.voiddustry.redvsblue.game.crux;

import arc.files.Fi;
import arc.struct.ObjectMap;
import arc.util.Log;
import arc.util.serialization.Jval;
import mindustry.Vars;

/** Stage timing/flow values loaded from config/rvsbUnits/stagePacing.hjson. */
public final class StagePacing {

    private static final String CONFIG = "rvsbUnits/stagePacing";
    private static final int DEFAULT_FINAL_STAGE = 13;
    private static final float DEFAULT_SECONDS = 300f;
    private static final int DEFAULT_START_DELAY = 120;
    private static final int DEFAULT_GAMES_UNTIL_RESTART = 2_000_000;

    private static int finalStage = DEFAULT_FINAL_STAGE;
    private static float defaultSeconds = DEFAULT_SECONDS;
    private static int startDelaySeconds = DEFAULT_START_DELAY;
    private static int gamesUntilRestart = DEFAULT_GAMES_UNTIL_RESTART;
    private static final ObjectMap<Integer, Float> durations = new ObjectMap<>();

    private StagePacing() {
    }

    public static void load() {
        durations.clear();

        Fi file = configFile();
        if (!file.exists()) {
            Log.warn("[StagePacing] @ not found, using defaults", file.absolutePath());
            return;
        }

        try {
            Jval root = Jval.read(file.readString());
            Jval value = root.has("stagePacing") ? root.get("stagePacing") : root;

            finalStage = Math.max(1, value.getInt("finalStage", DEFAULT_FINAL_STAGE));
            defaultSeconds = Math.max(1f, value.getFloat("defaultSeconds", DEFAULT_SECONDS));
            startDelaySeconds = Math.max(0, value.getInt("startDelaySeconds", DEFAULT_START_DELAY));
            gamesUntilRestart = Math.max(1, value.getInt("gamesUntilRestart", DEFAULT_GAMES_UNTIL_RESTART));

            if (value.has("durations")) {
                for (var entry : value.get("durations").asObject()) {
                    try {
                        durations.put(Integer.parseInt(entry.key.trim()), Math.max(1f, entry.value.asFloat()));
                    } catch (NumberFormatException e) {
                        Log.warn("[StagePacing] bad stage key '@' in durations, skipped", entry.key);
                    }
                }
            }

            Log.info("Loaded stage pacing from @ (finalStage=@, startDelay=@s, gamesUntilRestart=@)",
                    file.path(), finalStage, startDelaySeconds, gamesUntilRestart);
        } catch (Exception e) {
            Log.err("[StagePacing] failed to read @, using defaults", file.path());
            Log.err(e);
        }
    }

    public static int finalStage() {
        return finalStage;
    }

    public static int startDelaySeconds() {
        return startDelaySeconds;
    }

    public static int gamesUntilRestart() {
        return gamesUntilRestart;
    }

    public static float stageDuration(int stage) {
        return durations.get(stage, defaultSeconds);
    }

    private static Fi configFile() {
        Fi hjson = Vars.dataDirectory.child(CONFIG + ".hjson");
        Fi json = Vars.dataDirectory.child(CONFIG + ".json");
        return !hjson.exists() && json.exists() ? json : hjson;
    }
}
