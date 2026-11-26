package net.voiddustry.redvsblue.util;

import arc.Events;
import arc.files.Fi;
import arc.math.Mathf;
import arc.struct.Seq;
import arc.util.Log;
import arc.util.Strings;
import arc.util.serialization.Jval;
import mindustry.Vars;
import mindustry.game.EventType;
import mindustry.gen.Groups;
import mindustry.gen.Player;
import mindustry.gen.Unit;
import mindustry.net.Administration.ActionType;
import mindustry.net.Administration.PlayerAction;

/**
 * Optional per-map enforcement of the game's limited map area ({@code rules.limitMapArea}).
 * The engine only clamps non-AI teams, so crux AI can walk into the dark zone. Maps listed in
 * {@code rvsbUnits/mapArea.hjson} get clamped units and no build/break actions outside the area.
 *
 * <p>This follows the physical rectangle only. Fog of war ({@code rules.fog}) is a client-side
 * visibility effect and is not a movement boundary.</p>
 */
public final class MapAreaGuard {
    private static final String CONFIG = "rvsbUnits/mapArea";
    private static final int[] RECT = new int[4];

    private static boolean started;
    private static boolean enabled = true;
    private static boolean allMaps;
    private static boolean clampAI = true;
    private static boolean activeForMap;
    private static boolean listedForMap;
    private static final Seq<String> maps = new Seq<>();

    private MapAreaGuard() {
    }

    /** Registers the update/action hooks. Safe to call more than once. */
    public static synchronized void init() {
        if (started) return;
        started = true;

        load();
        Events.on(EventType.WorldLoadEvent.class, event -> load());
        Events.run(EventType.Trigger.update, MapAreaGuard::clampUnits);
        Vars.netServer.admins.addActionFilter(MapAreaGuard::allow);
    }

    /** Re-reads mapArea.hjson. Called on plugin init and every world load. */
    public static void load() {
        enabled = true;
        allMaps = false;
        clampAI = true;
        maps.clear();

        Fi hjson = Vars.dataDirectory.child(CONFIG + ".hjson");
        Fi json = Vars.dataDirectory.child(CONFIG + ".json");
        Fi file = !hjson.exists() && json.exists() ? json : hjson;
        if (file.exists()) {
            try {
                Jval root = Jval.read(file.readString());
                enabled = root.getBool("enabled", true);
                allMaps = root.getBool("all", false);
                clampAI = root.getBool("clampAI", true);
                if (root.has("maps")) {
                    for (Jval value : root.get("maps").asArray()) {
                        String name = value.asString().trim();
                        if (!name.isEmpty()) maps.add(name);
                    }
                }
            } catch (Throwable t) {
                Log.err("[MapAreaGuard] failed to read @", file.path());
                Log.err(t);
            }
        }

        updateActiveMap();
        Log.info("[MapAreaGuard] @: map '@' (@), rules=@, area=@, all=@, clampAI=@, maps=@",
                activeForMap ? "ACTIVE" : "inactive", mapName(), mapFile(),
                Vars.state.rules.limitMapArea, rectText(), allMaps, clampAI, maps.size);
    }

    private static String mapFile() {
        return Vars.state.map != null && Vars.state.map.file != null
                ? Vars.state.map.file.nameWithoutExtension() : "";
    }

    private static String mapName() {
        return Vars.state.map != null ? Strings.stripColors(Vars.state.map.name()) : "";
    }

    private static void updateActiveMap() {
        activeForMap = false;
        listedForMap = false;
        if (!enabled || Vars.state.map == null) return;

        String file = mapFile();
        String display = mapName();
        for (String map : maps) {
            if (map.equalsIgnoreCase(file) || map.equalsIgnoreCase(display)) {
                listedForMap = true;
                break;
            }
        }
        activeForMap = allMaps || listedForMap;
    }

    /**
     * Effective tile rectangle: {@code [x, y, width, height]}, or null when the guard has nothing
     * to enforce on this map (not listed/disabled, or the map has no limited area).
     */
    private static int[] rect() {
        if (!activeForMap) return null;

        var rules = Vars.state.rules;
        if (!rules.limitMapArea || rules.limitWidth <= 0 || rules.limitHeight <= 0) return null;

        RECT[0] = rules.limitX;
        RECT[1] = rules.limitY;
        RECT[2] = rules.limitWidth;
        RECT[3] = rules.limitHeight;
        return RECT;
    }

    private static String rectText() {
        int[] r = rect();
        return r == null ? "none" : "(" + r[0] + "," + r[1] + "," + r[2] + "," + r[3] + ")";
    }

    private static boolean inArea(int[] r, int x, int y) {
        return x >= r[0] && y >= r[1] && x < r[0] + r[2] && y < r[1] + r[3];
    }

    private static void clampUnits() {
        int[] r = rect();
        if (r == null) return;

        float margin = Vars.tilesize / 2f;
        float minX = r[0] * Vars.tilesize + margin;
        float maxX = (r[0] + r[2]) * Vars.tilesize - margin;
        float minY = r[1] * Vars.tilesize + margin;
        float maxY = (r[1] + r[3]) * Vars.tilesize - margin;
        if (minX > maxX || minY > maxY) return;

        for (Unit unit : Groups.unit) {
            if (unit == null || unit.dead) continue;
            if (!clampAI && !unit.isPlayer()) continue;

            float x = Mathf.clamp(unit.x, minX, maxX);
            float y = Mathf.clamp(unit.y, minY, maxY);
            if (x != unit.x || y != unit.y) {
                unit.set(x, y);
                unit.vel.setZero();
            }
        }
    }

    private static boolean allow(PlayerAction action) {
        if (action == null || action.tile == null) return true;
        int[] r = rect();
        if (r == null) return true;

        switch (action.type) {
            case placeBlock, breakBlock, rotate, configure, removePlanned -> {
                return inArea(r, action.tile.x, action.tile.y);
            }
            default -> {
                return true;
            }
        }
    }

    /** One-shot diagnostic for the admin command. */
    public static String describe(Player player) {
        var rules = Vars.state.rules;
        int[] r = rect();

        StringBuilder sb = new StringBuilder("[MapAreaGuard] map '").append(mapName()).append("'");
        String file = mapFile();
        if (!file.isEmpty()) sb.append(" (").append(file).append(")");

        sb.append("\n enabled=").append(enabled)
                .append(" all=").append(allMaps)
                .append(" listed=").append(listedForMap)
                .append(" clampAI=").append(clampAI);
        sb.append("\n guard=").append(activeForMap ? "ACTIVE" : "inactive")
                .append(" rules.limitMapArea=").append(rules.limitMapArea);
        if (rules.limitMapArea) {
            sb.append(" rules=(").append(rules.limitX).append(",").append(rules.limitY)
                    .append(",").append(rules.limitWidth).append(",").append(rules.limitHeight).append(")");
        }
        sb.append("\n effective=").append(rectText());
        if (r == null) sb.append("  (units are NOT clamped on this map)");

        if (player != null) {
            int tx = player.tileX(), ty = player.tileY();
            sb.append("\n you=(").append(tx).append(",").append(ty).append(") ")
                    .append(r != null && inArea(r, tx, ty) ? "inside" : "OUTSIDE");
        }
        return sb.toString();
    }
}
