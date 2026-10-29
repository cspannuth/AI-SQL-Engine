import java.util.*;

/** Simple numeric arithmetic and SQLite-style string concatenation. */
public class ArithmeticTerm extends CompareTerm {
    public List<QueryStatement> getSubqueries() { return CompareTerm.queries(left, right); }
    private final String operator;
    private final CompareTerm left;
    private final CompareTerm right;

    public ArithmeticTerm(String operator, CompareTerm left, CompareTerm right) {
        this.operator = operator;
        this.left = left;
        this.right = right;
    }

    public Object getValue() {
        Object a = left.getValue();
        Object b = right == null ? null : right.getValue();
        if (a == null || (right != null && b == null)) { return null; }
        if (operator.equals("||")) {
            QueryBudget.checkValueSize(QueryBudget.payloadBytes(a) + QueryBudget.payloadBytes(b));
            return a.toString() + b.toString();
        }
        Number x = SqlValues.number(a);
        Number y = b == null ? null : SqlValues.number(b);
        boolean integral = !(x instanceof Double) && (y == null || !(y instanceof Double));
        if (operator.equals("negate")) {
            if (integral && x.longValue() != Long.MIN_VALUE) { return -x.longValue(); }
            return -x.doubleValue();
        }
        if ((operator.equals("/") || operator.equals("%")) && y.doubleValue() == 0) { return null; }
        if (operator.equals("%") && y.longValue() == 0) { return null; }
        if (integral) {
            try {
                switch (operator) {
                    case "+": return Math.addExact(x.longValue(), y.longValue());
                    case "-": return Math.subtractExact(x.longValue(), y.longValue());
                    case "*": return Math.multiplyExact(x.longValue(), y.longValue());
                    case "/":
                        if (x.longValue() != Long.MIN_VALUE || y.longValue() != -1) {
                            return x.longValue() / y.longValue();
                        }
                        break;
                    case "%": return x.longValue() % y.longValue();
                }
            } catch (ArithmeticException overflow) {
                // SQLite promotes overflowing integer arithmetic to REAL.
            }
        }
        switch (operator) {
            case "+": return x.doubleValue() + y.doubleValue();
            case "-": return x.doubleValue() - y.doubleValue();
            case "*": return x.doubleValue() * y.doubleValue();
            case "/": return x.doubleValue() / y.doubleValue();
            case "%": return (double) (x.longValue() % y.longValue());
            default: throw new IllegalArgumentException("Unknown arithmetic operator: " + operator);
        }
    }

    public List<Column> getColumns() {
        List<Column> columns = new ArrayList<>(left.getColumns());
        if (right != null) { columns.addAll(right.getColumns()); }
        return columns;
    }

    public SqlType getResultType() { return operator.equals("||") ? SqlType.TEXT : SqlType.NUMERIC; }
    boolean hasLiteralValue() { return right == null && left.hasLiteralValue(); }

    public List<AggregateTerm> getAggregates() {
        List<AggregateTerm> aggregates = new ArrayList<>(left.getAggregates());
        if (right != null) { aggregates.addAll(right.getAggregates()); }
        return aggregates;
    }

    public String toString() {
        return right == null ? "-" + left : "(" + left + operator + right + ")";
    }
}
