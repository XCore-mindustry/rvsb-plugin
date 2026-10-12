package net.voiddustry.redvsblue.game.crux;

import arc.struct.ObjectMap;
import mindustry.gen.Player;
import mindustry.type.UnitType;
import mindustry.ui.Menus;
import net.voiddustry.redvsblue.Bundle;
import net.voiddustry.redvsblue.RedVsBluePlugin;
import net.voiddustry.redvsblue.util.MenuGuard;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;


public class ClassChooseMenu {

    public static final ObjectMap<UnitType, String> units = new ObjectMap<>();
    public static final Map<String, UnitType> selectedUnit = new HashMap<>();

    public static void openMenu(Player player) {
        String[][] unitsBundleKeys = new String[units.size][1];
        UnitType[] unitsNumbers = new UnitType[units.size];

        AtomicInteger i = new AtomicInteger();

        units.forEach((object) -> {
            unitsBundleKeys[i.get()][0] = Bundle.get(object.value, player.locale);
            unitsNumbers[i.get()] = object.key;
            i.addAndGet(1);
        });

        int menu = Menus.registerMenu((playerInMenu, option) -> {
            if (option < 0 || option >= unitsNumbers.length) {
                return;
            }

            selectedUnit.put(playerInMenu.uuid(), unitsNumbers[option]);
            CruxUnit.callSpawn(playerInMenu);
        });

        MenuGuard.menu(player.con, menu, Bundle.get("units.crux.menu.title", player.locale), "", unitsBundleKeys);
    }

    public static void updateUnitsMap() {
        units.clear();
        units.putAll(StageUnits.unitsForStage(RedVsBluePlugin.stage));
    }
}
