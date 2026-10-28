import java.util.*;

/** Scalar IN lists, including SQL's UNKNOWN result when a list contains NULL. */
public class InExpression extends ConditionalExpression {
    public List<QueryStatement> getSubqueries() {
        List<QueryStatement> queries = new ArrayList<>(expression.getSubqueries());
        for (CompareTerm value : values) { queries.addAll(value.getSubqueries()); }
        return queries;
    }
    private final CompareTerm expression;
    private final List<CompareTerm> values;

    public InExpression(CompareTerm expression, List<CompareTerm> values) {
        this.expression = expression;
        this.values = values;
    }

    public String toString() { return expression + " IN " + values; }

    public Boolean evaluate() {
        Object value = expression.getValue();
        if (values.isEmpty()) { return false; }
        boolean unknown = value == null;
        for (CompareTerm candidate : values) {
            Object other = candidate.getValue();
            if (other == null) { unknown = true; }
            else if (value != null) {
                // List operands have no column affinity in SQLite's IN operator.
                Object[] compared = SqlValues.comparisonValues(expression, new CompareTerm(other));
                if (SqlValues.compare(compared[0], compared[1]) == 0) { return true; }
            }
        }
        return unknown ? null : false;
    }

    public List<Column> getColumns() {
        List<Column> columns = new ArrayList<>(expression.getColumns());
        for (CompareTerm value : values) { columns.addAll(value.getColumns()); }
        return columns;
    }

    public List<AggregateTerm> getAggregates() {
        List<AggregateTerm> aggregates = new ArrayList<>(expression.getAggregates());
        for (CompareTerm value : values) { aggregates.addAll(value.getAggregates()); }
        return aggregates;
    }
}
