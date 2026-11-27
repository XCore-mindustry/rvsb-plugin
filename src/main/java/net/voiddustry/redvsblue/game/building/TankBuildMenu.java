package net.voiddustry.redvsblue.game.building;

import arc.Events;
import arc.util.Timer;
import mindustry.Vars;
import mindustry.game.EventType;
import mindustry.game.Rules;
import mindustry.game.Team;
import mindustry.gen.Call;
import mindustry.gen.Groups;
import mindustry.gen.Player;
import mindustry.type.UnitType;
import mindustry.world.Block;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Shows the conquer turret in the build menu only for players currently piloting a conquer.
 * The server rules keep the block allowed; every other player gets a per-connection copy with
 * the block banned, which hides it from their build menu.
 */
public final class TankBuildMenu {
    private static final Map<String, Boolean> lastTank = new ConcurrentHashMap<>();

    private TankBuildMenu() {
    }

    public static void init() {
        Events.on(EventType.PlayerJoin.class, event ->
                Timer.schedule(() -> sync(event.player, true), 0.5f));

        Events.on(EventType.UnitChangeEvent.class, event ->
                sync(event.player, false));

        Events.on(EventType.PlayerLeave.class, event ->
                lastTank.remove(event.player.uuid()));

        Events.on(EventType.WorldLoadEvent.class, event -> {
            lastTank.clear();
            Timer.schedule(() -> {
                for (Player player : Groups.player) sync(player, true);
            }, 1f);
        });
    }

    /** Re-sends the player's rules immediately (e.g. after the tank unit died). */
    public static void refresh(Player player) {
        sync(player, true);
    }

    private static void sync(Player player, boolean force) {
        if (player == null || player.con == null || !player.con.isConnected()) return;

        UnitType conquer = Vars.content.unit("conquer");
        Block turret = Vars.content.block("dp-conquer-turret");
        if (conquer == null || turret == null) return;

        boolean tank = player.team() == Team.blue
                && player.unit() != null
                && !player.unit().dead
                && player.unit().type == conquer;

        Boolean previous = lastTank.get(player.uuid());
        if (!force && previous != null && previous == tank) return;

        Rules rules;
        if (tank) {
            rules = Vars.state.rules;
        } else {
            rules = Vars.state.rules.copy();
            rules.bannedBlocks.add(turret);
            rules.revealedBlocks.remove(turret);
            rules.hideBannedBlocks = true;
        }

        Call.setRules(player.con, rules);
        lastTank.put(player.uuid(), tank);
    }
}
