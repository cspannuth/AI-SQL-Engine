import java.util.*;

/** A SQL predicate. null is UNKNOWN; WHERE accepts only Boolean.TRUE. */
public abstract class ConditionalExpression extends CompareTerm {
    public List<QueryStatement> getSubqueries() { return CompareTerm.queries(left, right); }
    private final ConditionalExpression left;
    private final ConditionalExpression right;

    public ConditionalExpression() { this(null, null); }
    public ConditionalExpression(ConditionalExpression left, ConditionalExpression right) {
        this.left = left;
        this.right = right;
    }

    public ConditionalExpression getLeft() { return left; }
    public ConditionalExpression getRight() { return right; }
    public final boolean isTrue() { return Boolean.TRUE.equals(evaluate()); }
    public abstract Boolean evaluate();
    public SqlType getResultType() { return SqlType.INTEGER; }

    /** SQL exposes predicate values as 1, 0, or NULL in scalar contexts. */
    public final Object getValue() {
        Boolean value = evaluate();
        return value == null ? null : value ? 1L : 0L;
    }

    public List<Column> getColumns() {
        List<Column> columns = new ArrayList<>();
        if (left != null) { columns.addAll(left.getColumns()); }
        if (right != null) { columns.addAll(right.getColumns()); }
        return columns;
    }

    public List<AggregateTerm> getAggregates() {
        List<AggregateTerm> aggregates = new ArrayList<>();
        if (left != null) { aggregates.addAll(left.getAggregates()); }
        if (right != null) { aggregates.addAll(right.getAggregates()); }
        return aggregates;
    }
}
