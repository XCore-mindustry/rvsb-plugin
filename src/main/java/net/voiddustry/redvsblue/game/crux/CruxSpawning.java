package net.voiddustry.redvsblue.game.crux;

import arc.files.Fi;
import arc.struct.IntFloatMap;
import arc.struct.ObjectMap;
import arc.struct.Seq;
import arc.util.Log;
import arc.util.serialization.Jval;
import mindustry.Vars;
import mindustry.type.UnitType;

/**
 * Crux normal-unit quotas and stage-specific bonus spawns, loaded from
 * config/rvsbUnits/cruxSpawning.hjson.
 */
public final class CruxSpawning {

    private static final String CONFIG = "rvsbUnits/cruxSpawning";

    public enum Context {
        playerRespawn,
        aiSpawn
    }

    public static final class SpecialSpawn {
        public final String type;
        public final float chance;
        public final int min, max;

        SpecialSpawn(String type, float chance, int min, int max) {
            this.type = type;
            this.chance = chance;
            this.min = min;
            this.max = max;
        }
    }

    private static final class StageSpawning {
        int unitCount = -1;
        final ObjectMap<String, Integer> unitCounts = new ObjectMap<>();
    }

    private static long recentWindowMillis = 45_000L;
    private static float playerWeight = 1f;
    private static float stageWeight = 1f;
    private static int stageCap = 12;
    private static float divisor = 3f;

    private static final IntFloatMap stageMultipliers = new IntFloatMap();
    private static final ObjectMap<Integer, StageSpawning> perStage = new ObjectMap<>();
    private static final ObjectMap<Integer, ObjectMap<Context, Seq<SpecialSpawn>>> specials = new ObjectMap<>();

    private CruxSpawning() {
    }

    public static void load() {
        recentWindowMillis = 45_000L;
        playerWeight = 1f;
        stageWeight = 1f;
        stageCap = 12;
        divisor = 3f;
        stageMultipliers.clear();
        perStage.clear();
        specials.clear();

        Fi file = configFile();
        if (!file.exists()) {
            Log.warn("[CruxSpawning] @ not found, using defaults", file.absolutePath());
            return;
        }

        try {
            Jval root = Jval.read(file.readString());
            Jval value = root.has("cruxSpawning") ? root.get("cruxSpawning") : root;

            recentWindowMillis = Math.max(1L, (long) (value.getFloat("recentWindowSeconds", 45f) * 1000f));

            if (value.has("formula")) {
                Jval formula = value.get("formula");
                playerWeight = formula.getFloat("playerWeight", 1f);
                stageWeight = formula.getFloat("stageWeight", 1f);
                stageCap = Math.max(0, formula.getInt("stageCap", 12));
                divisor = Math.max(0.001f, formula.getFloat("divisor", 3f));
            }

            if (value.has("stageMultipliers")) {
                for (var entry : value.get("stageMultipliers").asObject()) {
                    try {
                        stageMultipliers.put(Integer.parseInt(entry.key.trim()), entry.value.asFloat());
                    } catch (NumberFormatException e) {
                        Log.warn("[CruxSpawning] bad stage in stageMultipliers: @", entry.key);
                    }
                }
            }

            if (value.has("perStage")) {
                for (var entry : value.get("perStage").asObject()) {
                    int stage;
                    try {
                        stage = Integer.parseInt(entry.key.trim());
                    } catch (NumberFormatException e) {
                        Log.warn("[CruxSpawning] bad stage in perStage: @", entry.key);
                        continue;
                    }
                    Jval stageValue = entry.value;
                    if (!stageValue.isObject()) continue;

                    StageSpawning definition = new StageSpawning();
                    definition.unitCount = stageValue.getInt("unitCount", -1);
                    if (stageValue.has("unitCounts")) {
                        for (var countEntry : stageValue.get("unitCounts").asObject()) {
                            definition.unitCounts.put(countEntry.key, Math.max(0, countEntry.value.asInt()));
                        }
                    }
                    perStage.put(stage, definition);
                }
            }

            if (value.has("specials")) {
                for (var entry : value.get("specials").asObject()) {
                    int stage;
                    try {
                        stage = Integer.parseInt(entry.key.trim());
                    } catch (NumberFormatException e) {
                        Log.warn("[CruxSpawning] bad stage in specials: @", entry.key);
                        continue;
                    }
                    Jval stageValue = entry.value;
                    if (!stageValue.isObject()) continue;

                    ObjectMap<Context, Seq<SpecialSpawn>> contexts = new ObjectMap<>();
                    for (Context context : Context.values()) {
                        if (!stageValue.has(context.name())) continue;
                        Seq<SpecialSpawn> list = new Seq<>();
                        for (Jval item : stageValue.get(context.name()).asArray()) {
                            SpecialSpawn spawn = parseSpecial(item);
                            if (spawn != null) list.add(spawn);
                        }
                        if (!list.isEmpty()) contexts.put(context, list);
                    }
                    if (!contexts.isEmpty()) specials.put(stage, contexts);
                }
            }

            Log.info("Loaded crux spawning from @ (@ normal overrides, @ special stages)",
                    file.path(), perStage.size, specials.size);
        } catch (Exception e) {
            Log.err("[CruxSpawning] failed to read @, using defaults", file.path());
            Log.err(e);
        }
    }

