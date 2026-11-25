package net.voiddustry.redvsblue.game.crux;

import arc.util.Timer;
import mindustry.Vars;
import mindustry.content.Blocks;
import mindustry.game.Team;
import mindustry.gen.Call;
import mindustry.gen.Groups;
import mindustry.gen.Player;
import mindustry.gen.Unit;
import mindustry.world.Tile;
import net.voiddustry.redvsblue.Bundle;
import net.voiddustry.redvsblue.PlayerData;
import net.voiddustry.redvsblue.RedVsBluePlugin;
import net.voiddustry.redvsblue.game.stats.MapStats;
import net.voiddustry.redvsblue.util.Utils;

import java.util.HashSet;

/**
 * Blue player death flow. Before stage 4 the player waits {@link #DELAY} seconds and respawns as blue;
 * from stage 4 the same wait ends with a crux conversion, so red is only reachable after stage 3.
 */
public final class BlueRespawn {
    /** Seconds a dead blue player waits before respawning. */
    public static final float DELAY = 10f;

    /** UUIDs of blue players waiting for their respawn. */
    private static final HashSet<String> waiting = new HashSet<>();
    /** UUIDs of blue players returned from crux by the before-stage-4 watchdog (not a team-wipe wait). */
    private static final HashSet<String> returning = new HashSet<>();

    private static boolean started;

    private BlueRespawn() {
    }

    /** Starts the watchdog that catches players put into crux before red is unlocked (e.g. by an external /suicide). */
    public static synchronized void init() {
        if (started) return;
        started = true;

        Timer.schedule(() -> {
            if (!RedVsBluePlugin.playing || Utils.gameover || RedVsBluePlugin.stage > 3) return;
            for (Player player : Groups.player) {
                if (player.team() == Team.crux && player.con != null && player.con.isConnected()) {
                    returnToBlue(player);
                }
            }
        }, 1f, 0.5f);
    }

    /** Called when a blue player's unit dies, or when they use /suicide. */
    public static void onDeath(Player player) {
        if (player == null || !RedVsBluePlugin.playing || Utils.gameover || player.team() != Team.blue) return;

        if (RedVsBluePlugin.stage >= 4) {
            // after stage 3 death is an immediate crux conversion, no respawn wait
            boolean alreadyRecorded = waiting.remove(player.uuid());
            CruxUnit.convertToCrux(player, !alreadyRecorded);
            RedVsBluePlugin.gameOverCheck();
            return;
        }

        if (!waiting.add(player.uuid())) return; // already waiting

        PlayerData data = RedVsBluePlugin.players.get(player.uuid());
        Unit unit = data != null && data.getUnit() != null ? data.getUnit() : player.unit();
        MapStats.onBlueUnitDeath(player, unit);

        player.clearUnit();
        if (data != null) data.setUnit(null);

        Timer.schedule(() -> finish(player.uuid()), DELAY);
        RedVsBluePlugin.gameOverCheck();

        // if that was the last blue player, the game is already over; don't promise a respawn
        if (!Utils.gameover) {
            player.sendMessage(Bundle.format("game.respawn-wait", Bundle.findLocale(player.locale),
                    String.valueOf((int) DELAY)));
        }
    }

    /** Moves a player found in crux before stage 4 back to blue; they get a unit after {@link #DELAY}. */
    public static void returnToBlue(Player player) {
        if (player == null || !RedVsBluePlugin.playing || Utils.gameover
                || RedVsBluePlugin.stage > 3 || player.team() != Team.crux) return;

        String uuid = player.uuid();
        if (waiting.contains(uuid)) {
            // external command again during the wait: keep them off red, keep the original timer
            returning.add(uuid);
            clearCruxUnit(player);
            player.team(Team.blue);
            return;
        }

        waiting.add(uuid);
        returning.add(uuid);
        clearCruxUnit(player);
        CruxUnit.resetPossession(player);
        ClassChooseMenu.selectedUnit.remove(uuid);
        player.team(Team.blue);
        RedVsBluePlugin.players.put(uuid, new PlayerData(player));

        player.sendMessage(Bundle.format("game.respawn-wait", Bundle.findLocale(player.locale),
                String.valueOf((int) DELAY)));
        Timer.schedule(() -> finish(uuid), DELAY);
    }

    private static void clearCruxUnit(Player player) {
        Unit old = player.unit();
        player.clearUnit();
        if (old != null && old.spawnedByCore) Call.unitDespawn(old);
    }

    /** @return true when the player was converted to crux; false when a blue respawn was scheduled. */
    public static boolean resume(Player player) {
        if (player == null || !RedVsBluePlugin.playing || Utils.gameover || player.team() != Team.blue) return false;
        PlayerData data = RedVsBluePlugin.players.get(player.uuid());
        if (data != null && data.getUnit() != null) {
            if (!data.getUnit().dead) return false;
            onDeath(player);
            return player.team() == Team.crux;
        }

        if (RedVsBluePlugin.stage >= 4) {
            CruxUnit.convertToCrux(player, true);
            RedVsBluePlugin.gameOverCheck();
            return player.team() == Team.crux;
        }

        if (waiting.add(player.uuid())) {
            player.sendMessage(Bundle.format("game.respawn-wait", Bundle.findLocale(player.locale),
                    String.valueOf((int) DELAY)));
            Timer.schedule(() -> finish(player.uuid()), DELAY);
        }
        return false;
    }

    private static void finish(String uuid) {
        // claiming the entry also makes timers from a previous map/game harmless
        if (!waiting.remove(uuid)) {
            returning.remove(uuid);
            return;
        }
        returning.remove(uuid);

        try {
            if (!RedVsBluePlugin.playing || Utils.gameover) return;
            Player player = Groups.player.find(p -> p.uuid().equals(uuid));
            if (player == null || player.con == null || !player.con.isConnected() || player.team() != Team.blue) return;

            if (RedVsBluePlugin.stage <= 3) {
                respawnBlue(player);
            } else {
                // the death was already recorded in onDeath
                CruxUnit.convertToCrux(player, false);
            }
        } finally {
            if (RedVsBluePlugin.playing && !Utils.gameover) RedVsBluePlugin.gameOverCheck();
        }
    }

    private static void respawnBlue(Player player) {
        RedVsBluePlugin.players.put(player.uuid(), new PlayerData(player));
        PlayerData data = RedVsBluePlugin.players.get(player.uuid());

        Tile tile = Vars.world.tile(((int) RedVsBluePlugin.blueSpawnX) / 8, ((int) RedVsBluePlugin.blueSpawnY) / 8);
        if (tile != null) tile.setNet(Blocks.air, Team.derelict, 0);

        Unit unit = Utils.getStartingUnit().spawn(Team.blue, RedVsBluePlugin.blueSpawnX, RedVsBluePlugin.blueSpawnY);
        data.setUnit(unit);
        Utils.assignUnit(player, unit);
        if (Utils.gameRun) MapStats.onPick(player, unit.type);
    }

    /** Drops pending respawns on map load / game over. */
    public static void clear() {
        waiting.clear();
        returning.clear();
    }

    /** True while a player is being returned from an illegal red team. */
    public static boolean hasReturning() {
        return !returning.isEmpty();
    }

    /** True when every connected blue player is waiting; that is a loss, not a reason to wait for respawns. */
    public static boolean allBlueWaiting() {
        if (waiting.isEmpty() || !returning.isEmpty()) return false;

        boolean anyBlue = false;
        for (Player player : Groups.player) {
            if (player.team() != Team.blue) continue;
            anyBlue = true;
            if (!waiting.contains(player.uuid())) return false;
        }
        return anyBlue;
    }
}
