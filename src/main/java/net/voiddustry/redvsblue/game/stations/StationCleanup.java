package net.voiddustry.redvsblue.game.stations;

import mindustry.gen.Player;

/**
 * Breaks every station owned by a player. Called on blue death: after stage 3 deaths no longer
 * remove the player from blue, so stations would otherwise survive their owner forever.
 */
public final class StationCleanup {
    private StationCleanup() {
    }

    /** Idempotent: repeated calls are safe. */
    public static void destroyFor(Player player) {
        if (player == null) return;

        Miner.destroyFor(player);
        RepairPoint.destroyFor(player);
        Laboratory.destroyFor(player);
        Booster.destroyFor(player);
        ArmorWorkbench.destroyFor(player);
        Recycler.destroyFor(player);
        SuppressorTower.destroyFor(player);
    }
}
