package net.voiddustry.redvsblue.game.stations;

import arc.graphics.Color;
import arc.util.Log;
import arc.util.Timer;
import mindustry.Vars;
import mindustry.content.Blocks;
import mindustry.content.Fx;
import mindustry.game.Team;
import mindustry.gen.Call;
import mindustry.gen.Groups;
import mindustry.gen.Unit;
import mindustry.graphics.Layer;
import mindustry.type.UnitType;
import mindustry.ui.Menus;
import mindustry.world.Tile;
import net.voiddustry.redvsblue.Bundle;
import net.voiddustry.redvsblue.PlayerData;
import net.voiddustry.redvsblue.RedVsBluePlugin;
import net.voiddustry.redvsblue.evolution.Evolution;
import net.voiddustry.redvsblue.evolution.Evolutions;
import net.voiddustry.redvsblue.game.stations.stationData.StationData;
import net.voiddustry.redvsblue.util.Utils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static net.voiddustry.redvsblue.RedVsBluePlugin.players;

public class Laboratory {
    private static final Map<String, StationData> labsMap =
            new ConcurrentHashMap<>();

    public static final int evolutionMenu = Menus.registerMenu((player, option) -> {
        if (option < 0) {
            return;
        }

        Unit oldUnit = player.unit();

        if (oldUnit == null || oldUnit.type() == null) {
            return;
        }

        Evolution currentEvolution =
                Evolutions.evolutions.get(oldUnit.type().name);

        if (currentEvolution == null
                || option >= currentEvolution.evolutions.length) {
            return;
        }

        String targetUnitName = currentEvolution.evolutions[option];
        Evolution evolutionOption =
                Evolutions.evolutions.get(targetUnitName);

        if (evolutionOption == null) {
            Log.warn(
                    "Evolution data for unit '@' was not found.",
                    targetUnitName
            );
            return;
        }

        /*
         * Resolve the target through the active content registry at the
         * moment the player evolves. Do not use a cached UnitType or a
         * UnitTypes constant here.
         */
        UnitType targetType = Vars.content.unit(targetUnitName);

        if (targetType == null) {
            Log.warn(
                    "Cannot evolve player '@': Vars.content.unit('@') returned null.",
                    player.name(),
                    targetUnitName
            );
            return;
        }

        PlayerData playerData = players.get(player.uuid());

        if (playerData == null) {
            return;
        }

        float multiplier = getMultiplier(evolutionOption, player);
        int evolutionCost = (int) (evolutionOption.cost * multiplier);

        if (playerData.getScore() < evolutionCost) {
            player.sendMessage(
                    Bundle.get("evolution.not-enough", player.locale)
            );
            return;
        }

        Tile playerTile = player.tileOn();

        boolean canSpawnHere =
                targetType.flying
                        || targetType.canBoost
                        || targetType.groundLayer == Layer.legUnit
                        || (playerTile != null
                        && playerTile.block() == Blocks.air);

        if (!canSpawnHere) {
            return;
        }

        Unit newUnit = targetType.spawn(
                player.team(),
                oldUnit.x,
                oldUnit.y
        );

        if (newUnit == null || newUnit.dead()) {
            return;
        }

        newUnit.health = newUnit.type.health / 2f;

        /*
         * Explicitly transfer control in the same way as the working
         * setunit server command.
         */
        newUnit.controller(player);
        player.unit(newUnit);
        playerData.setUnit(newUnit);

        oldUnit.kill();

        playerData.subtractScore(evolutionCost);
        playerData.setEvolutionStage(evolutionOption.tier);
        playerData.setLastEvolutionTime(
                Instant.now().getEpochSecond()
        );

        Utils.sendBundled(
                "game.evolved",
                player.name(),
                targetUnitName
        );
    });

    public static void initTimer() {
        Timer.schedule(() -> {
            renderLabs();

            Groups.player.each(player -> {
                if (players.containsKey(player.uuid())) {
                    players.get(player.uuid()).setCanEvolve(false);
                }

                labsMap.forEach((uuid, lab) -> {
                    int centerX = lab.tileOn().x * 8;
                    int centerY = lab.tileOn().y * 8;

                    if (player.team() == Team.blue
                            && player.unit() != null
                            && player.dst(centerX, centerY) <= 48) {

                        PlayerData playerData =
                                players.get(player.uuid());

                        if (playerData != null) {
                            playerData.setCanEvolve(true);
                        }

                        Call.infoPopup(
                                player.con,
                                Bundle.get(
                                        "evolution.evolution-available",
                                        player.locale
                                ),
                                0.5f,
                                0,
                                0,
                                0,
                                -200,
                                0
                        );
                    }
                });
            });

            labsMap.forEach((uuid, lab) -> {
                int centerX = lab.tileOn().x * 8;
                int centerY = lab.tileOn().y * 8;

                for (int i = 0; i < 19; i++) {
                    Call.effect(
                            Fx.vaporSmall,
                            (float) (
                                    centerX
                                            + Math.sin(i) * 48
                            ),
                            (float) (
                                    centerY
                                            + Math.cos(i) * 48
                            ),
                            1,
                            Color.purple
                    );
                }
            });
        }, 0, 0.5f);
    }

