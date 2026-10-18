package net.voiddustry.redvsblue.game.crux;

import arc.graphics.Color;
import arc.math.Mathf;
import arc.math.Angles;
import arc.struct.Seq;
import arc.util.Time;
import arc.util.Timer;
import mindustry.content.Fx;
import mindustry.content.StatusEffects;
import mindustry.content.UnitTypes;
import mindustry.game.Team;
import mindustry.gen.Call;
import mindustry.gen.Groups;
import mindustry.gen.Player;
import mindustry.gen.Unit;
import mindustry.type.UnitType;
import net.voiddustry.redvsblue.Bundle;
import net.voiddustry.redvsblue.PlayerData;
import net.voiddustry.redvsblue.RedVsBluePlugin;
import net.voiddustry.redvsblue.util.AuraCircle;
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

    private static final class Aura {
        final long until;
        final AuraCircle circle = new AuraCircle(AURA_RADIUS, StationFx.rot);

        Aura(long until) {
            this.until = until;
        }
    }

    /** uuid -> active aura */
    private static final Map<String, Aura> auras = new HashMap<>();
    private static int tick;

    public static boolean hasAura(Player player) {
        Aura aura = auras.get(player.uuid());
        return aura != null && Time.millis() < aura.until;
    }

    public static void clear() {
        auras.values().forEach(a -> a.circle.hide());
        auras.clear();
    }

    public static void killStreak(Player player) {
        if (player == null || hasAura(player)) return;

        Aura old = auras.put(player.uuid(), new Aura(Time.millis() + AURA_MS));
        if (old != null) old.circle.hide();
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
            multiplier = 4f;
        } else if (stage <= 10) {
            type = UnitTypes.precept;
            multiplier = 9f;
        } else {
            type = UnitTypes.precept;
            multiplier = 13.5f;
        }

        if (!RedVsBluePlugin.redSpawns.isEmpty()) {
            Unit unit = type.spawn(Team.crux, RedVsBluePlugin.redSpawns.random());
            int blue = Utils.playerCount(Team.blue);
            float health = blue <= 2 ? 700f : 250f * blue * stage;
            unit.health = health * multiplier;
        }

        if (!player.dead()) {
            // a shockwave out to the rim and a spike star
            StationFx.at(Fx.spawnShockwave, player.x, player.y, AURA_RADIUS - 50f, StationFx.rot, null, AURA_RADIUS);
            StationFx.star(player.x, player.y, 40, StationFx.rot);
        }

        Groups.player.each(p -> {
            Locale locale = Bundle.findLocale(p.locale());
            p.sendMessage(Bundle.format("boss.killstreak", locale, player.name));
        });
    }

    public static void forEachBoss() {
        // 0.1 s: keeps the circle on the player
        // 0.3 s: a soul particle is pulled in from the rim; 0.5 s: damage; 1.5 s: pulse out to the rim; 4.5 s: spike star
        Timer.schedule(() -> {
            tick++;
            long now = Time.millis();
            Iterator<Map.Entry<String, Aura>> it = auras.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<String, Aura> entry = it.next();
                Aura aura = entry.getValue();
                if (now >= aura.until) {
                    aura.circle.hide();
                    it.remove();
                    continue;
                }

                Player p = Groups.player.find(pl -> pl.uuid().equals(entry.getKey()));
                if (p == null || p.dead() || p.team() != Team.crux) {
                    aura.circle.hide();
                    continue;
                }

                aura.circle.update(p.x, p.y);
                if (tick % 3 == 0) {
                    float angle = Mathf.random(360f);
                    StationFx.seek(p.x + Angles.trnsx(angle, AURA_RADIUS), p.y + Angles.trnsy(angle, AURA_RADIUS), p.x, p.y, StationFx.rot);
                }
                if (tick % 15 == 0) StationFx.pulse(p.x, p.y, AURA_RADIUS, StationFx.rot);
                if (tick % 45 == 0) StationFx.star(p.x, p.y, AURA_RADIUS * 0.55f, StationFx.rot);
                if (tick % 5 == 0) damage(p);
            }
        }, 0, 0.1F);
    }

    /** Percentage damage like the quad field: every blue unit in the circle loses AURA_DAMAGE of its max health. */
    private static void damage(Player owner) {
        float x = owner.x, y = owner.y;
        Seq<Unit> inside = new Seq<>();
        Groups.unit.each(u -> u.team == Team.blue && !u.dead && u.within(x, y, AURA_RADIUS), inside::add);

        for (Unit u : inside) {
            float damage = u.maxHealth * AURA_DAMAGE;
            u.apply(StatusEffects.sapped, 40f);
            StationFx.zap(x, y, u.x, u.y, StationFx.rot);
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
                for (int k = 0; k < 3; k++) StationFx.seek(u.x, u.y, x, y, StationFx.rot);
                u.kill();
            }
        }
    }
}
