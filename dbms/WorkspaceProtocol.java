import java.time.Instant;
import java.util.*;

/** Trusted local console commands are deliberately separate from read-only tool operations. */
final class WorkspaceProtocol implements ClientProtocol {
    private final DatabaseAdapter adapter;
    private final AdapterProtocol reads;
    private final QueryLimits limits;

    WorkspaceProtocol(DatabaseAdapter adapter, AdapterProtocol reads, QueryLimits limits) {
        this.adapter = adapter; this.reads = reads; this.limits = limits;
    }

    public boolean isClosed() { return reads.isClosed(); }

    public Map<String, Object> handle(Object input) {
        Object id = null;
        try {
            Map<String, Object> request = AdapterProtocol.object(input);
            String operation = Objects.toString(request.get("operation"), "");
            if (!Set.of("execute_sql", "workspace_status").contains(operation)) { return reads.handle(input); }
            id = request.get("id");
            if (id != null && !(id instanceof String) && !(id instanceof Long)) { throw new IllegalArgumentException("Invalid request ID"); }
            if (isClosed()) { throw new IllegalStateException("Workspace is closed"); }
            Set<String> fields = operation.equals("execute_sql") ? Set.of("id","operation","sql","parameters") : Set.of("id","operation");
            if (!fields.containsAll(request.keySet())) { throw new IllegalArgumentException("Unknown workspace request field"); }
            Object data = operation.equals("workspace_status") ? AdapterProtocol.map("transactionActive", DBMS.getTransaction() != null)
                    : execute(request);
            return AdapterProtocol.map("id", id, "ok", true, "operation", operation, "data", data);
        } catch (DatabaseAdapter.AdapterException error) {
            return AdapterProtocol.error(id, error.getCode().name(), error.getMessage(), null, error.getLimit());
        } catch (QueryBudget.Exceeded error) {
            return AdapterProtocol.error(id, "LIMIT_EXCEEDED", error.getMessage(), null, error.getLimit());
        } catch (IllegalArgumentException error) {
            return AdapterProtocol.error(id, "SQL_ERROR", error.getMessage(), null, null);
        } catch (RuntimeException error) {
            return AdapterProtocol.error(id, "DATABASE_ERROR", "Workspace execution failed; inspect state before retrying", null, null);
        }
    }

    private Object execute(Map<String, Object> request) {
        Object sqlValue = request.get("sql"), parameters = request.getOrDefault("parameters", List.of());
        if (!(sqlValue instanceof String) || !(parameters instanceof List<?>)) {
            throw new IllegalArgumentException("SQL must be text and parameters must be an array");
        }
        String sql = (String) sqlValue;
        List<Object> bound = adapter.validateRequest(sql, (List<?>) parameters);
        long started = System.nanoTime();
        try (QueryBudget budget = QueryBudget.start(limits)) {
            SQLStatement statement = DBMS.parse(sql, bound);
            statement.execute();
            // Do not raise a new timeout after a write has already committed. Result previews
            // below describe successful execution even when their output must be shortened.
            QueryResult result = statement.getResult();
            List<Object> columns = new ArrayList<>();
            for (int i = 0; i < result.getColumns().size(); i++) {
                columns.add(AdapterProtocol.map("name", result.getColumns().get(i), "type", result.getColumnTypes().get(i)));
            }
            List<List<Object>> rows = new ArrayList<>();
            long bytes = Json.stringify(columns).getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
            String reason = result.getTruncationReason();
            for (List<Object> row : result.getRows()) {
                if (rows.size() >= limits.maxRows()) { reason = "ROWS"; break; }
                long size = Json.stringify(row).getBytes(java.nio.charset.StandardCharsets.UTF_8).length + 1L;
                if (size > limits.maxResultBytes() - bytes) { reason = "BYTES"; break; }
                rows.add(row); bytes += size;
            }
            return AdapterProtocol.map("sql", sql, "parameters", bound, "columns", columns, "rows", rows,
                    "readOnly", statement instanceof QueryStatement,
                    "returnedRows", rows.size(), "affectedRows", result.getAffectedRows(), "complete", reason == null,
                    "truncated", reason != null, "truncationReason", reason, "capturedAt", Instant.now(),
                    "elapsedMillis", (System.nanoTime() - started) / 1000000, "scannedRows", budget.getScannedRows(),
                    "transactionActive", DBMS.getTransaction() != null);
        }
    }
}
