package net.voiddustry.redvsblue.game.crux;

import arc.files.Fi;
import arc.struct.ObjectMap;
import arc.struct.Seq;
import arc.util.Log;
import arc.util.serialization.Jval;
import mindustry.Vars;
import mindustry.type.UnitType;

/** Loads the selectable Crux units and boss candidates from config/rvsbUnits/stages.json. */
public final class StageUnits {
    private static final String configPath = "rvsbUnits/stages.json";

    private static final ObjectMap<Integer, StageDefinition> stages = new ObjectMap<>();
    public static final ObjectMap<Integer, UnitType> bosses = new ObjectMap<>();

    private StageUnits() {
    }

    public static void load() {
        stages.clear();
        // Vars.dataDirectory is the server's config directory. This resolves to config/rvsbUnits/stages.json.
        Fi file = Vars.dataDirectory.child(configPath);
        if (!file.exists()) {
            Log.err("Crux stage configuration is missing: @", file.absolutePath());
            return;
        }

        try {
            Jval root = Jval.read(file.readString());
            for (Jval stageValue : root.get("stages").asArray()) {
                int number = stageValue.getInt("stage", -1);
                if (number < 1) continue;

                StageDefinition definition = new StageDefinition();
                addSelectableUnits(stageValue.get("units"), definition.units);
                addUnits(stageValue.get("bosses"), definition.bossCandidates);
                definition.bossCount = Math.max(1, stageValue.getInt("bossCount", 1));
                stages.put(number, definition);
            }
            rollBosses();
            Log.info("Loaded @ configured Crux stages from @.", stages.size, file.path());
        } catch (Exception exception) {
            Log.err("Unable to load Crux stage configuration from @.", file.path());
            Log.err(exception);
        }
    }

    /** Selects one configured boss for every stage. Called once per game/map cycle. */
    public static void rollBosses() {
        bosses.clear();
        stages.each((number, definition) -> {
            if (!definition.bossCandidates.isEmpty()) {
                bosses.put(number, definition.bossCandidates.random());
            }
        });
    }

    public static ObjectMap<UnitType, String> unitsForStage(int stage) {
        StageDefinition definition = stages.get(stage);
        return definition == null ? new ObjectMap<>() : definition.units;
    }

    public static UnitType bossForStage(int stage) {
        return bosses.get(stage);
    }

    /** How many copies of the stage boss spawn ("bossCount", default 1). */
    public static int bossCountForStage(int stage) {
        StageDefinition definition = stages.get(stage);
        return definition == null ? 1 : definition.bossCount;
    }

    private static void addSelectableUnits(Jval values, ObjectMap<UnitType, String> target) {
        if (values == null || !values.isArray()) return;
        for (Jval value : values.asArray()) {
            UnitType unit = Vars.content.unit(value.asString());
            if (unit == null) {
                Log.warn("Unknown unit '@' in @.", value.asString(), configPath);
            } else {
                target.put(unit, unit.name);
            }
        }
    }

    private static void addUnits(Jval values, Seq<UnitType> target) {
        if (values == null || !values.isArray()) return;
        for (Jval value : values.asArray()) {
            UnitType unit = Vars.content.unit(value.asString());
            if (unit == null) {
                Log.warn("Unknown unit '@' in @.", value.asString(), configPath);
            } else {
                target.add(unit);
            }
        }
    }

    private static class StageDefinition {
        final ObjectMap<UnitType, String> units = new ObjectMap<>();
        final Seq<UnitType> bossCandidates = new Seq<>();
        int bossCount = 1;
    }
}
