package net.voiddustry.redvsblue.game.units;

import arc.util.Log;
import arc.util.Timer;
import net.voiddustry.redvsblue.game.units.special.Quad;
import net.voiddustry.redvsblue.game.units.special.Serverminate;

public class SpecialUnits {
    public static void init() {
        Log.info("&gStarting init units specs");

        Timer.schedule(Quad::initAbilities, 1, 0.5f);
        Serverminate.init();
    }
}
