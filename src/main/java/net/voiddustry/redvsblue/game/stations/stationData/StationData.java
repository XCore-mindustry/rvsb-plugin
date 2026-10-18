package net.voiddustry.redvsblue.game.stations.stationData;

import mindustry.gen.Player;
import mindustry.gen.WorldLabel;
import mindustry.world.Tile;
import net.voiddustry.redvsblue.util.AuraCircle;

public record StationData(Player owner, Tile tileOn, WorldLabel label, AuraCircle circle) {
    public StationData(Player owner, Tile tileOn) {
        this(owner, tileOn, null, null);
    }

    public StationData(Player owner, Tile tileOn, WorldLabel label) {
        this(owner, tileOn, label, null);
    }

    public void destroy() {
        if (label != null) {
            label.hide();
        }
        if (circle != null) {
            circle.hide();
        }
    }
}
