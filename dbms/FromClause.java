import java.util.*;

/** Tables and join predicates in their original left-to-right order. */
public class FromClause {
    public final ArrayList<Table> tables = new ArrayList<>();
    public final ArrayList<ConditionalExpression> conditions = new ArrayList<>();
    public final ArrayList<Boolean> leftJoins = new ArrayList<>();

    public FromClause add(Table table, ConditionalExpression condition, boolean leftJoin) {
        tables.add(table);
        conditions.add(condition);
        leftJoins.add(leftJoin);
        return this;
    }

    public static FromClause cross(List<Table> tables) {
        FromClause clause = new FromClause();
        for (Table table : tables) { clause.add(table, null, false); }
        return clause;
    }
}
