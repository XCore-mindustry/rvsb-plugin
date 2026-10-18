package net.voiddustry.redvsblue.evolution;

import arc.files.Fi;
import arc.util.Log;
import arc.util.serialization.Jval;
import mindustry.Vars;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static net.voiddustry.redvsblue.util.UnitsConfig.multp;

/**
 * The evolution tree, read from config/rvsbUnits/evolutions.hjson on every map load.
 *
 * <pre>
 * {
 *   "dagger": { "tier": 1, "stage": 1, "cost": 2, "evolves": ["atrax", "mace"] }
 *   "atrax":  { "tier": 2, "stage": 2, "cost": 18, "evolves": ["cleroi"] }
 * }
 * </pre>
 * Units are listed in menu order. {@code cost} is multiplied by the global price multiplier.
 * If the file is missing or broken the previously loaded tree stays active.
 */
public final class Evolutions {

    private static final String configPath = "rvsbUnits/evolutions.hjson";

    /** unit name -> evolution, in file order. Replaced as a whole on reload, never modified. */
    public static volatile Map<String, Evolution> evolutions = Collections.emptyMap();

    private Evolutions() {
    }

    /** All units of the tree in file order. */
    public static List<Evolution> all() {
        return new ArrayList<>(evolutions.values());
    }

    public static void load() {
        // Vars.dataDirectory is the server's config directory: config/rvsbUnits/evolutions.hjson
        Fi file = Vars.dataDirectory.child(configPath);
        if (!file.exists()) {
            Log.err("Evolution tree configuration is missing: @", file.absolutePath());
            return;
        }

        Map<String, Evolution> loaded = new LinkedHashMap<>();
        try {
            Jval root = Jval.read(file.readString());
            for (var entry : root.asObject()) {
                String name = entry.key;
                Jval value = entry.value;
                if (!value.isObject()) {
                    Log.warn("[Evolutions] '@' is not an object, skipped.", name);
                    continue;
                }
                int tier = value.getInt("tier", -1);
                int stage = value.getInt("stage", -1);
                int cost = value.getInt("cost", -1);
                if (tier < 1 || stage < 1 || cost < 0) {
                    Log.warn("[Evolutions] '@' needs tier >= 1, stage >= 1 and cost >= 0, skipped.", name);
                    continue;
                }
                List<String> evolves = new ArrayList<>();
                Jval list = value.get("evolves");
                if (list != null && list.isArray()) {
                    for (Jval target : list.asArray()) evolves.add(target.asString());
                }
                loaded.put(name, new Evolution(name, tier, cost * multp, evolves.toArray(new String[0]), stage));
            }
        } catch (Exception exception) {
            Log.err("Unable to load the evolution tree from @, keeping the previous one.", file.path());
            Log.err(exception);
            return;
        }

        // typos: units the server doesn't know (they are kept, the menu already skips them safely)
        for (Evolution evolution : loaded.values()) {
            if (Vars.content.unit(evolution.unitName) == null) {
                Log.warn("[Evolutions] Unknown unit '@'.", evolution.unitName);
            }
            for (String target : evolution.evolutions) {
                if (!loaded.containsKey(target)) {
                    Log.warn("[Evolutions] '@' evolves into '@', which is not in the tree.", evolution.unitName, target);
                }
            }
        }

        evolutions = Collections.unmodifiableMap(loaded);
        EvolutionTree.reset();
        EvolutionMenu.resetLayout();
        Log.info("Loaded @ evolution tree units from @.", loaded.size(), file.path());
    }
}
