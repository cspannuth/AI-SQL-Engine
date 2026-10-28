import java.util.*;

final class InQueryExpression extends ConditionalExpression {
    private final CompareTerm expression;
    private final QueryStatement query;
    InQueryExpression(CompareTerm expression, QueryStatement query) { this.expression = expression; this.query = query.expectColumns(1); }
    public Boolean evaluate() {
        QueryResult result = query.nested();
        if (result.getColumns().size() != 1) { throw new IllegalArgumentException("IN subquery must return one column"); }
        Object value = expression.getValue();
        boolean unknown = value == null && !result.getRows().isEmpty();
        for (List<Object> row : result.getRows()) {
            Object other = row.get(0);
            if (other == null) { unknown = true; }
            else if (value != null) {
                CompareTerm candidate = new CompareTerm(other) {
                    public SqlType getAffinity() { return result.getAffinities().get(0); }
                };
                Object[] compared = SqlValues.comparisonValues(expression,candidate);
                if (SqlValues.compare(compared[0],compared[1]) == 0) { return true; }
            }
        }
        return unknown ? null : false;
    }
    public List<Column> getColumns() { return expression.getColumns(); }
    public List<AggregateTerm> getAggregates() { return expression.getAggregates(); }
    public List<QueryStatement> getSubqueries() {
        List<QueryStatement> queries = new ArrayList<>(expression.getSubqueries());
        queries.add(query);
        return queries;
    }
}
