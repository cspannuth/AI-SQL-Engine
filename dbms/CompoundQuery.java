import java.util.*;

/** UNION [ALL], INTERSECT, and EXCEPT associate left-to-right as in SQLite. */
final class CompoundQuery extends QueryStatement {
    private final QueryStatement left;
    private final QueryStatement right;
    private final String operation;
    private List<OrderBy> order = List.of();
    private Limit limit;
    CompoundQuery(QueryStatement left, String operation, QueryStatement right) {
        this.left = left;
        this.right = right;
        this.operation = operation;
    }
    QueryStatement tail(List<OrderBy> order, Limit limit) { this.order = List.copyOf(order); this.limit = limit; return this; }
    QueryResult describe() {
        QueryResult a = left.describe(), b = right.describe();
        width(a,b);
        return header(a,b);
    }
    protected void executeInternal() {
        QueryResult a = left.nested(), b = right.nested();
        width(a,b);
        List<List<Object>> rows = new ArrayList<>();
        if (operation.equals("UNION ALL")) { rows.addAll(a.getRows()); rows.addAll(b.getRows()); }
        else {
            Set<List<Object>> rightKeys = new HashSet<>();
            for (List<Object> row : b.getRows()) { rightKeys.add(QueryResults.key(row)); }
            Set<List<Object>> emitted = new HashSet<>();
            for (List<Object> row : a.getRows()) {
                List<Object> key = QueryResults.key(row);
                boolean keep = operation.equals("UNION") || operation.equals("INTERSECT") == rightKeys.contains(key);
                if (keep && emitted.add(key)) { rows.add(row); }
            }
            if (operation.equals("UNION")) {
                for (List<Object> row : b.getRows()) { if (emitted.add(QueryResults.key(row))) { rows.add(row); } }
            }
        }
        for (List<Object> row : rows) { QueryBudget.reserveWorkingRow(QueryBudget.payloadBytes(row)); }
        result = QueryResults.finish(header(a,b),rows,order,limit);
    }
    private static QueryResult header(QueryResult a,QueryResult b) {
        List<SqlType> types=new ArrayList<>();
        for(int i=0;i<a.getColumns().size();i++) { types.add(SqlType.merge(a.getColumnTypes().get(i),b.getColumnTypes().get(i))); }
        return new QueryResult(a.getColumns(),types,List.of(),null,a.getAffinities());
    }
    private static void width(QueryResult a, QueryResult b) {
        if (a.getColumns().size() != b.getColumns().size()) { throw new IllegalArgumentException("Compound query column counts differ"); }
    }
    void collectTables(Set<String> tables, Set<String> cteNames) {
        left.collectTables(tables,cteNames);
        right.collectTables(tables,cteNames);
    }
}
