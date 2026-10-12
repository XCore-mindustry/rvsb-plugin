package net.voiddustry.redvsblue.game.crux;

import arc.Events;
import arc.files.Fi;
import arc.math.Mathf;
import arc.struct.FloatSeq;
import arc.struct.ObjectFloatMap;
import arc.struct.ObjectSet;
import arc.struct.Seq;
import arc.util.Log;
import arc.util.Strings;
import arc.util.Time;
import arc.util.serialization.Jval;
import mindustry.Vars;
import mindustry.game.EventType;
import mindustry.game.SpawnGroup;
import mindustry.game.Team;
import mindustry.gen.Groups;
import mindustry.gen.Unit;
import mindustry.type.StatusEffect;
import mindustry.type.UnitType;
import mindustry.world.Block;
import mindustry.world.Tile;
import net.voiddustry.redvsblue.RedVsBluePlugin;
import net.voiddustry.redvsblue.util.Utils;

//sloooooop
public final class StrengthSpawner {
    private static final String CONFIG_FILE = "rvsbUnits/spawns";
    private static final String WEIGHTS_FILE = "rvsbUnits/unitWeights";
    private static final String MAP_DIR = "rvsbUnits/mapspawns";
    /** Effects without explicit duration are effectively permanent. */
    private static final float PERMANENT = 60f * 60f * 60f * 24f;

    // ---- loaded config ----
    private static Jval config;
    private static final ObjectFloatMap<UnitType> weights = new ObjectFloatMap<>();
    private static float defaultWeight = 1f;
    private static float updateSeconds = 60f;

    // ---- runtime state ----
    private static final Seq<Spawner> active = new Seq<>();
    private static boolean running, hooked;
    private static float clock, untilUpdate, untilCheck;
    private static int blueUnits, bluePlayers;
    private static String mapKey = "";
    private static final ObjectSet<String> warned = new ObjectSet<>();

    private StrengthSpawner() {
    }

    // =====================================================================================================
    // lifecycle
    // =====================================================================================================

    /** Loads the configs and registers the update hook. Call once from plugin init. */
    public static void init() {
        load();
        if (hooked) return;
        hooked = true;
        Events.run(EventType.Trigger.update, StrengthSpawner::update);
    }

    /** (Re)reads spawns + unitWeights. Running spawners are rebuilt for the current map. */
    public static void load() {
        warned.clear();
        weights.clear();
        defaultWeight = 1f;
        try {
            Fi wf = configFile(WEIGHTS_FILE);
            if (wf.exists()) {
                Jval w = Jval.read(wf.readString());
                defaultWeight = w.getFloat("default", 1f);
                if (w.has("weights")) {
                    for (var e : w.get("weights").asObject()) {
                        UnitType type = Vars.content.unit(e.key);
                        if (type == null) Log.warn("[StrengthSpawner] unknown unit in weights: @", e.key);
                        else weights.put(type, e.value.asFloat());
                    }
                }
            } else {
                Log.warn("[StrengthSpawner] @ not found, all weights = 1", wf.absolutePath());
            }
        } catch (Throwable t) {
            Log.err("[StrengthSpawner] failed to read " + WEIGHTS_FILE + ".hjson", t);
        }

        config = null;
        try {
            Fi cf = configFile(CONFIG_FILE);
            if (cf.exists()) {
                config = Jval.read(cf.readString());
                updateSeconds = Math.max(1f, config.getFloat("updateSeconds", 60f));
            } else {
                Log.warn("[StrengthSpawner] @ not found, spawner disabled", cf.absolutePath());
            }
        } catch (Throwable t) {
            Log.err("[StrengthSpawner] failed to read " + CONFIG_FILE + ".hjson", t);
        }
        if (running) reset();
    }

