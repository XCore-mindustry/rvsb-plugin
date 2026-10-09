package net.voiddustry.redvsblue.evolution;

import arc.Events;
import arc.util.Scaling;
import arc.util.Timer;
import mindustry.Vars;
import mindustry.game.EventType;
import mindustry.game.Team;
import mindustry.gen.Call;
import mindustry.gen.Player;
import mindustry.type.UnitType;
import mindustry.ui.Menus;
import mindustry.ui.builder.MenuBuilder;
import mindustry.ui.builder.MenuResult;
import mindustry.ui.builder.UiBuilder.ButtonTableBuilder;
import mindustry.ui.builder.UiBuilder.ImageBuilder;
import mindustry.ui.builder.UiBuilder.NodeBuilder;
import mindustry.ui.builder.UiBuilder.StackBuilder;
import mindustry.ui.builder.UiBuilder.TableBuilder;
import net.voiddustry.redvsblue.Bundle;
import net.voiddustry.redvsblue.PlayerData;
import net.voiddustry.redvsblue.evolution.EvolutionTree.Edge;
import net.voiddustry.redvsblue.evolution.EvolutionTree.Node;
import net.voiddustry.redvsblue.evolution.EvolutionTree.Tree;
import net.voiddustry.redvsblue.game.stations.Laboratory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

import static mindustry.ui.builder.UiBuilder.*;
import static net.voiddustry.redvsblue.RedVsBluePlugin.players;

/**
 * Evolution menu drawn as unit trees with the server UI builder system (build 160+).
 *
 * <p>Affordable evolutions are framed in accent yellow, too expensive ones in red (not clickable).
 * Evolving takes a double click: the first click selects (green frame) for {@link #SELECT_TIMEOUT}
 * seconds, a second click on the same unit within that time evolves. The menu stays open and
 * rebuilds in place; it is closed with the dialog's back button or the escape/back key.
 */
public final class EvolutionMenu {

    // layout, in UI units
    static final float HALF = 40f;      // half of a leaf column
    static final float LINK_H = 16f;    // connector row height (sprites are 80x32 = 2x)
    static final float ICON = 40f;
    static final float TEXT_H = 16f;

    // colours (hex, no #)
    static final String C_ACCENT = "ffd37f";      // Pal.accent, same as [accent]
    static final String C_SELECTED = "38d667";    // Color.green, same as [green]
    static final String C_EXPENSIVE = "e55454";   // Pal.remove
    static final String C_CURRENT = "c8c8c8";
    static final String C_LOCKED = "333333";
    static final String C_LOCKED_ICON = "5a5a5a";
    static final String C_LINK = "6a6a6a";

    static final String ROOT_ID = "evo-root";
    static final String RESULT_PREFIX = "evo:";

    // connector sprite flags (see tools/gen_evo_links.py)
    static final int TL = 1, TR = 2, BL = 4, BR = 8, H = 16;

    /** seconds a first click stays selected before it is dropped */
    static final float SELECT_TIMEOUT = 1f;

    /** A first click. Compared by identity so an old expiry task can't clear a newer selection. */
    private static final class Selection {
        final String unit;

        Selection(String unit) {
            this.unit = unit;
        }
    }

    /** uuid -> pending first click */
    private static final Map<String, Selection> selections = new ConcurrentHashMap<>();
    private static final Map<Tree, Geometry> geometry = new IdentityHashMap<>();

    private static int menuId = -1;

    private EvolutionMenu() {}

    /** Registers the menu handler. Safe to call more than once. */
    public static synchronized void init() {
        if (menuId != -1) return;
        menuId = Menus.registerMenuBuilder(EvolutionMenu::handle);
        EvolutionSprites.init();
        Events.on(EventType.PlayerLeave.class, e -> selections.remove(e.player.uuid()));
    }

    // ------------------------------------------------------------------ game side

    /** Opens the evolution tree menu. The player must be standing near a laboratory. */
    public static void open(Player player) {
        init();
        PlayerData data = players.get(player.uuid());
        if (data == null || player.unit() == null || player.unit().dead()) return;

        if (!data.isCanEvolve()) return;

        Locale locale = Bundle.findLocale(player.locale());

        selections.remove(player.uuid());
        EvolutionSprites.sendTextures(player);
        // the root node's children are built into the dialog, so the pane must be a child
        MenuBuilder.of(table().add(pane().id(ROOT_ID).grow().add(build(viewFor(player, data, locale)))))
                .id(menuId)
                .title(Bundle.get("menu.evolution.title", locale))
                .hideOnClick(false)
                .show(player);
    }

