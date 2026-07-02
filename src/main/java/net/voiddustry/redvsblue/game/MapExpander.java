package net.voiddustry.redvsblue.game;

import arc.Events;
import arc.util.Log;
import arc.util.Timer;
import mindustry.Vars;
import mindustry.content.Blocks;
import mindustry.game.EventType;
import mindustry.game.Rules;
import mindustry.gen.Call;
import mindustry.world.Tile;
import mindustry.world.Tiles;
import mindustry.world.blocks.environment.Floor;

/**
 * Expands every loaded map upwards by a strip of tiles reserved for background logic,
 * and makes sure the playable area (rules.limitMapArea) never includes that strip,
 * even if a map's world processor re-sets the map area to the full map size.
 */
public class MapExpander {

    /** Number of rows added above the original map. Reserved for background logic, never playable. */
    public static final int STRIP_HEIGHT = 5;

    /** Rules tag marking a world whose tiles already contain the strip (protects saves from double expansion). */
    private static final String EXPANDED_TAG = "rvsb-strip-expanded";

    private static boolean expanded = false;

    private MapExpander() {
    }

    public static void init() {
        Events.on(EventType.WorldLoadBeginEvent.class, event -> expanded = false);

        // Runs at the start of endMapLoad(), before darkness, physics and pathfinding
        // are set up, so the engine finishes loading with the expanded tile grid.
        Events.on(EventType.WorldLoadEndEvent.class, event -> expand());

        // Map area sync: every second, make sure the strip is never part of the playable
        // area. The 30+ existing maps were made before this feature, so their world
        // processors may set mapArea to the full map size and accidentally open the strip.
        Timer.schedule(MapExpander::syncMapArea, 1f, 1f);
    }

    private static void expand() {
        if (Vars.state.rules.tags.getBool(EXPANDED_TAG)) {
            // Loading a save of an already expanded world, do not expand again.
            expanded = true;
            return;
        }

        Tiles old = Vars.world.tiles;
        int width = old.width;
        int oldHeight = old.height;
        if (width == 0 || oldHeight == 0) return;

        Tiles resized = new Tiles(width, oldHeight + STRIP_HEIGHT);

        // Keep the original tile objects, so buildings and spawns stay valid.
        for (int y = 0; y < oldHeight; y++) {
            for (int x = 0; x < width; x++) {
                resized.set(x, y, old.getn(x, y));
            }
        }

        // Fill the strip copying only floors from the row below, never walls or overlays.
        for (int x = 0; x < width; x++) {
            Floor floor = old.getn(x, oldHeight - 1).floor();
            for (int y = oldHeight; y < oldHeight + STRIP_HEIGHT; y++) {
                resized.set(x, y, new Tile(x, y, floor, Blocks.air, Blocks.air));
            }
        }

        Vars.world.tiles = resized;
        Vars.state.rules.tags.put(EXPANDED_TAG, "true");
        expanded = true;

        Log.info("Expanded map upwards by @ tiles: @x@ -> @x@", STRIP_HEIGHT, width, oldHeight, width, oldHeight + STRIP_HEIGHT);
    }

    private static void syncMapArea() {
        if (!expanded || !Vars.state.isGame()) return;

        Rules rules = Vars.state.rules;

        // Keep the marker in the rules so saves of this world are never expanded twice,
        // even if something replaced the rules object after world load.
        rules.tags.put(EXPANDED_TAG, "true");

        int maxHeight = Vars.world.height() - STRIP_HEIGHT;
        if (maxHeight <= 0) return;

        boolean changed = false;

        if (!rules.limitMapArea) {
            // Map never limited its play area: enable the limit to hide the strip.
            rules.limitMapArea = true;
            rules.limitX = 0;
            rules.limitY = 0;
            rules.limitWidth = Vars.world.width();
            rules.limitHeight = maxHeight;
            changed = true;
        } else {
            // Clamp map areas set by old world processors so they never open up the strip.
            if (rules.limitY >= maxHeight) {
                rules.limitY = maxHeight - 1;
                changed = true;
            }
            if (rules.limitY + rules.limitHeight > maxHeight) {
                rules.limitHeight = Math.max(1, maxHeight - rules.limitY);
                changed = true;
            }
        }

        if (changed) {
            Call.setRules(rules);
        }
    }
}
