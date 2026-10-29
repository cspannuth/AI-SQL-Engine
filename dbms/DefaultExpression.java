import java.util.List;

/** A stored default is scalar SQL, evaluated only for an omitted INSERT value. */
final class DefaultExpression {
    private final String sql;
    private final CompareTerm expression;

    DefaultExpression(String sql) {
        this.sql = sql;
        expression = ((SelectStatement) DBMS.parse("SELECT " + sql + ";", List.of())).singleExpression();
        if (!expression.getColumns().isEmpty() || !expression.getAggregates().isEmpty()
                || !expression.getSubqueries().isEmpty()) {
            throw new IllegalArgumentException("DEFAULT cannot reference columns, aggregates, or queries");
        }
    }

    String sql() { return sql; }
    Object evaluate() { return expression.getValue(); }

    /** ADD COLUMN permits literal values and their signs/casts, rather than general calculations. */
    boolean isLiteral() { return expression.hasLiteralValue(); }
}