    /** Builds the spawner list for the current map and restarts the clock. Call on world load. */
    public static void reset() {
        active.clear();
        clock = 0f;
        untilUpdate = 0f;
        untilCheck = 0f;
        running = true;
        if (config == null || Vars.state.map == null) return;

        String fileName = Vars.state.map.file != null ? Vars.state.map.file.nameWithoutExtension() : "";
        String displayName = Strings.stripColors(Vars.state.map.name());
        mapKey = fileName.isEmpty() ? displayName : fileName;
        Jval mapCfg = readMapConfig(fileName, displayName);

        Jval global = config.has("global") ? config.get("global") : null;
        if (global != null && !isException(global, fileName, displayName)) {
            float mult = mapCfg == null ? 1f : mapCfg.getFloat("globalMultiplier", 1f);
            readSpawners(global.get("spawners"), mult, "global");
        }
        if (mapCfg != null) readSpawners(mapCfg.get("spawners"), 1f, mapKey);

        Log.info("[StrengthSpawner] map '@': @ spawner(s) active", mapKey, active.size);
    }

    /**
     * Per map config: {@code rvsbUnits/mapspawns/<map>.hjson} (or .json), matched (ignoring case) by map file name without
     * extension first, then by display name. Read on every reset, so edits apply from the next map.
     */
    private static Jval readMapConfig(String fileName, String displayName) {
        Fi dir = Vars.dataDirectory.child(MAP_DIR);
        if (!dir.isDirectory()) return null;
        Seq<Fi> files = new Seq<>(dir.list(f -> isConfig(f.getName())));
        files.sort(f -> f.extension().equalsIgnoreCase("hjson") ? 0 : 1); // .hjson wins over .json
        for (String candidate : new String[]{fileName, displayName}) {
            if (candidate.isEmpty()) continue;
            for (Fi f : files) {
                if (!f.nameWithoutExtension().equalsIgnoreCase(candidate)) continue;
                try {
                    mapKey = f.nameWithoutExtension();
                    return Jval.read(f.readString());
                } catch (Throwable t) {
                    Log.err("[StrengthSpawner] failed to read " + f.path(), t);
                    return null;
                }
            }
        }
        return null;
    }

    /** {@code base + ".hjson"} if it exists, otherwise {@code base + ".json"}, otherwise the (missing) .hjson file. */
    private static Fi configFile(String base) {
        Fi hjson = Vars.dataDirectory.child(base + ".hjson"), json = Vars.dataDirectory.child(base + ".json");
        return !hjson.exists() && json.exists() ? json : hjson;
    }

    private static boolean isConfig(String fileName) {
        String n = fileName.toLowerCase();
        return n.endsWith(".hjson") || n.endsWith(".json");
    }

    /** Stops all spawning until the next {@link #reset()}. Call on game over. */
    public static void stop() {
        running = false;
        active.clear();
    }

    private static boolean isException(Jval global, String fileName, String displayName) {
        if (!global.has("exceptions")) return false;
        for (Jval ex : global.get("exceptions").asArray()) {
            String s = ex.asString();
            if (s.equalsIgnoreCase(fileName) || s.equalsIgnoreCase(displayName)) return true;
        }
        return false;
    }

    private static void readSpawners(Jval arr, float multiplier, String source) {
        if (arr == null || !arr.isArray()) return;
        for (Jval j : arr.asArray()) {
            try {
                active.add(new Spawner(j, multiplier, source));
            } catch (Throwable t) {
                Log.err("[StrengthSpawner] bad spawner in " + source + ": " + j.getString("name", "?"), t);
            }
        }
    }

    // =====================================================================================================
    // update loop
    // =====================================================================================================

    private static void update() {
        if (!running || active.isEmpty() || !RedVsBluePlugin.playing || !Utils.gameRun || Vars.state.isPaused()) return;
        float dt = Time.delta / 60f;
        clock += dt;
        untilUpdate -= dt;
        untilCheck -= dt;

        if (untilUpdate <= 0f) {
            untilUpdate += updateSeconds;
            refreshStrength();
        }

        for (Spawner s : active) s.stepFlex(dt);

        if (untilCheck <= 0f) {
            untilCheck = 1f;
            for (Spawner s : active) s.check();
        }
    }

    private static void refreshStrength() {
        blueUnits = 0;
        bluePlayers = 0;
        Groups.unit.each(u -> {
            if (u.team == Team.blue && !u.dead) blueUnits++;
        });
        Groups.player.each(p -> {
            if (p.team() == Team.blue) bluePlayers++;
        });
        for (Spawner s : active) s.retarget();
    }

