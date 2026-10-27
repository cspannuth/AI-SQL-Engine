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
    public List<Column> getColumns() { return List.of(); }
    public List<AggregateTerm> getAggregates() { return List.of(); }
    public String toString() { return value == null ? "NULL" : value.toString(); }
}
