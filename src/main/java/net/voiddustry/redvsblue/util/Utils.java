package net.voiddustry.redvsblue.util;

import arc.graphics.Color;
import arc.struct.Seq;
import arc.util.Log;
import arc.util.Time;
import arc.util.Timer;
import mindustry.Vars;
import mindustry.content.*;
import mindustry.game.Team;
import mindustry.gen.*;
import mindustry.type.UnitType;
import mindustry.world.Block;
import mindustry.world.blocks.environment.Prop;
import mindustry.world.blocks.environment.TallBlock;
import net.voiddustry.redvsblue.Bundle;
import net.voiddustry.redvsblue.PlayerData;
import net.voiddustry.redvsblue.game.crux.StageUnits;
import net.voiddustry.redvsblue.game.starting_menu.StartingItems;
import net.voiddustry.redvsblue.game.starting_menu.StartingMenu;
import net.voiddustry.redvsblue.game.stations.*;

import java.util.Locale;

import static mindustry.Vars.*;
import static net.voiddustry.redvsblue.RedVsBluePlugin.*;

public class Utils {

    public static boolean voting;
    public static boolean gameRun;
    public static boolean gameover;
    public static boolean hardcore;
    public static int money_per_min = 3;

    public static void initRules() {
        state.rules.bannedBlocks.clear();
        state.rules.revealedBlocks.clear();

        for (Block block : Vars.content.blocks()) {
            state.rules.bannedBlocks.add(block);

            for (int i = 0; i < block.requirements.length; i++) {
                if (block.requirements[i].item == Items.dormantCyst) {
                    state.rules.bannedBlocks.remove(block);
                    state.rules.revealedBlocks.add(block);
                    break;
                }
            }
        }

        state.rules.buildSpeedMultiplier = 0;

        state.rules.env = Vars.defaultEnv;
        state.rules.planet = Planets.sun;

        state.rules.waveSpacing = Integer.MAX_VALUE;
        state.rules.waves = true;
        state.rules.bannedUnits.add(UnitTypes.alpha);

        state.rules.hideBannedBlocks = true;
        state.rules.blockWhitelist = false;

        state.rules.teams.get(Team.malis).blockHealthMultiplier = 2;

        Call.setRules(state.rules);
    }

    public static void launchGameStartTimer() {
        int[] i = {120};

        Timer.Task task = new Timer.Task() {
            @Override
            public void run() {
                announceBundled("game.game-starts-soon", 1, i[0]);
                i[0]--;

                if (i[0] <= 0) {
                    gameRun = true;
                    this.cancel();
                    StartingMenu.canOpenMenu = false;
                }
            }
        };

        Timer timer = new Timer();
        timer.scheduleTask(task, 0, 1);
    }

    public static void loadContent() {
        StartingItems.load();
    }

    public static void initTimers() {
        Miner.initTimer();
        RepairPoint.initTimer();
        Laboratory.initTimer();
        Booster.initTimer();
        ArmorWorkbench.initTimer();
        Recycler.initTimer();
        SuppressorTower.initTimer();

        Timer.schedule(() -> {
            if (playing) {
                Groups.player.each(player -> {
                    PlayerData data = players.get(player.uuid());

                    if (player.team() == Team.blue && data != null) {
                        data.setScore(data.getScore() + money_per_min);

                        player.sendMessage(
                                Bundle.format(
                                        "game.salary",
                                        Bundle.findLocale(player.locale),
                                        money_per_min
                                )
                        );
                    }
                });
            }
        }, 0, 60);

        Timer.schedule(() -> stageTimer--, 0, 1);


        Timer.schedule(() -> Groups.player.each(player -> {
            Unit unit = player.unit();

            if (unit == null) {
                return;
            }

            if (player.tileOn() == null) {
                damageForUnsafePosition(player);
                return;
            }

            if (player.team() != Team.blue) {
                return;
            }

            Block block = player.tileOn().block();


            if (block == Blocks.cliff
                    || (block instanceof Prop && block.breakable)
                    || block instanceof TallBlock) {
                return;
            }


            if (player.tileOn().build != null
                    && player.tileOn().build.team == player.team()) {
                return;
            }


            if (player.tileOn().build != null
                    && player.tileOn().build.team != Team.blue
                    && player.tileOn().build.team != Team.derelict) {
                damageForUnsafePosition(player);
                return;
            }


            if ((!block.isAir() || player.tileOn().isDarkened())
                    && !block.canBeBuilt()) {
                damageForUnsafePosition(player);
            }
        }), 0, 0.1F);
    }