    private static SpecialSpawn parseSpecial(Jval value) {
        if (value.isString()) {
            return new SpecialSpawn(value.asString(), 1f, 1, 1);
        }
        if (!value.isObject() || !value.has("type")) return null;

        String type = value.getString("type", "");
        if (type.isEmpty()) return null;

        float chance = Math.min(1f, Math.max(0f, value.getFloat("chance", 1f)));
        int min = Math.max(1, value.getInt("min", 1));
        int max = Math.max(min, value.getInt("max", min));
        return new SpecialSpawn(type, chance, min, max);
    }

    /** How many recently-spawned units of each type should exist, aligned with the provided unit list. */
    public static int[] quotas(int stage, Seq<UnitType> types, int players) {
        int[] result = new int[types.size];
        if (types.isEmpty()) return result;

        StageSpawning definition = perStage.get(stage);
        if (definition != null && !definition.unitCounts.isEmpty()) {
            for (int i = 0; i < types.size; i++) {
                result[i] = Math.max(0, definition.unitCounts.get(types.get(i).name, 0));
            }
            return result;
        }

        if (definition != null && definition.unitCount >= 0) {
            return distribute(definition.unitCount, types.size);
        }

        float stagePart = Math.min(stage, stageCap) * stageWeight;
        float total = (players * playerWeight + stagePart) / divisor;
        total *= stageMultipliers.get(stage, 1f);
        int count = Math.max(0, Math.round(total));

        // Preserve the original formula's integer division per type.
        int perType = count / types.size;
        for (int i = 0; i < result.length; i++) result[i] = perType;
        return result;
    }

    private static int[] distribute(int total, int types) {
        int[] result = new int[types];
        if (types <= 0) return result;
        int base = Math.max(0, total) / types;
        int remainder = Math.max(0, total) % types;
        for (int i = 0; i < types; i++) result[i] = base + (i < remainder ? 1 : 0);
        return result;
    }

    public static long recentWindowMillis() {
        return recentWindowMillis;
    }

    public static Seq<SpecialSpawn> specials(int stage, Context context) {
        ObjectMap<Context, Seq<SpecialSpawn>> contexts = specials.get(stage);
        if (contexts == null) return Seq.with();
        Seq<SpecialSpawn> list = contexts.get(context);
        return list == null ? Seq.with() : list;
    }

    private static Fi configFile() {
        Fi hjson = Vars.dataDirectory.child(CONFIG + ".hjson");
        Fi json = Vars.dataDirectory.child(CONFIG + ".json");
        return !hjson.exists() && json.exists() ? json : hjson;
    }
}
