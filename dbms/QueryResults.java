import java.util.*;

/** Apply compound-query ordering, limits, and the caller's final preview bounds. */
final class QueryResults {
    static QueryResult finish(QueryResult header, List<List<Object>> input, List<OrderBy> order, Limit limit) {
        List<List<Object>> rows = new ArrayList<>(input);
        List<Integer> positions = new ArrayList<>();
        for (OrderBy item : order) {
            int position = -1;
            if (item.expression instanceof IntegerLiteral) {
                Object value = item.expression.getValue();
                if (value instanceof Long && (Long)value > 0 && (Long)value <= header.getColumns().size()) {
                    position = (int)((Long)value - 1);
                }
            } else if (item.expression instanceof Column && ((Column)item.expression).getTable() == null) {
                for (int i = 0; i < header.getColumns().size(); i++) {
                    if (header.getColumns().get(i).equalsIgnoreCase(((Column)item.expression).getName())) { position = i; break; }
                }
            }
            if (position < 0 || position >= header.getColumns().size()) {
                throw new IllegalArgumentException("Compound ORDER BY must name an output column or position");
            }
            positions.add(position);
        }
        if (!order.isEmpty()) {
            rows.sort((a,b) -> {
                QueryBudget.check();
                for (int i = 0; i < order.size(); i++) {
                    int comparison = SqlValues.compare(a.get(positions.get(i)),b.get(positions.get(i)));
                    if (comparison != 0) { return order.get(i).descending ? -comparison : comparison; }
                }
                return 0;
            });
        }
        int start = limit == null ? 0 : (int)Math.min(rows.size(),limit.getOffset());
        long count = limit == null ? rows.size() : Math.min(rows.size()-start,limit.getMax());
        int end = start + (int)Math.min(count,QueryBudget.resultRowLimit());
        String truncated = count > QueryBudget.resultRowLimit() ? "ROWS" : null;
        long bytes = 8 + QueryBudget.payloadBytes(header.getColumns()) + QueryBudget.payloadBytes(header.getColumnTypes());
        if (bytes > QueryBudget.resultByteLimit()) { throw new QueryBudget.Exceeded("result header bytes"); }
        List<List<Object>> output = new ArrayList<>();
        for (int i = start; i < end; i++) {
            long size = QueryBudget.payloadBytes(rows.get(i));
            if (size > QueryBudget.resultByteLimit()-bytes) { truncated = "BYTES"; break; }
            bytes += size;
            output.add(rows.get(i));
        }
        return new QueryResult(header.getColumns(),header.getColumnTypes(),output,truncated,header.getAffinities());
    }
    static List<Object> key(List<Object> row) {
        List<Object> key = new ArrayList<>();
        for (Object value : row) { key.add(SqlValues.key(value)); }
        return key;
    }
}
