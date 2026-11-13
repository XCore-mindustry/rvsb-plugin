package net.voiddustry.redvsblue.game.stations;

import mindustry.Vars;
import mindustry.content.Blocks;
import mindustry.gen.WorldLabel;
import mindustry.world.Block;
import mindustry.world.Tile;
import mindustry.world.blocks.ConstructBlock.ConstructBuild;
import net.voiddustry.redvsblue.game.stations.stationData.StationData;

public class StationUtils {
    public static WorldLabel createStationLabel(Tile tile, String text) {
        WorldLabel label = WorldLabel.create();
        label.x(tile.x * 8);
        label.y(tile.y * 8 + 4);
        label.fontSize = 0.8F;
        label.text = text;
        label.add();
        return label;
    }

    /** A tile is free only when it has no block and is not the void floor. */
    public static boolean isFreeTile(Tile tile) {
        return tile != null && tile.block().isAir() && tile.floor() != Blocks.empty;
    }

    /** Every tile covered by this block must be free. */
    public static boolean isFreePlacement(Block block, Tile origin) {
        if (block == null || origin == null) return false;
        int offset = -(block.size - 1) / 2;
        for (int dx = 0; dx < block.size; dx++) {
            for (int dy = 0; dy < block.size; dy++) {
                Tile tile = Vars.world.tile(origin.x + dx + offset, origin.y + dy + offset);
                if (!isFreeTile(tile)) return false;
            }
        }
        return true;
    }

    public static boolean isStationBlock(Block block) {
        return block == Blocks.pulverizer || block == Blocks.mender || block == Blocks.phaseWall
                || block == Blocks.beamNode || block == Blocks.slagIncinerator
                || block == Vars.content.block("dp-workbench-station")
                || block == Vars.content.block("dp-laboratory-station");
    }

    /** Station block on this tile, including an in-progress deconstruction, or null. */
    public static Block stationBlock(Tile tile) {
        if (tile == null) return null;
        Block block = tile.block();
        if (tile.build instanceof ConstructBuild cb) block = cb.current;
        return isStationBlock(block) ? block : null;
    }

    /** True if any tile covered by the placed block contains a station. */
    public static boolean footprintContainsStation(Block block, Tile origin) {
        if (block == null || origin == null) return false;
        int offset = -(block.size - 1) / 2;
        for (int dx = 0; dx < block.size; dx++) {
            for (int dy = 0; dy < block.size; dy++) {
                if (stationBlock(Vars.world.tile(origin.x + dx + offset, origin.y + dy + offset)) != null) return true;
            }
        }
        return false;
    }

    /** True when this tile holds the station block or its in-progress deconstruction. */
    public static boolean isStationTile(Tile tile, Block block) {
        if (tile == null || block == null) return false;
        if (tile.block() == block) return true;
        return tile.build instanceof ConstructBuild cb && cb.current == block;
    }

    /** True while the station on this tile is being deconstructed. */
    public static boolean isDeconstructing(Tile tile) {
        return tile != null && tile.build instanceof ConstructBuild;
    }

    /** 0..100 while the station on this tile is being deconstructed, -1 otherwise. */
    public static int deconstructPercent(Tile tile) {
        if (tile == null || !(tile.build instanceof ConstructBuild cb)) return -1;
        return Math.max(0, Math.min(100, Math.round((1f - cb.progress) * 100f)));
    }

    /** Keeps the station label visible and shows the deconstruction percentage. */
    public static void markDeconstructing(StationData data) {
        if (data == null || data.label() == null) return;
        int percent = deconstructPercent(data.tileOn());
        if (percent < 0) return;

        String text = data.label().text;
        int marker = text.lastIndexOf("\n[scarlet]deconstructing ");
        if (marker >= 0) text = text.substring(0, marker);

        data.label().text = text + "\n[scarlet]deconstructing " + percent + "%[]";
        if (marker < 0 && data.circle() != null) data.circle().hide();
    }

    public static void drawStationName(Tile tile, String text, float time) {
        WorldLabel label = WorldLabel.create();
        label.x(tile.x * 8);
        label.y(tile.y * 8 + 4);
        label.fontSize = 0.8F;
        label.text = text;
        label.duration = time;
        label.add();
    }
}
