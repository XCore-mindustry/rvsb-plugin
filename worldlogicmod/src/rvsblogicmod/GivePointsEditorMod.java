package rvsblogicmod;

import arc.Events;
import arc.util.Log;
import mindustry.game.EventType.ClientLoadEvent;
import mindustry.mod.Mod;

public class GivePointsEditorMod extends Mod {

    public GivePointsEditorMod() {
        Events.on(ClientLoadEvent.class, e -> {
            GivePointsStatement.register();
            Log.info("[rvsb-world-logic-editor-helper] instructions registered in editor.");
        });
    }
}
