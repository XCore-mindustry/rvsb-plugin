package net.voiddustry.redvsblue.game.units.special;

import arc.graphics.Color;
import mindustry.content.Fx;
import mindustry.content.StatusEffects;
import mindustry.content.UnitTypes;
import mindustry.game.Team;
import mindustry.gen.Call;
import mindustry.gen.Groups;
import net.voiddustry.redvsblue.PlayerData;
import mindustry.Vars;

import static net.voiddustry.redvsblue.RedVsBluePlugin.players;

public class Quad {
    public static void initAbilities() {
        Groups.unit.forEach(unit -> {
            if(unit.type == UnitTypes.quad && unit.team == Team.blue) {
                if (unit.x < Vars.world.width()*8-8 && unit.x > 8 && unit.y < Vars.world.height()*8-8 && unit.y > 8) {
                    int centerX = unit.tileOn().x * 8;
                    int centerY = unit.tileOn().y * 8;

                    for (int i = 0; i < 40; i++) {
                        Call.effect(Fx.healWaveDynamic, (float) (centerX + Math.sin(i) * 64), (float) (centerY + Math.cos(i) * 64), 1, Color.red);
                    }
                    Groups.unit.forEach(u -> {
                        if(u.team == Team.crux && u.dst(unit.x, unit.y) <= 64) {
                            Call.effect(Fx.fire, u.x, u.y, 2, Color.red);

                            u.apply(StatusEffects.shocked, 120);

                            float damage = u.maxHealth/200;

                            if (u.health >= damage) {
                                u.health = u.health() - damage;
                                Call.label("[#55557F]-" + damage, 0.5f, u.x, u.y);
                            } else {
                                u.kill();
                                if(unit.getPlayer() != null) {
                                    PlayerData data = players.get(unit.getPlayer().uuid());
                                    if (data != null) {
                                        data.rewardUnitKill(unit.getPlayer(), u);
                                    }
                                }

                                Call.label("[scarlet]-" + damage, 3f, u.x, u.y);
                            }
                        }
                    });
                }
            }
        });
    }
}
