package net.voiddustry.redvsblue.game.stations;

import mindustry.gen.WorldLabel;
import mindustry.world.Tile;

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
