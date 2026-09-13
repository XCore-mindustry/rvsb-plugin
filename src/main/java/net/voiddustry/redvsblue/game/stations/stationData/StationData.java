package net.voiddustry.redvsblue.game.stations.stationData;

import mindustry.gen.Player;
import mindustry.gen.WorldLabel;
import mindustry.world.Tile;

public record StationData(Player owner, Tile tileOn, WorldLabel label) {
    public StationData(Player owner, Tile tileOn) {
        this(owner, tileOn, null);
    }

    public void destroy() {
        if (label != null) {
            label.hide();
        }
    }
}