package net.voiddustry.redvsblue.util;

import arc.Events;
import arc.files.Fi;
import arc.struct.Seq;
import arc.util.Log;
import arc.util.Strings;
import arc.util.Timer;
import arc.util.serialization.Jval;
import mindustry.Vars;
import mindustry.content.Blocks;
import mindustry.game.EventType;
import mindustry.game.Team;
import mindustry.gen.Call;
import mindustry.gen.Groups;
import mindustry.gen.Player;
import mindustry.world.Block;
import mindustry.world.Tile;
import mindustry.world.blocks.defense.BaseShield;
import net.voiddustry.redvsblue.Bundle;
import net.voiddustry.redvsblue.game.crux.StrengthSpawner;

/**
 * Places an invisible 1x1 {@link BaseShield} on every map {@code spawn} overlay.
 * Only maps listed in {@code rvsbUnits/spawnShield.hjson} are affected, and shields appear only
 * after the balancer's HP metric reaches the configured threshold.
 */
public final class SpawnShields {
    private static final String CONFIG = "rvsbUnits/spawnShield";

    private static boolean enabled = true;
    private static String blockName = "dp-spawn-shield";
    private static float radius = 64f;
    private static int health = 2147483000;

    private static boolean allMaps;
    private static final Seq<String> maps = new Seq<>();

    private static boolean activationEnabled = true;
    private static float activationHp = 1000f;
    private static float activationDelay = 20f;
    private static String activationMetric = "decaying";
    private static String activationSpawner = "";

    private static boolean placedThisWorld;
    private static boolean announced;
    private static int worldGen;

    private SpawnShields() {
    }

    /** Registers the world-load hook and the activation timer. Safe to call more than once. */
    public static synchronized void init() {
        load();
        Events.on(EventType.WorldLoadEvent.class, event -> {
            load();
            worldGen++;
            placedThisWorld = false;
            announced = false;
        });
        Timer.schedule(SpawnShields::tick, 1f, 1f);
    }

    private static void load() {
        enabled = true;
        blockName = "dp-spawn-shield";
        radius = 64f;
        health = 2147483000;
        allMaps = false;
        maps.clear();
        activationEnabled = true;
        activationHp = 1000f;
        activationDelay = 20f;
        activationMetric = "decaying";
        activationSpawner = "";

        Fi file = Vars.dataDirectory.child(CONFIG + ".hjson");
        if (!file.exists()) file = Vars.dataDirectory.child(CONFIG + ".json");
        if (!file.exists()) {
            // No config: keep the old behaviour, guard every map, but still wait for the threshold.
            allMaps = true;
            return;
        }

        try {
            Jval root = Jval.read(file.readString());
            enabled = root.getBool("enabled", true);
            blockName = root.getString("block", blockName);
            radius = root.getFloat("radius", radius);
            health = root.getInt("health", health);
            allMaps = root.getBool("all", false);
            if (root.has("maps")) {
                for (Jval value : root.get("maps").asArray()) {
                    String name = value.asString().trim();
                    if (!name.isEmpty()) maps.add(name);
                }
            }
            if (root.has("activation")) {
                Jval activation = root.get("activation");
                activationEnabled = activation.getBool("enabled", true);
                activationHp = activation.getFloat("hp", activationHp);
                activationDelay = activation.getFloat("delay", activationDelay);
                activationMetric = activation.getString("metric", activationMetric);
                activationSpawner = activation.getString("spawner", activationSpawner);
            }
        } catch (Throwable t) {
            Log.err("[SpawnShields] failed to read @", file.path());
            Log.err(t);
        }
    }

    private static String mapFile() {
        return Vars.state.map != null && Vars.state.map.file != null
                ? Vars.state.map.file.nameWithoutExtension() : "";
    }

    private static String mapName() {
        return Vars.state.map != null ? Strings.stripColors(Vars.state.map.name()) : "";
    }

    private static boolean activeForMap() {
        if (allMaps) return true;
        String file = mapFile();
        String display = mapName();
        for (String map : maps) {
            if (map.equalsIgnoreCase(file) || map.equalsIgnoreCase(display)) return true;
        }
        return false;
    }

    private static void tick() {
        if (placedThisWorld || announced || !enabled || Vars.net.client()
                || !Vars.state.isGame() || Vars.world == null) return;
        if (!activeForMap()) return;

        if (activationEnabled && activationHp > 0f) {
            float hp = StrengthSpawner.balancerHp(activationMetric, activationSpawner);
            if (hp < activationHp) return;

            announced = true;
            notifyPlayers();
            Log.info("[SpawnShields] balancer HP @ >= @; announcing shields in @s",
                    Math.round(hp), Math.round(activationHp), Math.round(activationDelay));

            int gen = worldGen;
            if (activationDelay <= 0f) {
                place();
                return;
            }
            Timer.schedule(() -> {
                if (gen != worldGen || placedThisWorld || !Vars.state.isGame() || !activeForMap()) return;
                place();
            }, activationDelay);
            return;
        }

        place();
    }

    private static void notifyPlayers() {
        for (Player player : Groups.player) {
            if (player.con != null && player.con.isConnected()) {
                Call.announce(player.con, Bundle.format("spawnShield.incoming", player.locale, String.valueOf(Math.round(activationDelay))));
            }
        }
    }

    private static void place() {
        placedThisWorld = true;

        Block block = Vars.content.block(blockName);
        if (block == null) block = Vars.content.block("spawn-shield");
        if (!(block instanceof BaseShield shieldBlock)) {
            Log.warn("[SpawnShields] block '@' not found or not a BaseShield; spawn shields disabled", blockName);
            return;
        }

        if (radius > 0f) shieldBlock.radius = radius;
        if (health > 0) shieldBlock.health = health;

        int placed = 0, skipped = 0;
        for (int y = 0; y < Vars.world.height(); y++) {
            for (int x = 0; x < Vars.world.width(); x++) {
                Tile tile = Vars.world.tile(x, y);
                if (tile == null || tile.overlay() != Blocks.spawn) continue;

                if (tile.block() == shieldBlock) {
                    skipped++;
                    continue;
                }
                if (tile.block() != Blocks.air) {
                    skipped++;
                    continue;
                }

                Call.constructFinish(tile, shieldBlock, null, (byte) 0, Team.crux, null);

                // protection is active immediately, not after the radius lerp-in
                if (tile.build instanceof BaseShield.BaseShieldBuild shield) {
                    shield.smoothRadius = shieldBlock.radius;
                }
                placed++;
            }
        }

        Log.info("[SpawnShields] placed @ spawn shield(s), skipped @ occupied spawn tile(s)", placed, skipped);
    }
}
