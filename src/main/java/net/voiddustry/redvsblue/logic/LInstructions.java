package net.voiddustry.redvsblue.logic;

import arc.func.Prov;
import arc.scene.ui.layout.Table;
import arc.util.Log;
import mindustry.game.Team;
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

        LogicIO.allStatements.add((Prov<LStatement>) FetchPointsStatement::new);
        LAssembler.customParsers.put(FetchPointsStatement.OPCODE, FetchPointsStatement::read);
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

    public static final class FetchPointsStatement extends LStatement {
        public static final String OPCODE = "fetchpoints";

        public String unit = "0";
        public String result = "result";

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
            return new FetchPointsI(builder.var(unit), builder.var(result));
        }

        @Override
        public void write(StringBuilder builder) {
            builder.append(OPCODE).append(' ');
            builder.append(unit).append(' ');
            builder.append(result).append(' ');
        }

        public static LStatement read(String[] tokens) {
            FetchPointsStatement s = new FetchPointsStatement();
            if (tokens.length > 1) s.unit = tokens[1];
            if (tokens.length > 2) s.result = tokens[2];
            return s;
        }
    }

    public static final class FetchPointsI implements LExecutor.LInstruction {
        public LVar unit;
        public LVar result;

        public FetchPointsI() {}

        public FetchPointsI(LVar unit, LVar result) {
            this.unit = unit;
            this.result = result;
        }

        @Override
        public void run(LExecutor exec) {
            if (unit == null || result == null) {
                return;
            }


            if (!(unit.objval instanceof Unit u)) {
                result.setobj(null);
                return;
            }


            Player player = u.getPlayer();


            if (player == null) {
                result.setobj(null);
                return;
            }

            PlayerData data = RedVsBluePlugin.players.get(player.uuid());

            if (data == null) {
                result.setobj(null);
                return;
            }

            result.setnum(data.getScore());
        }
    }
}