    /**
     * Opens the evolution UI for a nearby laboratory station button press.
     */
    public static void openStationMenu(
            mindustry.gen.Player player
    ) {
        if (player.unit() == null
                || players.get(player.uuid()) == null) {
            return;
        }

        Locale locale = Bundle.findLocale(player.locale());

        Evolution currentEvolution =
                Evolutions.evolutions.get(
                        player.unit().type().name
                );

        if (currentEvolution == null) {
            return;
        }

        String[][] buttons =
                new String[currentEvolution.evolutions.length][1];

        for (int i = 0;
             i < currentEvolution.evolutions.length;
             i++) {

            String targetUnitName =
                    currentEvolution.evolutions[i];

            Evolution option =
                    Evolutions.evolutions.get(targetUnitName);

            if (option == null) {
                buttons[i][0] =
                        "[scarlet]Missing evolution: "
                                + targetUnitName;
                continue;
            }

            /*
             * Resolve the display unit from Vars.content as well.
             */
            UnitType targetType =
                    Vars.content.unit(targetUnitName);

            String displayName =
                    targetType != null
                            ? targetType.localizedName
                            : targetUnitName;

            float multiplier =
                    getMultiplier(option, player);

            int cost =
                    (int) (option.cost * multiplier);

            String color;

            if (multiplier > 1
                    && multiplier <= 1.99f) {
                color = "[orange]";
            } else if (cost > option.cost) {
                color = "[red]";
            } else if (cost < option.cost) {
                color = "[green]";
            } else {
                color = "[yellow]";
            }

            buttons[i][0] = Bundle.format(
                    "menu.evolution.evolve",
                    locale,
                    displayName,
                    color
                            + cost
                            + " - "
                            + (multiplier * 100)
                            + "%"
            );
        }

        Call.menu(
                player.con,
                evolutionMenu,
                Bundle.get(
                        "menu.evolution.title",
                        locale
                ),
                Bundle.format(
                        "menu.evolution.message",
                        locale,
                        players.get(
                                player.uuid()
                        ).getEvolutionStage(),
                        Bundle.get(
                                "evolution.branch.initial",
                                locale
                        )
                ),
                buttons
        );
    }

    public static float getMultiplier(
            Evolution evolution,
            mindustry.gen.Player player
    ) {
        if (evolution == null
                || RedVsBluePlugin.players.get(
                player.uuid()
        ) == null) {
            return 4;
        }

        double timeSinceLastEvolution =
                Instant.now().getEpochSecond()
                        - RedVsBluePlugin.players
                        .get(player.uuid())
                        .getLastEvolutionTime();

        int requiredStage = evolution.stage;
        float multiplier = 0;

        if (timeSinceLastEvolution < 180) {
            multiplier =
                    (float) (
                            (180 - timeSinceLastEvolution)
                                    / 1000
                    );

            multiplier +=
                    ((float) Math.sqrt(evolution.cost)
                            / evolution.cost)
                            * multiplier;
        }

        if (RedVsBluePlugin.stage == requiredStage) {
            multiplier += 1f;
        } else if (RedVsBluePlugin.stage > requiredStage) {
            multiplier += 0.75f;
        } else {
            multiplier +=
                    1
                            + (float) Math.pow(
                            2,
                            requiredStage
                                    - RedVsBluePlugin.stage
                    ) / 4;
        }

        BigDecimal roundedMultiplier =
                new BigDecimal(
                        String.valueOf(multiplier)
                );

        roundedMultiplier =
                roundedMultiplier.setScale(
                        3,
                        RoundingMode.HALF_UP
                );

        return roundedMultiplier.floatValue();
    }

    public static float getMultiplier(
            String evolution,
            mindustry.gen.Player player
    ) {
        return getMultiplier(
                Evolutions.evolutions.get(evolution),
                player
        );
    }

    public static void buyLab(
            mindustry.gen.Player player,
            Tile tile
    ) {
        PlayerData playerData =
                players.get(player.uuid());

        if (playerData == null) {
            return;
        }

        if (playerData.getScore() < 7) {
            player.sendMessage(
                    Bundle.format(
                            "station.not-enough-money",
                            Bundle.findLocale(player.locale),
                            7
                    )
            );
            return;
        }

        if (labsMap.containsKey(player.uuid())
                || player.dead()) {
            return;
        }

        Tile playerTileOn = player.tileOn();

        if (playerTileOn == null) {
            return;
        }

        if (tile == null) {
            tile = Vars.world.tile(
                    playerTileOn.x,
                    playerTileOn.y - 1
            );
        }

        if (tile == null
                || player.dead()
                || player.team() != Team.blue
                || !tile.block().isAir()
                || tile.floor() == Blocks.empty) {
            return;
        }

        String text = player.name + "[gold]'s\n[purple]Lab";
        mindustry.gen.WorldLabel label = StationUtils.createStationLabel(tile, text);
        StationData laboratoryData =
                new StationData(player, tile, label);

        labsMap.put(
                player.uuid(),
                laboratoryData
        );

        Call.constructFinish(
                tile,
                Vars.content.block(
                        "dp-laboratory-station"
                ),
                null,
                (byte) 0,
                Team.blue,
                null
        );

        Call.effect(
                Fx.regenParticle,
                tile.x * 8,
                tile.y * 8,
                0,
                Color.red
        );

        playerData.subtractScore(7);
    }

    public static void renderLabs() {
        labsMap.forEach((owner, lab) -> {
            if (lab == null) {
                return;
            }

            if (lab.tileOn().block()
                    != Vars.content.block(
                    "dp-laboratory-station"
            )
                    || lab.owner().team()
                    != Team.blue) {

                labsMap.remove(owner);
                lab.destroy();

                if (lab.tileOn().block()
                        == Vars.content.block(
                        "dp-laboratory-station"
                )) {
                    lab.tileOn().build.kill();
                }
            }
        });
    }

    public static void clearLabs() {
        labsMap.values().forEach(StationData::destroy);
        labsMap.clear();
    }
}