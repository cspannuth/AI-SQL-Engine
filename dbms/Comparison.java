import java.util.*;

/** Scalar comparisons with SQL three-valued null semantics. */
public class Comparison extends ConditionalExpression {
    public static final int EQ = 0, NOTEQ = 1, LT = 2, GT = 3, LTEQ = 4, GTEQ = 5;
    public static final int LIKE = 6, CLIKE = 7, IS_NULL = 8, IS_NOT_NULL = 9;
    public static final int IS = 10, IS_NOT = 11;
    private final int type;
    private final CompareTerm left;
    private final CompareTerm right;

    public Comparison(int type, CompareTerm left, CompareTerm right) {
        if (type < EQ || type > IS_NOT) { throw new IllegalArgumentException("Invalid comparison"); }
        this.type = type;
        this.left = left;
        this.right = right;
    }

    public CompareTerm getLeftTerm() { return left; }
    public CompareTerm getRightTerm() { return right; }
    public int getType() { return type; }

    public String toString() {
        String[] operators = {"=", "<>", "<", ">", "<=", ">=", "LIKE", "CLIKE",
                "IS NULL", "IS NOT NULL", "IS", "IS NOT"};
        return "(" + left + " " + operators[type] + (right == null ? "" : " " + right) + ")";
    }

    public Boolean evaluate() {
        Object a = left.getValue();
        Object b = right == null ? null : right.getValue();
        if (type == IS_NULL) { return a == null; }
        if (type == IS_NOT_NULL) { return a != null; }
        if (right != null && type != LIKE && type != CLIKE) {
            Object[] values = SqlValues.comparisonValues(left, right);
            a = values[0];
            b = values[1];
        }
        if (type == IS || type == IS_NOT) {
            boolean equal = a == null || b == null ? a == b : SqlValues.compare(a, b) == 0;
            return type == IS ? equal : !equal;
        }
        if (a == null || b == null) { return null; }
        if (type == LIKE || type == CLIKE) {
            return SqlValues.like(a.toString(), b.toString(), false);
        }
        int order = SqlValues.compare(a, b);
        switch (type) {
            case EQ: return order == 0;
            case NOTEQ: return order != 0;
            case LT: return order < 0;
            case GT: return order > 0;
            case LTEQ: return order <= 0;
            case GTEQ: return order >= 0;
            default: throw new IllegalStateException("Unknown comparison");
        }
    }

    public List<Column> getColumns() {
        List<Column> columns = new ArrayList<>(left.getColumns());
        if (right != null) { columns.addAll(right.getColumns()); }
        return columns;
    }

    public List<AggregateTerm> getAggregates() {
        List<AggregateTerm> aggregates = new ArrayList<>(left.getAggregates());
        if (right != null) { aggregates.addAll(right.getAggregates()); }
        return aggregates;
    }
}
