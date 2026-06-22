package rvsblogicmod;

import arc.func.Prov;
import arc.scene.ui.layout.Table;
import mindustry.gen.LogicIO;
import mindustry.logic.LAssembler;
import mindustry.logic.LCategory;
import mindustry.logic.LExecutor;
import mindustry.logic.LStatement;
import mindustry.logic.LVar;

public class GivePointsStatement extends LStatement {

    public static final String OPCODE = "givepoints";

    public String unit = "0";
    public String amount = "0";

    @Override
    public String name() {
        return OPCODE;
    }

    @Override
    public LCategory category() {
        return LCategory.operation;
    }

    @Override
    public boolean privileged() {
        return true;
    }

    @Override
    public void build(Table table) {
        table.add("unit ");
        field(table, unit, str -> unit = str).width(110f).padRight(8f);
        table.add("amount ");
        field(table, amount, str -> amount = str).width(90f);
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

    public static void register() {
        LogicIO.allStatements.add((Prov<LStatement>) GivePointsStatement::new);
        LAssembler.customParsers.put(OPCODE, GivePointsStatement::read);

        LogicIO.allStatements.add((Prov<LStatement>) FetchPointsStatement::new);
        LAssembler.customParsers.put(FetchPointsStatement.OPCODE, FetchPointsStatement::read);
    }

    public static final class GivePointsI implements LExecutor.LInstruction {
        public GivePointsI() {}
        public GivePointsI(LVar unit, LVar amount) {}

        @Override
        public void run(LExecutor exec) {}
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
        public LCategory category() {
            return LCategory.operation;
        }

        @Override
        public boolean privileged() {
            return true;
        }

        @Override
        public void build(Table table) {
            table.add("unit ");
            field(table, unit, str -> unit = str).width(110f).padRight(8f);
            table.add("result ");
            field(table, result, str -> result = str).width(90f);
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
        public FetchPointsI() {}
        public FetchPointsI(LVar unit, LVar result) {}

        @Override
        public void run(LExecutor exec) {}
    }
}