    /** Warns once per unknown name (e.g. datapatch units missing on a vanilla server). The entry is skipped. */
    private static void unknown(String what, String owner, String name) {
        if (warned.add(what + ":" + name)) Log.warn("[StrengthSpawner] unknown @ '@' (first seen in @), skipped", what, name, owner);
    }

    static float weight(UnitType type) {
        return weights.get(type, defaultWeight);
    }

    /** Server command helper. */
    public static String status() {
        StringBuilder b = new StringBuilder("map '" + mapKey + "', t=" + (int) clock + "s, blue units=" + blueUnits
                + ", blue players=" + bluePlayers + ", stage=" + RedVsBluePlugin.stage + (running ? "" : " (stopped)"));
        for (Spawner s : active) {
            b.append("\n  ").append(s.name).append(" [").append(s.metric.name().toLowerCase()).append("] value=")
                    .append((int) s.value).append(" target=").append((int) s.target)
                    .append(" next in ").append(Math.max(0, (int) (s.nextTime - clock))).append("s");
        }
        return b.toString();
    }

    // =====================================================================================================
    // config objects (nested; everything lives in this one file)
    // =====================================================================================================

    enum Metric {
        /** sum of weighted hp. */
        SUM,
        /** average of weighted hp (sum is weighted, divided by counted units). */
        AVERAGE,
        /** average + highest weighted hp / 2. */
        AVERAGE_TOP_HALF,
        /** top1 + top2*d + top3*d^2 ... (d = decay, default 0.75). */
        DECAYING;

        static Metric parse(String s) {
            return switch (s.toLowerCase().replace("-", "").replace("_", "")) {
                case "sum" -> SUM;
                case "average", "avg" -> AVERAGE;
                case "averagetophalf", "avgtophalf", "averageplustophalf" -> AVERAGE_TOP_HALF;
                case "decaying", "decayingsum", "decay" -> DECAYING;
                default -> throw new IllegalArgumentException("unknown metric " + s);
            };
        }
    }

    static final class Spawner {
        final String name;
        final Metric metric;
        final boolean currentHealth, playersOnly, flex, waitForDeath, weighted, peak;
        final float multiplier, minUnitHp, maxUnitHp, decay, flexSeconds, grace, playerScaling;
        final int minStage, maxStage;
        final ObjectSet<UnitType> exclude = new ObjectSet<>(), include = new ObjectSet<>();
        final ObjectFloatMap<Block> buildingBonus = new ObjectFloatMap<>();
        final Seq<Tier> tiers = new Seq<>();

        float target, value, flexRate, nextTime, lastCooldown;
        final Seq<Unit> alive = new Seq<>();
        private final FloatSeq scratch = new FloatSeq();

        Spawner(Jval j, float mapMultiplier, String source) {
            name = j.getString("name", source + "#" + active.size);
            metric = Metric.parse(j.getString("metric", "sum"));
            currentHealth = j.getString("hp", "max").toLowerCase().startsWith("current");
            playersOnly = j.getBool("playersOnly", false);
            weighted = j.getBool("weighted", true);
            peak = j.getBool("peak", false);
            flex = j.getBool("flex", false);
            flexSeconds = Math.max(1f, j.getFloat("flexSeconds", 300f));
            waitForDeath = j.getBool("waitForDeath", false);
            multiplier = j.getFloat("multiplier", 1f) * mapMultiplier;
            minUnitHp = j.getFloat("minUnitHp", 0f);
            maxUnitHp = j.getFloat("maxUnitHp", Float.MAX_VALUE);
            decay = j.getFloat("decay", 0.75f);
            grace = j.getFloat("graceSeconds", 0f);
            playerScaling = Math.min(0.99f, j.getFloat("playerScaling", 0f));
            minStage = j.getInt("minStage", 0);
            maxStage = j.getInt("maxStage", Integer.MAX_VALUE);
            units(j, "exclude", exclude);
            units(j, "include", include);
            if (j.has("buildingBonus")) {
                for (var e : j.get("buildingBonus").asObject()) {
                    Block block = Vars.content.block(e.key);
                    if (block == null) unknown("block", name, e.key);
                    else buildingBonus.put(block, e.value.asFloat());
                }
            }
            for (Jval t : j.get("tiers").asArray()) tiers.add(new Tier(t, name));
            tiers.sort(t -> -t.min); // highest first
        }

