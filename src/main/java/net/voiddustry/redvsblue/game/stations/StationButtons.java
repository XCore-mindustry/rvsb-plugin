package net.voiddustry.redvsblue.game.stations;

import arc.Events;
import arc.util.Timer;
import mindustry.Vars;
import mindustry.game.EventType;
import mindustry.gen.Building;
import mindustry.gen.Player;

import java.util.HashMap;
import java.util.Map;

/** Handles configurable station buttons defined by the server content assets. */
public final class StationButtons {
    private static final long menuCooldownMillis = 1_000L;
    private static final Map<String, Long> menuCooldowns = new HashMap<>();

    private StationButtons() {
    }

    public static void init() {
        Events.on(EventType.ConfigEvent.class, event -> {
            // SwitchBuild starts enabled. A player click toggles it off; that off transition is the button press.
            if (!(event.value instanceof Boolean enabled) || enabled || event.player == null || !isStationButton(event.tile)) {
                return;
            }

            Player player = event.player;
            // Preserve the original station interaction ranges: 48 for labs, 64 for workbenches.
            float stationRange = event.tile.block == Vars.content.block("dp-laboratory-station") ? 48f : 64f;
            // Reject remote presses immediately without opening a menu or starting a cooldown.
            if (player.unit() == null || player.unit().dst(event.tile) > stationRange) {
                event.tile.configure(true);
                return;
            }

            // Valid presses, including cooldown-limited presses, restore after one second.
            Timer.schedule(() -> {
                if (event.tile.isValid() && isStationButton(event.tile)) {
                    event.tile.configure(true);
                }
            }, 1f);

            long now = System.currentTimeMillis();
            Long lastOpen = menuCooldowns.get(player.uuid());
            if (lastOpen != null && now - lastOpen < menuCooldownMillis) return;
            menuCooldowns.put(player.uuid(), now);

            if (event.tile.block == Vars.content.block("dp-laboratory-station")) {
                Laboratory.openStationMenu(player);
            } else if (event.tile.block == Vars.content.block("dp-workbench-station")) {
                ArmorWorkbench.openStationMenu(player, event.tile);
            }
        });
    }

    private static boolean isStationButton(Building building) {
        return building.block == Vars.content.block("dp-laboratory-station") || building.block == Vars.content.block("dp-workbench-station");
    }
}
