import java.util.List;

/** A scalar SQL expression; columns and arithmetic expressions extend this class. */
public class CompareTerm {
    public static final int INTEGER = 0, REAL = 1, STRING = 2;
    private final Object value;
    private int valType;

    public CompareTerm() { value = null; valType = -1; }
    public CompareTerm(Object value) {
        this.value = value;
        valType = value instanceof Float || value instanceof Double ? REAL
                : value instanceof Number ? INTEGER : STRING;
    }

    public void setValType(int type) { valType = type; }
    public int getValType() { return valType; }
    public Object getValue() { return value; }
    public SqlType getResultType() { return SqlType.of(getValue()); }
    public List<Column> getColumns() { return List.of(); }
    public List<AggregateTerm> getAggregates() { return List.of(); }
    public List<QueryStatement> getSubqueries() { return List.of(); }
    public SqlType getAffinity() { return SqlType.UNKNOWN; }
    static List<QueryStatement> queries(CompareTerm... expressions) {
        java.util.ArrayList<QueryStatement> result = new java.util.ArrayList<>();
        for (CompareTerm expression : expressions) {
            if (expression != null) { result.addAll(expression.getSubqueries()); }
        }
        return result;
    }
    public String toString() { return value == null ? "NULL" : value.toString(); }
}