        private void units(Jval j, String key, ObjectSet<UnitType> out) {
            if (!j.has(key)) return;
            for (Jval v : j.get(key).asArray()) {
                UnitType type = Vars.content.unit(v.asString());
                if (type == null) unknown("unit", name, v.asString());
                else out.add(type);
            }
        }

        float compute() {
            scratch.clear();
            float bonus = 0f;
            for (Unit u : Groups.unit) {
                if (u.team != Team.blue || u.dead) continue;
                if (playersOnly && !u.isPlayer()) continue;
                if (exclude.contains(u.type) || (!include.isEmpty() && !include.contains(u.type))) continue;
                if (u.maxHealth < minUnitHp || u.maxHealth > maxUnitHp) continue;
                float hp = currentHealth ? u.health + 0.3f * u.maxHealth : u.maxHealth;
                scratch.add(Math.max(0f, hp) * (weighted ? weight(u.type) : 1f));
            }
            for (var e : buildingBonus) {
                bonus += Team.blue.data().getBuildings(e.key).size * e.value;
            }
            float result = 0f;
            int n = scratch.size;
            switch (metric) {
                case SUM -> result = scratch.sum();
                case AVERAGE -> result = n == 0 ? 0f : scratch.sum() / n;
                case AVERAGE_TOP_HALF -> {
                    if (n > 0) {
                        float max = 0f;
                        for (int i = 0; i < n; i++) max = Math.max(max, scratch.items[i]);
                        result = scratch.sum() / n + max / 2f;
                    }
                }
                case DECAYING -> {
                    scratch.sort();
                    float f = 1f;
                    for (int i = n - 1; i >= 0; i--, f *= decay) result += scratch.items[i] * f;
                }
            }
            return (result + bonus) * multiplier;
        }

        void retarget() {
            float now = compute();
            target = peak ? Math.max(target, now) : now; // peak: highest value since the map started
            if (flex) {
                // linear ramp: constant speed that would reach the new target in flexSeconds
                flexRate = Math.abs(target - value) / flexSeconds;
            } else {
                value = target;
            }
        }

        void stepFlex(float dt) {
            if (flex) value = Mathf.approach(value, target, flexRate * dt);
        }

        void check() {
            int stage = RedVsBluePlugin.stage;
            if (clock < grace || stage < minStage || stage > maxStage) return;
            if (waitForDeath) {
                alive.removeAll(u -> u == null || u.dead || !u.isAdded());
                if (!alive.isEmpty()) {
                    nextTime = Math.max(nextTime, clock + lastCooldown);
                    return;
                }
            }
            if (clock < nextTime) return;

            Tier tier = null;
            for (Tier t : tiers) {
                if (value >= t.min && value < t.max && stage >= t.minStage && stage <= t.maxStage) {
                    tier = t;
                    break;
                }
            }
            if (tier == null) return;

            Option option = tier.roll();
            if (option != null) {
                Tile tile = RedVsBluePlugin.redSpawns.isEmpty() ? null : RedVsBluePlugin.redSpawns.random();
                if (tile == null) return;
                for (UnitEntry e : option.units) e.spawn(tile, this);
            }
            int n = Math.max(1, bluePlayers);
            lastCooldown = tier.cooldown * (float) Math.pow(1f - playerScaling, (n - 1) / 9f);
            nextTime = clock + lastCooldown;
        }
    }

    static final class Tier {
        final float min, max, cooldown;
        final int minStage, maxStage;
        final Seq<Option> options = new Seq<>();
        final float totalWeight;

        Tier(Jval j, String owner) {
            min = j.getFloat("min", 0f);
            max = j.getFloat("max", Float.MAX_VALUE);
            cooldown = j.getFloat("cooldown", 60f);
            minStage = j.getInt("minStage", 0);
            maxStage = j.getInt("maxStage", Integer.MAX_VALUE);
            if (j.has("options")) {
                for (Jval o : j.get("options").asArray()) options.add(new Option(o, owner));
            } else if (j.has("units")) {
                options.add(new Option(j, owner)); // shorthand: a single option
            }
            float w = 0f;
            for (Option o : options) w += o.weight;
            totalWeight = w;
        }

