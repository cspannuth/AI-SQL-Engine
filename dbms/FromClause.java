import java.util.*;

/** Tables and join predicates in their original left-to-right order. */
public class FromClause {
    public final ArrayList<Table> tables = new ArrayList<>();
    public final ArrayList<ConditionalExpression> conditions = new ArrayList<>();
    public final ArrayList<Boolean> leftJoins = new ArrayList<>();
    public boolean reorderable = true;

    public FromClause add(Table table, ConditionalExpression condition, boolean leftJoin) {
        tables.add(table);
        conditions.add(condition);
        leftJoins.add(leftJoin);
        return this;
    }

    /** Explicit CROSS JOIN preserves the caller's requested loop order, as in SQLite. */
    public FromClause crossJoin(Table table) {
        reorderable = false;
        return add(table, null, false);
    }

    public static FromClause cross(List<Table> tables) {
        FromClause clause = new FromClause();
        for (Table table : tables) { clause.add(table, null, false); }
        return clause;
    }
}
