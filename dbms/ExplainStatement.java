/** EXPLAIN QUERY PLAN resolves a SELECT without reading table rows. */
public final class ExplainStatement extends QueryStatement {
    private final QueryStatement query;
    public ExplainStatement(QueryStatement query) {
        this.query = query;
        for (int i = 0; i < query.numTables(); i++) { addTable(query.getTable(i)); }
    }
    protected void executeInternal() { result = query.explain(); }
    QueryStatement tail(java.util.List<OrderBy> order, Limit limit) { throw new IllegalArgumentException("EXPLAIN cannot have a query tail"); }
    QueryResult describe() { return new QueryResult(java.util.List.of("detail"),java.util.List.of()); }
    void collectTables(java.util.Set<String> tables, java.util.Set<String> cteNames) { query.collectTables(tables,cteNames); }
}
