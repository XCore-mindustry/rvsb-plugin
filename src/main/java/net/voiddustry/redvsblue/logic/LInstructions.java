package net.voiddustry.redvsblue.logic;

import arc.func.Prov;
import arc.scene.ui.layout.Table;
import mindustry.gen.LogicIO;
import mindustry.gen.Player;
import mindustry.gen.Unit;
import mindustry.logic.LAssembler;
import mindustry.logic.LExecutor;
import mindustry.logic.LStatement;
import mindustry.logic.LVar;
import net.voiddustry.redvsblue.PlayerData;
import net.voiddustry.redvsblue.RedVsBluePlugin;


public final class LInstructions {
    private LInstructions() {}

    public static void register() {
        LogicIO.allStatements.add((Prov<LStatement>) GivePointsStatement::new);
        LAssembler.customParsers.put(GivePointsStatement.OPCODE, GivePointsStatement::read);
    }

    public static final class GivePointsStatement extends LStatement {
        public static final String OPCODE = "givepoints";

        public String unit = "0";
        public String amount = "0";

        @Override
        public String name() {
            return OPCODE;
        }

        @Override
        public boolean privileged() {
            return true;
        }

        @Override
        public void build(Table table) {
        }

        @Override
        public LExecutor.LInstruction build(LAssembler builder) {
            return new GivePointsI(builder.var(unit), builder.var(amount));
        }

        @Override
        public void write(StringBuilder builder) {
            builder.append(OPCODE).append(' ');
            builder.append(unit).append(' ');
            builder.append(amount).append(' ');
        }

        public static LStatement read(String[] tokens) {
            GivePointsStatement s = new GivePointsStatement();
            if (tokens.length > 1) s.unit = tokens[1];
            if (tokens.length > 2) s.amount = tokens[2];
            return s;
        }
    }

    public static final class GivePointsI implements LExecutor.LInstruction {
        public LVar unit;
        public LVar amount;

        public GivePointsI() {}

        public GivePointsI(LVar unit, LVar amount) {
            this.unit = unit;
            this.amount = amount;
        }

        @Override
        public void run(LExecutor exec) {
            if (unit == null || amount == null) return;

            if (!(unit.objval instanceof Unit u)) return;

            if (u == null || !u.isPlayer()) return;

            Player player = u.getPlayer();
            if (player == null) return;

            PlayerData data = RedVsBluePlugin.players.get(player.uuid());
            if (data == null) return;

            data.addScore((int) amount.numval);
        }
    }
}
