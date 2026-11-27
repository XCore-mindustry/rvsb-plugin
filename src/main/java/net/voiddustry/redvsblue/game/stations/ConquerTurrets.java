package net.voiddustry.redvsblue.game.stations;

import arc.Events;
import arc.struct.Seq;
import arc.util.Timer;
import mindustry.Vars;
import mindustry.game.EventType;
import mindustry.game.Team;
import mindustry.gen.Building;
import mindustry.gen.Groups;
import mindustry.gen.Player;
import mindustry.gen.Unit;
import mindustry.gen.WorldLabel;
import mindustry.world.blocks.ConstructBlock.ConstructBuild;
import mindustry.type.UnitType;
import mindustry.world.Block;
import mindustry.world.Tile;

import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Ownership and strict 2-per-live-conquer limit for conquer turrets. */
public final class ConquerTurrets {
    private static final Map<Tile, String> owners = new ConcurrentHashMap<>();
    private static final Map<Tile, LabelData> labels = new ConcurrentHashMap<>();

    private record LabelData(String owner, String base, WorldLabel label) {}

    private ConquerTurrets() {
    }

    public static void init() {
        Events.on(EventType.WorldLoadEvent.class, event -> {
            owners.clear();
            labels.values().forEach(data -> data.label.hide());
            labels.clear();
            Timer.schedule(ConquerTurrets::enforce, 1f);
        });
        // Safety net: a tank death/unit change must not leave excess turrets behind.
        Timer.schedule(ConquerTurrets::enforce, 1f, 1f);
        Timer.schedule(ConquerTurrets::updateLabels, 0f, 0.5f);
    }

    public static void register(String uuid, Tile tile) {
        if (uuid == null || tile == null) return;
        owners.put(tile, uuid);

        if (labels.containsKey(tile)) return;
        Player player = Groups.player.find(p -> p.uuid().equals(uuid));
        if (player == null) return;

        String base = player.name + "[gold]'s\n[accent]Conquer Turret";
        labels.put(tile, new LabelData(uuid, base, StationUtils.createStationLabel(tile, base)));
    }

    public static void removeAt(Tile tile) {
        if (tile == null) return;
        owners.remove(tile);
        hideLabel(tile);
        if (tile.build != null) {
            owners.remove(tile.build.tile);
            hideLabel(tile.build.tile);
        }
    }

    private static void hideLabel(Tile tile) {
        LabelData data = labels.remove(tile);
        if (data != null) data.label.hide();
    }

    private static void updateLabels() {
        if (Vars.state == null || !Vars.state.isGame()) return;

        for (Map.Entry<Tile, LabelData> entry : new ArrayList<>(labels.entrySet())) {
            Tile tile = entry.getKey();
            LabelData data = entry.getValue();

            boolean present = tile != null && (tile.block() == turret()
                    || (tile.build instanceof ConstructBuild cb && cb.current == turret()));
            if (!present) {
                data.label.hide();
                labels.remove(tile);
                continue;
            }

            int percent = StationUtils.deconstructPercent(tile);
            data.label.text = percent >= 0
                    ? data.base + "\n[scarlet]deconstructing " + percent + "%[]"
                    : data.base;
        }
    }

    public static boolean owns(String uuid, Tile tile) {
        if (uuid == null || tile == null || tile.build == null) return false;
        return uuid.equals(owners.get(tile.build.tile));
    }

    /** Called when a blue player dies: their turrets are removed, then the cap is rechecked. */
    public static void destroyFor(Player player) {
        if (player == null) return;

        String uuid = player.uuid();
        for (Map.Entry<Tile, String> entry : new ArrayList<>(owners.entrySet())) {
            if (!uuid.equals(entry.getValue())) continue;

            Tile tile = entry.getKey();
            removeAt(tile);
            if (tile != null && tile.build != null && tile.build.block == turret()) {
                tile.build.kill();
            }
        }
        enforce();
    }

    /** Keeps the number of blue conquer turrets at or below 2 per live blue conquer. */
    public static void enforce() {
        Block turret = turret();
        if (turret == null || Vars.state == null || !Vars.state.isGame()) return;

        int live = 0;
        UnitType conquer = Vars.content.unit("conquer");
        for (Unit unit : Groups.unit) {
            if (unit.team == Team.blue && !unit.dead && conquer != null && unit.type == conquer) live++;
        }
        int limit = live * 2;

        var buildings = Team.blue.data().getBuildings(turret);
        int count = buildings.size;
        if (count <= limit) return;

        ArrayList<Building> list = new ArrayList<>();
        for (Building building : buildings) list.add(building);

        // First remove turrets whose owner is not a live tank anymore.
        for (Building building : list) {
            if (count <= limit) break;
            if (building == null || !building.isValid()) continue;

            String owner = owners.get(building.tile);
            if (owner == null || !isLiveTank(owner)) {
                removeAt(building.tile);
                building.kill();
                count--;
            }
        }

        // Then remove any remaining excess, oldest first.
        for (Building building : list) {
            if (count <= limit) break;
            if (building == null || !building.isValid()) continue;

            removeAt(building.tile);
            building.kill();
            count--;
        }
    }

    private static boolean isLiveTank(String uuid) {
        UnitType conquer = Vars.content.unit("conquer");
        if (conquer == null) return false;

        Player player = Groups.player.find(p -> p.uuid().equals(uuid));
        return player != null
                && player.team() == Team.blue
                && player.unit() != null
                && !player.unit().dead
                && player.unit().type == conquer;
    }

    private static Block turret() {
        return Vars.content.block("dp-conquer-turret");
    }
}
