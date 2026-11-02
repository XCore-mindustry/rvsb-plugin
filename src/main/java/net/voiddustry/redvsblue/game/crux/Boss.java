package net.voiddustry.redvsblue.game.crux;

import arc.graphics.Color;
import arc.math.Mathf;
import arc.math.Angles;
import arc.struct.Seq;
import arc.util.Time;
import arc.util.Timer;
import mindustry.Vars;
import mindustry.content.Fx;
import mindustry.content.StatusEffects;
import mindustry.content.UnitTypes;
import mindustry.game.Team;
import mindustry.gen.Call;
import mindustry.gen.Groups;
import mindustry.gen.Player;
import mindustry.gen.Unit;
import mindustry.type.StatusEffect;
import mindustry.type.UnitType;
import net.voiddustry.redvsblue.Bundle;
import net.voiddustry.redvsblue.PlayerData;
import net.voiddustry.redvsblue.RedVsBluePlugin;
import net.voiddustry.redvsblue.util.StationFx;
import net.voiddustry.redvsblue.util.Utils;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;

/** Crux killing streaks: the player gets the rotting aura for 5 minutes and an AI unit with extra health spawns. */
public class Boss {
    public static final long AURA_MS = 5 * 60 * 1000;

    /** radius of the aura in world units (8 tiles, same as the quad field) */
    public static final float AURA_RADIUS = 64f;
    /** fraction of max health every blue unit inside loses each damage tick (0.5 s): 2% a second, twice the quad field */
    public static final float AURA_DAMAGE = 1f / 100f;

    /** uuid -> aura expiry timestamp (ms) */
    private static final Map<String, Long> auras = new HashMap<>();
    private static int tick;

    public static boolean hasAura(Player player) {
        Long until = auras.get(player.uuid());
        return until != null && Time.millis() < until;
    }

    public static void clear() {
        auras.clear();
    }

    public static void killStreak(Player player) {
        if (player == null || hasAura(player)) return;

        auras.put(player.uuid(), Time.millis() + AURA_MS);
        PlayerData data = RedVsBluePlugin.players.get(player.uuid());
        if (data != null) data.setKills(0);

        int stage = RedVsBluePlugin.stage;
        UnitType type;
        float multiplier;
        if (stage <= 4) {
            type = UnitTypes.mace;
            multiplier = 1f;
        } else if (stage <= 7) {
            type = UnitTypes.locus;
            multiplier = 2.4f;
        } else if (stage <= 10) {
            type = UnitTypes.precept;
            multiplier = 4.95f;
        } else {
            type = UnitTypes.precept;
            multiplier = 7.425f;
        }

        if (!RedVsBluePlugin.redSpawns.isEmpty()) {
            Unit unit = type.spawn(Team.crux, RedVsBluePlugin.redSpawns.random());
            int blue = Utils.playerCount(Team.blue);
            float health = blue <= 2 ? 700f : 250f * blue * stage;
            unit.health = health * multiplier;
        }

        if (!player.dead() && player.unit() != null) {
            Unit u = player.unit();
            StatusEffect s = Vars.content.statusEffect("dp-killstreak-aura");
            if (s != null) u.apply(s, 20f);
            StationFx.at(Fx.spawnShockwave, u.x, u.y, AURA_RADIUS - 50f, StationFx.rot, null, AURA_RADIUS);
            StationFx.at(Fx.healWaveMend, u.x, u.y, AURA_RADIUS, StationFx.rot, u, AURA_RADIUS);
            StationFx.star(u.x, u.y, 40, StationFx.rot);
        }

        Groups.player.each(p -> {
            Locale locale = Bundle.findLocale(p.locale());
            p.sendMessage(Bundle.format("boss.killstreak", locale, player.name));
        });
    }

    public static void forEachBoss() {
        // dp-killstreak-aura renders smooth parented circles on the client; healWaveMend is the fallback
        Timer.schedule(() -> {
            tick++;
            long now = Time.millis();
            StatusEffect auraStatus = Vars.content == null ? null : Vars.content.statusEffect("dp-killstreak-aura");
            Iterator<Map.Entry<String, Long>> it = auras.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<String, Long> entry = it.next();
                if (now >= entry.getValue()) {
                    it.remove();
                    continue;
                }

                Player p = Groups.player.find(pl -> pl.uuid().equals(entry.getKey()));
                if (p == null || p.dead() || p.team() != Team.crux || p.unit() == null) continue;

                Unit u = p.unit();
                if (auraStatus != null) u.apply(auraStatus, 20f);
                else if (tick % 6 == 0) StationFx.at(Fx.healWaveMend, u.x, u.y, AURA_RADIUS, StationFx.rot, u, AURA_RADIUS);

                if (tick % 3 == 0) {
                    float angle = Mathf.random(360f);
                    StationFx.at(Fx.regenSuppressSeek, u.x + Angles.trnsx(angle, AURA_RADIUS), u.y + Angles.trnsy(angle, AURA_RADIUS), 0f, StationFx.rot, u, AURA_RADIUS);
                }
                if (tick % 45 == 0) StationFx.star(u.x, u.y, AURA_RADIUS * 0.55f, StationFx.rot);
                if (tick % 5 == 0) damage(p);
            }
        }, 0, 0.1F);
    }

    /** Percentage damage like the quad field: every blue unit in the circle loses AURA_DAMAGE of its max health. */
    private static void damage(Player owner) {
        Unit ownerUnit = owner.unit();
        float x = owner.x, y = owner.y;
        Seq<Unit> inside = new Seq<>();
        Groups.unit.each(u -> u.team == Team.blue && !u.dead && u.within(x, y, AURA_RADIUS), inside::add);

        for (Unit u : inside) {
            float damage = u.maxHealth * AURA_DAMAGE;
            u.apply(StatusEffects.sapped, 40f);
            StationFx.at(Fx.chainEmp, x, y, 0f, StationFx.rot, u, AURA_RADIUS);
            StationFx.at(Fx.hitSquaresColor, u.x, u.y, 0f, StationFx.rot, null, 16f);

            Player victim = u.getPlayer();
            if (victim != null && victim.con != null) {
                Call.label(victim.con, "[#d1263a]-" + Math.round(damage), 0.5f, u.x, u.y);
            }

            if (u.health > damage) {
                u.health -= damage;
            } else {
                // killed by the aura: a burst on the victim and its remains drift into the crux player
                StationFx.star(u.x, u.y, Math.max(20f, u.hitSize), StationFx.rot);
                if (ownerUnit != null) {
                    for (int k = 0; k < 3; k++) StationFx.at(Fx.regenSuppressSeek, u.x, u.y, 0f, StationFx.rot, ownerUnit, AURA_RADIUS);
                }
                u.kill();
            }
        }
    }
}