    /** Rebuilds the open menu in place (keeps the dialog and its scroll position). */
    public static void refresh(Player player) {
        PlayerData data = players.get(player.uuid());
        if (data == null) return;
        Locale locale = Bundle.findLocale(player.locale());
        MenuBuilder.of(table().add(build(viewFor(player, data, locale))))
                .id(menuId)
                .update(player, ROOT_ID);
    }

    private static void handle(Player player, MenuResult result) {
        if (result.wasCancelled()) {
            selections.remove(player.uuid());
            return;
        }
        if (result.result == null || !result.result.startsWith(RESULT_PREFIX)) return;

        String target = result.result.substring(RESULT_PREFIX.length());
        PlayerData data = players.get(player.uuid());
        if (data == null || player.unit() == null || player.unit().dead() || player.team() != Team.blue) {
            selections.remove(player.uuid());
            Call.hideMenuBuilder(player.con, menuId);
            return;
        }

        if (!data.isCanEvolve()) {
            selections.remove(player.uuid());
            refresh(player);
            return;
        }

        if (!availableFor(player.unit().type.name).contains(target)) {
            // stale click (unit changed since the menu was built)
            selections.remove(player.uuid());
            refresh(player);
            return;
        }

        Evolution evolution = Evolutions.evolutions.get(target);
        int cost = (int) (evolution.cost * Laboratory.getMultiplier(evolution, player));
        if (cost > data.getScore()) {
            // price went up / balance went down since the menu was built
            selections.remove(player.uuid());
            player.sendMessage(Bundle.get("evolution.not-enough", Bundle.findLocale(player.locale())));
            refresh(player);
            return;
        }

        Selection selection = selections.get(player.uuid());
        if (selection != null && selection.unit.equals(target)) {
            selections.remove(player.uuid());
            Laboratory.evolve(player, target);
        } else {
            Selection next = new Selection(target);
            selections.put(player.uuid(), next);
            Timer.schedule(() -> expire(player, next), SELECT_TIMEOUT);
        }
        refresh(player);
    }

    /** Drops a selection that wasn't confirmed in time (runs on the main thread). */
    private static void expire(Player player, Selection selection) {
        if (selections.remove(player.uuid(), selection) && player.con != null && player.con.isConnected()) {
            refresh(player);
        }
    }

    private static Set<String> availableFor(String unitName) {
        Set<String> set = new HashSet<>();
        Evolution current = Evolutions.evolutions.get(unitName);
        if (current != null) {
            for (String s : current.evolutions) {
                if (Evolutions.evolutions.get(s) != null) set.add(s);
            }
        }
        return set;
    }

    private static View viewFor(Player player, PlayerData data, Locale locale) {
        View v = new View();
        v.current = player.unit() != null ? player.unit().type.name : "";
        v.available = availableFor(v.current);
        Selection selection = selections.get(player.uuid());
        v.selected = selection != null ? selection.unit : null;
        v.balance = data.getScore();
        v.currentText = Bundle.get("menu.evolution.current", locale);
        v.header = Bundle.format("menu.evolution.header", locale, String.valueOf(data.getScore()));
        v.hint = Bundle.get("menu.evolution.hint", locale);

        v.icon = name -> EvolutionSprites.icon(name).region();
        v.iconPlaceholder = name -> EvolutionSprites.icon(name).placeholder();
        v.displayName = name -> {
            UnitType type = Vars.content.unit(name);
            return type != null ? type.localizedName : name;
        };
        v.price = name -> {
            Evolution evolution = Evolutions.evolutions.get(name);
            if (evolution == null) return null;
            float multiplier = Laboratory.getMultiplier(evolution, player);
            int cost = (int) (evolution.cost * multiplier);
            return new Price(cost, priceColor(multiplier, cost, evolution.cost));
        };
        return v;
    }

    /** Same colour rules the old text menu used. */
    public static String priceColor(float multiplier, int cost, int baseCost) {
        if (multiplier > 1 && multiplier <= 1.99f) return "[orange]";
        if (cost > baseCost) return "[red]";
        if (cost < baseCost) return "[green]";
        return "[yellow]";
    }

    // ------------------------------------------------------------------ UI building (no game state)

    /** Current price of a unit and its colour markup. */
    public record Price(int cost, String color) {}