    private static void damageForUnsafePosition(Player player) {
        Unit unit = player.unit();

        if (unit == null || unit.dead()) {
            return;
        }

        float damage = unit.type.health / 100f;

        if (unit.health <= damage) {
            unit.kill();
        } else {
            unit.health -= damage;
        }

        Call.effect(Fx.burning, player.x, player.y, 1, Color.red);
    }

    public static void processLevel(Player player, PlayerData data) {
        if (data.getLevel() < 5 && data.getExp() >= data.getMaxExp()) {
            int expLimit = data.getExp();
            int expLimitToSet = expLimit + expLimit / 4;

            data.setMaxExp(expLimitToSet);
            data.setExp(0);
            data.setLevel(data.getLevel() + 1);

            sendBundled("game.level-up", player.name);
        }
    }

    public static void label(
            float x,
            float y,
            String text,
            float time,
            float fontsize
    ) {
        WorldLabel label = WorldLabel.create();

        label.x(x);
        label.y(y + 4);
        label.fontSize = fontsize;
        label.text = text;
        label.duration = time;
        label.add();
    }

    public static int getRandomInt(int min, int max) {
        return (int) ((Math.random() * (max - min)) + min);
    }

    public static Player getRandomPlayer(Team team) {
        Seq<Player> playerSeq = new Seq<>();

        Groups.player.each(player -> {
            if (player.team() == team) {
                playerSeq.add(player);
            }
        });

        return playerSeq.isEmpty() ? null : playerSeq.random();
    }

    public static Player getRandomPlayer() {
        if (Groups.player.isEmpty()) {
            return null;
        }

        return Groups.player.index(
                getRandomInt(0, Groups.player.size())
        );
    }

    public static void spawnBoss() {
        UnitType bossType = StageUnits.bossForStage(stage);

        if (bossType == null || redSpawns.isEmpty()) {
            Log.warn(
                    "No configured boss or spawn point for stage @.",
                    stage
            );
            return;
        }

        Unit boss = bossType.spawn(
                Team.crux,
                redSpawns.random()
        );

        boss.health = boss.type.health + boss.type.health / 3;

        if (!boss.dead()) {
            Player player = getRandomPlayer(Team.crux);

            if (player != null) {
                Call.unitControl(player, boss);
                sendBundled(
                        "game.boss.spawn",
                        player.name()
                );
            }
        }
    }

    public static int playerCount(Team team) {
        final int[] count = {0};

        Groups.player.each(player -> {
            if (player.team() == team) {
                count[0]++;
            }
        });

        return count[0];
    }

    public static int playerCount() {
        return Groups.player.size();
    }

    public static void sendBundled(
            String key,
            Object... format
    ) {
        Groups.player.forEach(player -> {
            Locale locale = Bundle.findLocale(player.locale());

            player.sendMessage(
                    Bundle.format(key, locale, format)
            );
        });
    }

    public void sendBundled(String key) {
        Groups.player.forEach(player -> {
            Locale locale = Bundle.findLocale(player.locale());

            player.sendMessage(
                    Bundle.get(key, locale)
            );
        });
    }

    public static UnitType getStartingUnit() {
        switch (getRandomInt(1, 11)) {
            case 1, 2, 3, 4 -> {
                return UnitTypes.dagger;
            }
            case 5, 6 -> {
                return UnitTypes.nova;
            }
            case 7, 8 -> {
                return UnitTypes.merui;
            }
            case 9 -> {
                return UnitTypes.flare;
            }
            case 10 -> {
                return UnitTypes.mono;
            }
            default -> {
                return UnitTypes.dagger;
            }
        }
    }

    public static void announceBundled(
            String key,
            int duration
    ) {
        Groups.player.forEach(player -> {
            Locale locale = Bundle.findLocale(player.locale);
            String text = Bundle.get(key, locale);

            Call.infoPopup(
                    player.con,
                    text,
                    duration,
                    0,
                    0,
                    0,
                    -200,
                    0
            );
        });
    }

    public static void announceBundled(
            String key,
            int duration,
            Object... format
    ) {
        Groups.player.forEach(player -> {
            Locale locale = Bundle.findLocale(player.locale);
            String text = Bundle.format(
                    key,
                    locale,
                    format
            );

            Call.infoPopup(
                    player.con,
                    text,
                    duration,
                    0,
                    0,
                    0,
                    -200,
                    0
            );
        });
    }
}