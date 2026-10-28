import java.util.*;

/** Read-query AST shared by SELECT, compounds, and WITH. */
public abstract class QueryStatement extends SQLStatement {
    private int expectedColumns;
    private QueryResult description;
    protected QueryStatement() { super(); }
    protected QueryStatement(ArrayList<Table> tables, ArrayList<Column> columns, ConditionalExpression where) {
        super(tables, columns, where);
    }
    final QueryResult nested() {
        try (QueryContext.Scope active = QueryContext.enter(this)) {
            return QueryBudget.internalResults(() -> { executeInternal(); checkWidth(result); return result; });
        }
    }
    final QueryStatement expectColumns(int count) { expectedColumns = count; return this; }
    final QueryResult describeChecked() { description = describe(); checkWidth(description); return description; }
    final QueryResult description() { return description; }
    private void checkWidth(QueryResult result) {
        if (expectedColumns != 0 && result.getColumns().size() != expectedColumns) {
            throw new IllegalArgumentException("Subquery must return " + expectedColumns + " column(s)");
        }
    }
    abstract QueryStatement tail(List<OrderBy> order, Limit limit);
    abstract QueryResult describe();
    abstract void collectTables(Set<String> tables, Set<String> cteNames);
    public final Set<String> readTables() {
        Set<String> tables = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        collectTables(tables, new TreeSet<>(String.CASE_INSENSITIVE_ORDER));
        return tables;
    }
    QueryResult explain() { return new QueryResult(List.of("detail"),List.of(List.of(getClass().getSimpleName()))); }
}