        /** Weighted random option; an option with no units means "nothing this time". */
        Option roll() {
            if (totalWeight <= 0f) return null;
            float r = Mathf.random(totalWeight);
            for (Option o : options) {
                r -= o.weight;
                if (r <= 0f) return o.units.isEmpty() ? null : o;
            }
            Option last = options.peek();
            return last.units.isEmpty() ? null : last;
        }
    }

    static final class Option {
        final float weight;
        final Seq<UnitEntry> units = new Seq<>();

        Option(Jval j, String owner) {
            weight = j.getFloat("weight", 1f);
            if (j.has("units")) {
                for (Jval u : j.get("units").asArray()) {
                    UnitEntry e = new UnitEntry(u, owner);
                    if (e.valid()) units.add(e);
                }
            }
        }
    }

    static final class UnitEntry {
        final UnitType type;
        final int wave, count;
        final float extraPerBlueUnit, shield, health, effectSeconds;
        final double flag;
        final boolean setFlag;
        final Seq<StatusEffect> effects = new Seq<>();
        final FloatSeq effectDurations = new FloatSeq(); // seconds, 0 = entry default

        UnitEntry(Jval j, String owner) {
            String typeName = j.getString("type", "");
            type = typeName.isEmpty() ? null : Vars.content.unit(typeName);
            if (!typeName.isEmpty() && type == null) unknown("unit", owner, typeName);
            wave = j.getInt("wave", 0);
            count = j.getInt("count", 1);
            extraPerBlueUnit = j.getFloat("extraPerBlueUnit", 0f);
            shield = j.getFloat("shield", 0f);
            health = j.getFloat("health", 0f);
            effectSeconds = j.getFloat("effectSeconds", 0f);
            setFlag = j.has("flag");
            flag = j.getDouble("flag", 0);
            if (j.has("effects")) {
                // "boss" or {"name": "boss", "seconds": 10}
                for (Jval v : j.get("effects").asArray()) {
                    String effectName = v.isObject() ? v.getString("name", "") : v.asString();
                    StatusEffect s = Vars.content.statusEffect(effectName);
                    if (s == null) {
                        unknown("status effect", owner, effectName);
                    } else {
                        effects.add(s);
                        effectDurations.add(v.isObject() ? v.getFloat("seconds", 0f) : 0f);
                    }
                }
            }
        }

        boolean valid() {
            return type != null || wave > 0;
        }

        void spawn(Tile tile, Spawner owner) {
            int amount = count + (extraPerBlueUnit > 0f ? (int) Mathf.random(extraPerBlueUnit * blueUnits) : 0);
            float x = tile.worldx(), y = tile.worldy();
            if (wave > 0) {
                // spawn the map's wave groups of the given wave (1-based) like spawnwave would
                for (int i = 0; i < amount; i++) {
                    for (SpawnGroup group : Vars.state.rules.spawns) {
                        // same filter as the spawnwave instruction
                        if (group.type == null || (group.spawn != -1 && group.spawn != tile.pos())) continue;
                        int n = group.getSpawned(wave - 1);
                        for (int k = 0; k < n; k++) {
                            Unit u = group.createUnit(Team.crux, wave - 1);
                            u.set(x + Mathf.range(Vars.tilesize), y + Mathf.range(Vars.tilesize));
                            u.add();
                            decorate(u, owner);
                        }
                    }
                }
                return;
            }
            for (int i = 0; i < amount; i++) {
                Unit u = type.spawn(Team.crux, x + Mathf.range(Vars.tilesize), y + Mathf.range(Vars.tilesize));
                decorate(u, owner);
            }
        }

        private void decorate(Unit u, Spawner owner) {
            float dur = effectSeconds > 0f ? effectSeconds * 60f : PERMANENT;
            for (int i = 0; i < effects.size; i++) {
                float own = effectDurations.get(i);
                u.apply(effects.get(i), own > 0f ? own * 60f : dur);
            }
            if (shield > 0f) u.shield = shield;
            if (health > 0f) u.health = health;
            if (setFlag) u.flag = flag;
            if (owner.waitForDeath) owner.alive.add(u);
        }
    }
}