    /** Everything the builder needs to know about one player's view. */
    public static final class View {
        public String current = "";
        /** evolutions of the current unit (affordable or not) */
        public Set<String> available = new HashSet<>();
        public String selected;
        public int balance;
        public String currentText = "CURRENT";
        public String header = "";
        public String hint = "";
        public Function<String, String> icon = n -> "unit-" + n + "-ui";
        public Function<String, String> iconPlaceholder = n -> n;
        public Function<String, String> displayName = n -> n;
        /** null if the unit has no evolution data */
        public Function<String, Price> price = n -> new Price(0, "[yellow]");

        private final Map<String, Price> prices = new HashMap<>();

        Price priceOf(String unit) {
            return prices.computeIfAbsent(unit, price);
        }

        boolean canAfford(String unit) {
            Price p = priceOf(unit);
            return p != null && p.cost() <= balance;
        }
    }

    /** Builds the menu content: header, hint, and every tree side by side. */
    public static TableBuilder build(View v) {
        TableBuilder trees = table();
        for (Tree tree : EvolutionTree.trees()) {
            trees.add(buildTree(tree, v).align("top").padLeft(10f).padRight(10f));
        }

        return table()
                .add(label(v.header).padBottom(2f)).row()
                .add(label(v.hint).padBottom(10f)).row()
                .add(trees);
    }

    private static TableBuilder buildTree(Tree tree, View v) {
        Geometry g = geometry(tree);
        int cols = tree.width * 2;
        // every single half-cell is HALF wide; multi-column cells set their own width
        TableBuilder t = table().add(defaults().width(HALF));

        t.add(label("[lightgray]" + v.displayName.apply(tree.root))
                .labelAlign("center").colspan(cols).width(HALF * cols).padBottom(4f)).row();

        for (int r = -1; r <= tree.maxRow; r++) {
            // node row
            for (int i = 0; i < cols; ) {
                Node n = tree.nodeStartingAt(r, i / 2);
                if (n != null && i % 2 == 0) {
                    t.add(nodeCell(n, v).colspan(n.span * 2).width(HALF * n.span * 2));
                    i += n.span * 2;
                    continue;
                }
                Cell pass = g.pass(r, i);
                if (pass != null) {
                    t.add(linkCell(pass, v).fillY());
                    i++;
                    continue;
                }
                int j = i + 1;
                while (j < cols && g.pass(r, j) == null && !(j % 2 == 0 && tree.nodeStartingAt(r, j / 2) != null)) j++;
                t.add(space().colspan(j - i).width(HALF * (j - i)));
                i = j;
            }
            t.row();

            if (r == tree.maxRow) break;

            // connector row below it
            for (int i = 0; i < cols; ) {
                Cell link = g.link(r, i);
                if (link != null) {
                    t.add(linkCell(link, v).height(LINK_H));
                    i++;
                    continue;
                }
                int j = i + 1;
                while (j < cols && g.link(r, j) == null) j++;
                t.add(space().colspan(j - i).width(HALF * (j - i)).height(LINK_H));
                i = j;
            }
            t.row();
        }
        return t;
    }

    private static TableBuilder nodeCell(Node n, View v) {
        boolean current = n.unit.equals(v.current);
        boolean option = v.available.contains(n.unit);
        boolean available = option && v.canAfford(n.unit);
        boolean tooExpensive = option && !available;
        boolean selected = available && n.unit.equals(v.selected);

        String frame = selected ? C_SELECTED
                : available ? C_ACCENT
                : tooExpensive ? C_EXPENSIVE
                : current ? C_CURRENT
                : C_LOCKED;

        ImageBuilder icon = image(v.icon.apply(n.unit))
                .placeholder(v.iconPlaceholder.apply(n.unit))
                .scaling(Scaling.fit)
                .size(ICON);
        if (!option && !current) icon.color(C_LOCKED_ICON);

        ButtonTableBuilder button = buttonTable().style("flatt").margin(4f).add(icon);
        if (available) {
            button.clicked(RESULT_PREFIX + n.unit);
        } else {
            button.disabled(true);
        }

        return table()
                .add(label(current ? "[accent]" + v.currentText : "").labelAlign("center").height(TEXT_H)).row()
                .add(table().background("whiteui").margin(3f).add(button).color(frame)).row()
                .add(label(priceText(v.priceOf(n.unit))).labelAlign("center").height(TEXT_H));
    }

    private static String priceText(Price price) {
        return price == null ? "[gray]?" : price.color() + price.cost();
    }

    private static final String[] LINK_COLORS = {C_LINK, C_EXPENSIVE, C_ACCENT, C_SELECTED};

