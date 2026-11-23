package net.voiddustry.redvsblue.game.crux;

import mindustry.Vars;
import mindustry.gen.Player;
import mindustry.type.UnitType;
import mindustry.ui.Menus;
import mindustry.ui.builder.MenuBuilder;
import mindustry.ui.builder.UiBuilder.TableBuilder;
import net.voiddustry.redvsblue.Bundle;

import java.util.Locale;

import static mindustry.ui.builder.UiBuilder.*;

/** Small player-facing window with the current balancer tier. Opened with /balancer. */
public final class BalancerMenu {
    private static final String C_LABEL = "bfbfbfff";
    private static final String C_VALUE = "ffffffff";
    private static final String C_TIER = "ffd37fff";
    private static final String C_RANGE = "c8c8c8ff";

    private static int menuId = -1;

    private BalancerMenu() {
    }

    /**
     * Reserves a menu id. There are no buttons, but closing the dialog still sends a (cancelled) result,
     * and that must not land on some other mod's listener that happens to own id 0.
     */
    public static synchronized void init() {
        if (menuId != -1) return;
        menuId = Menus.registerMenuBuilder((player, result) -> {
        });
    }

    public static void open(Player player) {
        init();
        Locale locale = Bundle.findLocale(player.locale);
        MenuBuilder.of(build(locale, StrengthSpawner.view()))
                .id(menuId)
                .title(Bundle.get("menu.balancer.title", locale))
                .fillScreen(false)
                .show(player);
    }

    /** The full dialog body; separated from {@link #open} so the layout can be built without a player. */
    static TableBuilder build(Locale locale, StrengthSpawner.View view) {
        return table().add(content(locale, view).width(640f).minHeight(320f).pad(16f));
    }

    private static TableBuilder content(Locale locale, StrengthSpawner.View view) {
        TableBuilder root = table();

        root.add(label(Bundle.get("menu.balancer.map", locale)).color(C_LABEL).padRight(12f).padBottom(3f))
                .add(label(view.map().isEmpty() ? "—" : view.map()).color(C_VALUE).padBottom(3f)).row();
        root.add(label(Bundle.get("menu.balancer.multiplier", locale)).color(C_LABEL).padRight(12f).padBottom(3f))
                .add(label("[accent]x" + coef(view.mapMultiplier()) + "[]").padBottom(3f)).row();
        root.add(label(Bundle.get("menu.balancer.stage", locale)).color(C_LABEL).padRight(12f).padBottom(3f))
                .add(label("[scarlet]" + view.stage() + "[] [darkgray]/ " + view.finalStage() + "[]").padBottom(3f)).row();
        root.add(label(Bundle.get("menu.balancer.blue", locale)).color(C_LABEL).padRight(12f))
                .add(label("[royal]" + view.bluePlayers() + "[] " + Bundle.get("menu.balancer.players", locale)
                        + " · [royal]" + view.blueUnits() + "[] " + Bundle.get("menu.balancer.units", locale))).row();

        root.add(label(Bundle.get("menu.balancer.spawners", locale)).color(C_TIER)
                .colspan(2).padTop(18f).padBottom(6f)).row();

        if (view.spawners().isEmpty()) {
            root.add(label(Bundle.get("menu.balancer.none", locale)).color(C_LABEL)
                    .colspan(2).padTop(4f)).row();
        } else {
            for (StrengthSpawner.SpawnerView spawner : view.spawners()) {
                root.add(label(spawnerLine(spawner, locale)).colspan(2).padTop(14f)).row();
                if (spawner.tier() > 0) {
                    root.add(label(range(spawner.min(), spawner.max()) + cooldownText(spawner, locale))
                            .color(C_RANGE).colspan(2).padTop(3f)).row();
                    if (!spawner.spawns().isEmpty()) {
                        root.add(label(spawnsText(spawner, locale)).colspan(2).padTop(3f).width(600f).wrap()).row();
                    }
                }
                root.add(label(unitsText(spawner, locale)).colspan(2).padTop(3f).width(600f).wrap()).row();
            }
        }

        return root;
    }

    private static String spawnerLine(StrengthSpawner.SpawnerView spawner, Locale locale) {
        String tier;
        if (spawner.tier() <= 0) {
            tier = "[gray]" + Bundle.get("menu.balancer.no-tier", locale) + "[]";
        } else {
            tier = "[accent]" + Bundle.format("menu.balancer.tier", locale,
                    String.valueOf(spawner.tier()), String.valueOf(spawner.tierCount())) + "[]";
        }
        return "[white]" + spawner.name() + "[]   " + tier
                + "   [lightgray]HP " + num(spawner.value()) + "[]";
    }

    private static String spawnsText(StrengthSpawner.SpawnerView spawner, Locale locale) {
        StringBuilder b = new StringBuilder("[gray]")
                .append(Bundle.get("menu.balancer.spawns", locale)).append("[] ");
        boolean first = true;
        for (StrengthSpawner.SpawnUnitView spawn : spawner.spawns()) {
            if (!first) b.append("[gray], []");
            first = false;
            UnitType type = Vars.content.unit(spawn.name());
            String name = type == null ? spawn.name() : Bundle.contentName(type, locale);
            b.append("[white]").append(name).append("[]");
            if (spawn.count() > 1) b.append("[gray] x").append(spawn.count()).append("[]");
        }
        return b.toString();
    }

    private static String unitsText(StrengthSpawner.SpawnerView spawner, Locale locale) {
        String prefix = "[gray]" + Bundle.get("menu.balancer.counted", locale) + "[] ";
        if (spawner.units().isEmpty()) {
            return prefix + "[gray]" + Bundle.get("menu.balancer.no-units", locale) + "[]";
        }

        StringBuilder b = new StringBuilder(prefix);
        boolean first = true;
        for (StrengthSpawner.UnitView unit : spawner.units()) {
            if (!first) b.append("[gray], []");
            first = false;
            UnitType type = Vars.content.unit(unit.name());
            String name = type == null ? unit.name() : Bundle.contentName(type, locale);
            b.append("[white]").append(name).append("[] [gray]x").append(unit.count())
                    .append(" (").append(coef(unit.weight())).append(")[]");
        }
        return b.toString();
    }

    private static String range(float min, float max) {
        return num(min) + " – " + num(max);
    }

    private static String cooldownText(StrengthSpawner.SpawnerView spawner, Locale locale) {
        return "   [gray]· " + Bundle.format("menu.balancer.cooldown", locale,
                String.valueOf(spawner.cooldown())) + "[]";
    }

    /** One decimal minimum, at most two, e.g. 1.0 / 0.8 / 1.25. */
    private static String coef(float value) {
        String text = String.format(Locale.ROOT, "%.2f", value).replaceAll("0+$", "");
        return text.endsWith(".") ? text + "0" : text;
    }

    private static String num(float value) {
        if (value >= Float.MAX_VALUE / 2f) return "inf";
        return String.format(Locale.ROOT, "%,d", Math.round(value)).replace(',', ' ');
    }
}
