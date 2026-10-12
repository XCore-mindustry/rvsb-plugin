package net.voiddustry.redvsblue.util;

import arc.Events;
import arc.struct.IntIntMap;
import arc.struct.IntSet;
import arc.struct.ObjectMap;
import mindustry.Vars;
import mindustry.game.EventType;
import mindustry.gen.Call;
import mindustry.gen.MenuChooseCallPacket;
import mindustry.net.NetConnection;

/**
 * Drop-in replacement for {@code Call.menu} that ignores answers to menus the player was never shown.
 *
 * <p>Menu listeners are global, so a modified client can answer any registered menu id, for example another
 * player's workbench menu. Menus shown through here are remembered per player, and an answer to one of them is
 * only passed on if it was shown to that player, is still open and the option exists.
 */
public final class MenuGuard {
    /** menu ids shown through this class; answers to other menus are passed through untouched */
    private static final IntSet guarded = new IntSet();
    /** uuid -> open menu id -> button count */
    private static final ObjectMap<String, IntIntMap> open = new ObjectMap<>();

    static {
        Vars.net.handleServer(MenuChooseCallPacket.class, MenuGuard::receive);
        Events.on(EventType.PlayerLeave.class, e -> open.remove(e.player.uuid()));
    }

    private MenuGuard() {}

    /** Same as {@code Call.menu}. */
    public static void menu(NetConnection con, int menuId, String title, String message, String[][] options) {
        if (con != null) {
            int buttons = 0;
            for (String[] row : options) buttons += row.length;
            guarded.add(menuId);
            open.get(con.uuid, IntIntMap::new).put(menuId, buttons);
        }
        Call.menu(con, menuId, title, message, options);
    }

    private static void receive(NetConnection con, MenuChooseCallPacket packet) {
        if (guarded.contains(packet.menuId)) {
            IntIntMap menus = open.get(con.uuid);
            int buttons = menus == null ? -1 : menus.remove(packet.menuId, -1);
            // not shown to this player, already answered, or a button that doesn't exist
            if (buttons == -1 || packet.option < -1 || packet.option >= buttons) return;
        }
        packet.handleServer(con);
    }
}
