import java.util.*;

/** Versioned JSON-friendly operations shared by the terminal and process client. */
public final class AdapterProtocol implements ClientProtocol {
    private static final List<String> OPERATIONS = List.of(
            "get_capabilities", "list_tables", "describe_table", "query_readonly", "get_result", "close");
    private final DatabaseAdapter adapter;
    private final QueryLimits ceilings;
    private final boolean schemaOnly;
    private boolean closed;

    public AdapterProtocol(DatabaseAdapter adapter, QueryLimits ceilings, boolean schemaOnly) {
        this.adapter = adapter;
        this.ceilings = ceilings;
        this.schemaOnly = schemaOnly;
    }

    public boolean isClosed() { return closed; }

    public Map<String, Object> handle(Object input) {
        Object id = null;
        try {
            Map<String, Object> request = object(input);
            Object requestedId = request.get("id");
            if (requestedId != null && !(requestedId instanceof String) && !(requestedId instanceof Long)) {
                throw new IllegalArgumentException("Request id must be a string or integer");
            }
            id = requestedId;
            String operation = string(request, "operation");
            if (closed) { return error(id, "CLOSED", "Client session is closed", null, null); }
            if (schemaOnly && (operation.equals("query_readonly") || operation.equals("get_result"))) {
                return error(id, "ACCESS_DENIED", "This session permits schema discovery only", null, null);
            }
            Object data;
            switch (operation) {
                case "get_capabilities":
                    fields(request, "id", "operation");
                    data = map("protocolVersion", 1, "schemaOnly", schemaOnly,
                            "operations", OPERATIONS.stream().filter(op -> !schemaOnly
                                    || (!op.equals("query_readonly") && !op.equals("get_result"))).toList(),
                            "database", adapter.getCapabilities());
                    break;
                case "list_tables":
                    fields(request, "id", "operation");
                    data = adapter.listTables();
                    break;
                case "describe_table":
                    fields(request, "id", "operation", "table");
                    data = adapter.describeTable(string(request, "table"));
                    break;
                case "query_readonly":
                    fields(request, "id", "operation", "sql", "parameters", "limits");
                    Object parameters = request.getOrDefault("parameters", List.of());
                    if (!(parameters instanceof List<?>)) { throw new IllegalArgumentException("Parameters must be an array"); }
                    data = result(adapter.queryReadOnly(string(request, "sql"), (List<?>) parameters,
                            limits(request.get("limits"))));
                    break;
                case "get_result":
                    fields(request, "id", "operation", "resultId");
                    data = result(adapter.getResult(string(request, "resultId")));
                    break;
                case "close":
                    fields(request, "id", "operation");
                    adapter.close();
                    closed = true;
                    data = map("closed", true);
                    break;
                default: throw new IllegalArgumentException("Unknown operation: " + operation);
            }
            return map("id", id, "ok", true, "operation", operation, "data", data);
        } catch (DatabaseAdapter.AdapterException e) {
            return error(id, e.getCode().name(), e.getMessage(), e.getRequestId(), e.getLimit());
        } catch (IllegalArgumentException e) {
            return error(id, "INVALID_REQUEST", e.getMessage(), null, null);
        } catch (RuntimeException e) {
            return error(id, "DATABASE_ERROR", "The database could not complete the request", null, null);
        }
    }

    private Map<String, Object> result(DatabaseAdapter.ReadResult result) {
        Map<String, Object> data = Json.record(result);
        data.put("returnedRows", result.returnedRows());
        data.put("truncated", result.truncated());
        data.put("complete", result.complete());
        return data;
    }

    private QueryLimits limits(Object value) {
        if (value == null) { return ceilings; }
        Map<String, Object> values = object(value);
        fields(values, "maxRows", "maxResultBytes", "maxScannedRows", "maxWorkingRows", "maxWorkingBytes", "timeoutMillis");
        return new QueryLimits(integer(values, "maxRows", ceilings.maxRows()),
                number(values, "maxResultBytes", ceilings.maxResultBytes()),
                number(values, "maxScannedRows", ceilings.maxScannedRows()),
                integer(values, "maxWorkingRows", ceilings.maxWorkingRows()),
                number(values, "maxWorkingBytes", ceilings.maxWorkingBytes()),
                number(values, "timeoutMillis", ceilings.timeoutMillis()));
    }

    static Map<String, Object> object(Object value) {
        if (!(value instanceof Map<?, ?>)) { throw new IllegalArgumentException("Expected a JSON object"); }
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
            if (!(entry.getKey() instanceof String)) { throw new IllegalArgumentException("Object keys must be strings"); }
            result.put((String) entry.getKey(), entry.getValue());
        }
        return result;
    }

    private static void fields(Map<String, Object> values, String... allowed) {
        Set<String> names = Set.of(allowed);
        for (String name : values.keySet()) {
            if (!names.contains(name)) { throw new IllegalArgumentException("Unknown field: " + name); }
        }
    }

    private static String string(Map<String, Object> request, String name) {
        Object value = request.get(name);
        if (!(value instanceof String) || ((String) value).isBlank()) {
            throw new IllegalArgumentException("Field '" + name + "' must be a non-empty string");
        }
        return (String) value;
    }

    private static long number(Map<String, Object> values, String name, long fallback) {
        if (!values.containsKey(name)) { return fallback; }
        if (!(values.get(name) instanceof Long)) { throw new IllegalArgumentException(name + " must be an integer"); }
        return (Long) values.get(name);
    }

    private static int integer(Map<String, Object> values, String name, int fallback) {
        long value = number(values, name, fallback);
        if (value < 1 || value > Integer.MAX_VALUE) { throw new IllegalArgumentException(name + " is out of range"); }
        return (int) value;
    }

    public static Map<String, Object> error(Object id, String code, String message, String requestId, String limit) {
        return map("id", id, "ok", false, "error", map(
                "code", code, "message", message, "requestId", requestId, "limit", limit));
    }

    public static Map<String, Object> map(Object... pairs) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) { result.put((String) pairs[i], pairs[i + 1]); }
        return result;
    }
}