    /** 0 idle, 1 too expensive, 2 affordable, 3 selected. */
    private static int edgeRank(Edge e, View v) {
        if (!e.from.unit.equals(v.current) || !v.available.contains(e.to.unit)) return 0;
        if (!v.canAfford(e.to.unit)) return 1;
        return e.to.unit.equals(v.selected) ? 3 : 2;
    }

    /**
     * One connector half-cell. Each line segment takes the colour of the highest-ranked edge using it;
     * if segments in the cell end up with different colours, they are layered in a stack.
     */
    private static NodeBuilder<?> linkCell(Cell cell, View v) {
        int[] bitsByRank = new int[LINK_COLORS.length];
        for (int bit = TL; bit <= H; bit <<= 1) {
            int rank = -1;
            for (Map.Entry<Edge, Integer> entry : cell.edgeFlags.entrySet()) {
                if ((entry.getValue() & bit) != 0) rank = Math.max(rank, edgeRank(entry.getKey(), v));
            }
            if (rank >= 0) bitsByRank[rank] |= bit;
        }

        List<ImageBuilder> layers = new ArrayList<>();
        for (int rank = 0; rank < bitsByRank.length; rank++) {  // highlighted layers drawn last
            if (bitsByRank[rank] != 0) layers.add(image(EvolutionSprites.LINK_REGION + bitsByRank[rank])
                    .placeholder(EvolutionSprites.LINK_PLACEHOLDER).color(LINK_COLORS[rank]));
        }
        if (layers.size() == 1) return layers.get(0);

        StackBuilder stack = stack();
        layers.forEach(stack::add);
        return stack;
    }

    // ------------------------------------------------------------------ connector geometry

    /** One half-cell of connector drawing: which sprite segments each edge uses here. */
    static final class Cell {
        final Map<Edge, Integer> edgeFlags = new LinkedHashMap<>();

        void add(int flag, Edge edge) {
            edgeFlags.merge(edge, flag, (a, b) -> a | b);
        }
    }

    /** Connector cells of a tree, indexed by row (offset by 1 for the tier-1 row) and half-cell. */
    static final class Geometry {
        final Cell[][] links;   // links[r + 1][i] = connector row between node rows r and r + 1
        final Cell[][] passes;  // passes[r + 1][i] = vertical pass-through inside node row r

        Geometry(int rows, int cols) {
            links = new Cell[rows][cols];
            passes = new Cell[rows][cols];
        }

        Cell link(int row, int i) {
            return links[row + 1][i];
        }

        Cell pass(int row, int i) {
            return passes[row + 1][i];
        }
    }

    static synchronized Geometry geometry(Tree tree) {
        return geometry.computeIfAbsent(tree, EvolutionMenu::computeGeometry);
    }

    private static Geometry computeGeometry(Tree tree) {
        int cols = tree.width * 2;
        Geometry g = new Geometry(tree.maxRow + 2, cols);

        for (Edge e : tree.edges) {
            int pr = e.from.row, cr = e.to.row;
            int pc = e.from.center(), cc = e.to.center();

            // straight down through any skipped rows, then over to the child on the last gap
            for (int r = pr; r < cr - 1; r++) {
                vertical(g.links[r + 1], cols, pc, true, e);
                vertical(g.links[r + 1], cols, pc, false, e);
                vertical(g.passes[r + 2], cols, pc, true, e);
                vertical(g.passes[r + 2], cols, pc, false, e);
            }
            Cell[] last = g.links[cr];
            vertical(last, cols, pc, true, e);
            for (int i = Math.min(pc, cc); i < Math.max(pc, cc); i++) cell(last, i).add(H, e);
            vertical(last, cols, cc, false, e);
        }

        // nodes always win over pass-through lines
        for (Node n : tree.nodes) {
            for (int i = n.col * 2; i < (n.col + n.span) * 2; i++) g.passes[n.row + 1][i] = null;
        }
        return g;
    }

    /** Vertical line on half-cell boundary {@code b}: right edge of cell b-1 and left edge of cell b. */
    private static void vertical(Cell[] row, int cols, int b, boolean top, Edge e) {
        if (b - 1 >= 0) cell(row, b - 1).add(top ? TR : BR, e);
        if (b < cols) cell(row, b).add(top ? TL : BL, e);
    }

    private static Cell cell(Cell[] row, int i) {
        if (row[i] == null) row[i] = new Cell();
        return row[i];
    }

    /** For tests/debugging: the menu content as DSL text. */
    public static String toDsl(View v) {
        NodeBuilder<?> node = build(v);
        return node.toString();
    }
}
