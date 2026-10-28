import java.util.*;

/** A materialized derived table or ordinary CTE; never a writable storage table. */
final class QueryTable extends Table {
    final QueryStatement query;
    private final List<String> names;
    private List<List<Object>> rows;

    QueryTable(QueryStatement query, List<String> names, String name) {
        super(name);
        this.query = query;
        this.names = List.copyOf(names);
    }
    public com.sleepycat.je.OperationStatus open() {
        QueryResult result;
        if (QueryContext.isDescribing()) {
            try (QueryContext.Scope active = QueryContext.enter(query)) { result = query.describe(); }
        } else { result = query.nested(); }
        if (!names.isEmpty() && names.size() != result.getColumns().size()) {
            throw new IllegalArgumentException("CTE column count does not match its query");
        }
        clearColumns();
        Set<String> seen = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        for (int i = 0; i < result.getColumns().size(); i++) {
            String base = names.isEmpty() ? result.getColumns().get(i) : names.get(i);
            String label = base;
            for (int suffix = 1; !seen.add(label); suffix++) { label = base + ":" + suffix; }
            Column column = new Column(label, this);
            SqlType type = result.getColumnTypes().get(i);
            column.setType(type == SqlType.INTEGER ? Column.INTEGER : type == SqlType.REAL ? Column.REAL : Column.VARCHAR);
            column.setLength(column.getType() <= Column.REAL ? 8 : 32767);
            column.setQueryType(type, result.getAffinities().get(i));
            addColumn(column);
        }
        rows = result.getRows();
        return com.sleepycat.je.OperationStatus.SUCCESS;
    }
    public List<List<Object>> memoryRows() { return rows; }
}
