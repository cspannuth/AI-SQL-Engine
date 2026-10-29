import java.util.*;

/** Compile stored SQL with the normal parser and evaluate against a proposed row. */
final class CheckConstraint {
    private final String sql;
    private final CompareTerm expression;
    private final String name;

    CheckConstraint(String sql, Table table, TableIterator iterator) {
        this.sql = sql;
        name = table.getConstraints().stream().filter(constraint -> constraint.kind().equals("CHECK")
                && sql.equals(constraint.expression()) && constraint.name() != null)
                .map(SchemaConstraint::name).findFirst().orElse(null);
        expression = ((SelectStatement) DBMS.parse("SELECT " + sql + ";", List.of())).singleExpression();
        if (!expression.getAggregates().isEmpty() || !expression.getSubqueries().isEmpty()) {
            throw new IllegalArgumentException("Aggregates and subqueries are not allowed in CHECK");
        }
        for (Column reference : expression.getColumns()) {
            Column match = table.resolveColumn(reference);
            if (match == null) { throw new IllegalArgumentException("Unknown CHECK column: " + reference); }
            reference.useColInfo(match);
            reference.setTableIterator(iterator);
        }
    }

    void validate() {
        try (QueryContext.Scope scope = DateFunctions.checkingSchema()) {
            Object value = expression.getValue();
            if (value != null && SqlValues.number(value).doubleValue() == 0) {
                throw new IllegalArgumentException("CHECK constraint failed: " + (name == null ? sql : name));
            }
        }
    }
    boolean references(String column) {
        return expression.getColumns().stream().anyMatch(reference -> reference.getName().equalsIgnoreCase(column));
    }
}
