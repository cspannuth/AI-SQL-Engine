import java.nio.charset.StandardCharsets;
import java.io.*;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.function.Function;

/**
 * A local, read-only application boundary around the engine's single session.
 * Trusted host code creates the policy after identifying its caller. This class
 * provides authorization, not authentication or a network transport.
 */
public final class DatabaseAdapter implements AutoCloseable {
    public static final int MAX_REQUEST_BYTES = 65_536;
    public static final int MAX_PARAMETERS = 256;
    public static final int MAX_SQL_TOKENS = 512;
    public static final int MAX_SQL_NESTING = 32;

    public enum ErrorCode {
        INVALID_REQUEST, SQL_ERROR, ACCESS_DENIED, LIMIT_EXCEEDED,
        NOT_FOUND, CLOSED, TRANSACTION_ACTIVE, DATABASE_ERROR
    }

    /** Stable error code for a future transport; internal causes stay local. */
    public static class AdapterException extends RuntimeException {
        private final ErrorCode code;
        private final String requestId;
        private final String limit;
        AdapterException(ErrorCode code, String message, String requestId, Throwable cause) {
            super(message, cause);
            this.code = code;
            this.requestId = requestId;
            limit = cause instanceof QueryBudget.Exceeded ? ((QueryBudget.Exceeded) cause).getLimit()
                    : cause instanceof AdapterException ? ((AdapterException) cause).getLimit() : null;
        }
        public ErrorCode getCode() { return code; }
        public String getRequestId() { return requestId; }
        public String getLimit() { return limit; }
    }

    /** An explicit allow-all flag avoids giving a special meaning to table names. */
    public record AccessPolicy(String callerId, Set<String> allowedTables, boolean allTables) {
        public AccessPolicy {
            if (callerId == null || callerId.isBlank()) { throw new IllegalArgumentException("Caller ID is required"); }
            Set<String> copied = new HashSet<>();
            for (String table : Objects.requireNonNull(allowedTables)) {
                if (table == null || table.isEmpty()) { throw new IllegalArgumentException("Table name is required"); }
                copied.add(table);
            }
            allowedTables = Set.copyOf(copied);
        }
        public static AccessPolicy readTables(String callerId, Set<String> tables) {
            return new AccessPolicy(callerId, tables, false);
        }
        public static AccessPolicy readAll(String callerId) {
            return new AccessPolicy(callerId, Set.of(), true);
        }
        boolean permits(String table) {
            // Match the catalog's identifier comparison, including quoted Unicode names.
            return allTables || allowedTables.stream().anyMatch(allowed -> allowed.equalsIgnoreCase(table));
        }
    }

    /** Artifacts are private to this adapter instance and expire without renewal. */
    public record Retention(int maxResults, long maxBytes, Duration ttl) {
        public Retention {
            if (maxResults < 1 || maxBytes < 1 || ttl == null || ttl.isNegative() || ttl.isZero()
                    || ttl.compareTo(Duration.ofDays(365)) > 0) {
                throw new IllegalArgumentException("Retention requires positive bounds and a TTL of at most one year");
            }
        }
        public static Retention defaults() { return new Retention(20, 33_554_432, Duration.ofMinutes(10)); }
    }

    public record ColumnSchema(String name, String type, int byteLength, boolean nullable, boolean primaryKey,
            boolean unique, boolean hasDefault, Object defaultValue, String defaultSql) { }
    public record TableSchema(String name, List<ColumnSchema> columns, boolean rowIdTable, String rowIdColumn,
            List<String> checks, List<ForeignKey> foreignKeys, List<IndexDefinition> indexes,
            List<String> primaryKeyColumns,List<List<String>> uniqueKeys, List<SchemaConstraint> constraints) {
        public TableSchema {
            columns = List.copyOf(columns);
            checks = List.copyOf(checks);
            foreignKeys = List.copyOf(foreignKeys);
            indexes = List.copyOf(indexes);
            primaryKeyColumns=List.copyOf(primaryKeyColumns);
            uniqueKeys=uniqueKeys.stream().map(List::copyOf).toList();
            constraints = List.copyOf(constraints);
        }
    }
    public record ResultColumn(String name, SqlType type) { }
    public record Capabilities(int apiVersion, String dialect, List<String> features,
            List<String> limitations, QueryLimits maximumLimits, Retention retention,
            int maxRequestBytes, int maxParameters, int maxSqlTokens, int maxSqlNesting) {
        public Capabilities { features = List.copyOf(features); limitations = List.copyOf(limitations); }
    }

    /** A snapshot of returned values; completeness is relative to the submitted SQL. */
    public record ReadResult(String requestId, String resultId, String databaseId, String callerId,
            String sql, List<Object> parameters, List<ResultColumn> columns, List<List<Object>> rows,
            String truncationReason, Instant capturedAt, Instant expiresAt, long elapsedMillis,
            long scannedRows, long workingRows, long workingBytes, long payloadBytes, QueryLimits limits) {
        public ReadResult {
            parameters = Collections.unmodifiableList(new ArrayList<>(parameters));
            columns = List.copyOf(columns);
            List<List<Object>> copied = new ArrayList<>();
            for (List<Object> row : rows) {
                copied.add(Collections.unmodifiableList(new ArrayList<>(row)));
            }
            rows = Collections.unmodifiableList(copied);
        }
        public boolean truncated() { return truncationReason != null; }
        public boolean complete() { return !truncated(); }
        public int returnedRows() { return rows.size(); }
    }

    private final AccessPolicy policy;
    private final QueryLimits maximumLimits;
    private final Retention retention;
    private final Clock clock;
    private final Object environment;
    private final String databaseId = UUID.randomUUID().toString();
    private final LinkedHashMap<String, ReadResult> results = new LinkedHashMap<>();
    private long retainedBytes;
    private boolean closed;

    public static DatabaseAdapter open(Path home, AccessPolicy policy) {
        return open(home, policy, QueryLimits.defaults(), Retention.defaults());
    }

    public static DatabaseAdapter open(Path home, AccessPolicy policy, QueryLimits limits, Retention retention) {
        return new DatabaseAdapter(home, policy, limits, retention, Clock.systemUTC());
    }

    // Clock injection makes expiry tests deterministic without sleeps.
    DatabaseAdapter(Path home, AccessPolicy policy, QueryLimits limits, Retention retention, Clock clock) {
        this(home, policy, limits, retention, clock, false);
    }

    /** The trusted workspace opts into writable startup; ordinary adapter opens remain read-only. */
    static DatabaseAdapter openWorkspace(Path home, QueryLimits limits, Retention retention) {
        return new DatabaseAdapter(home, AccessPolicy.readAll("local-workspace"), limits, retention, Clock.systemUTC(), true);
    }

    private DatabaseAdapter(Path home, AccessPolicy policy, QueryLimits limits, Retention retention, Clock clock,
            boolean workspace) {
        this.policy = Objects.requireNonNull(policy);
        maximumLimits = Objects.requireNonNull(limits);
        this.retention = Objects.requireNonNull(retention);
        this.clock = Objects.requireNonNull(clock);
        synchronized (DBMS.class) {
            if (DBMS.getEnv() != null) {
                throw error(ErrorCode.INVALID_REQUEST, "Another database session is already open", null, null);
            }
            try { DBMS.init(Objects.requireNonNull(home), workspace); }
            catch (RuntimeException e) { throw error(ErrorCode.DATABASE_ERROR, "Could not open the database", null, e); }
            environment = DBMS.getEnv();
        }
    }

    public Capabilities getCapabilities() {
        return read(null, maximumLimits, budget -> new Capabilities(1, "custom SQLite-oriented SQL subset",
                List.of("SELECT", "schema discovery", "positional scalar parameters", "INNER/CROSS/LEFT joins",
                        "GROUP BY/HAVING", "COUNT/SUM/AVG/TOTAL/MIN/MAX", "DISTINCT/ORDER BY/LIMIT",
                        "scalar/correlated subqueries and EXISTS", "ordinary WITH CTEs and derived tables",
                        "UNION/UNION ALL/INTERSECT/EXCEPT", "CASE/CAST and scalar text/numeric/UTC functions",
                        "persistent equality indexes and EXPLAIN QUERY PLAN",
                        "rowid tables for new schemas", "row/byte previews", "scan/working-data budgets",
                        "DEFAULT/CHECK/composite-key/reference-action/index metadata",
                        "expression/time default SQL and named declaration metadata",
                        "cooperative deadline", "result retrieval"),
                List.of("read-only adapter", "one serialized session", "no recursive CTEs or window functions",
                        "no persistent result history", "no snapshot version or multi-user authentication",
                        "working bytes estimate payload rather than total heap", "deadline cannot preempt blocking code"),
                maximumLimits, retention, MAX_REQUEST_BYTES, MAX_PARAMETERS, MAX_SQL_TOKENS, MAX_SQL_NESTING));
    }

    public List<String> listTables() {
        return read(null, maximumLimits, budget -> {
            List<String> allowed = new ArrayList<>();
            for (String table : Catalog.listTableNames()) {
                if (policy.permits(table)) {
                    if (allowed.size() >= maximumLimits.maxRows()) { throw new QueryBudget.Exceeded("schema rows"); }
                    allowed.add(table);
                }
            }
            return List.copyOf(allowed);
        });
    }

    public TableSchema describeTable(String name) {
        return read(null, maximumLimits, budget -> {
            if (name == null || name.isEmpty()) { throw error(ErrorCode.INVALID_REQUEST, "Table name is required", null, null); }
            authorize(name);
            Table table = new Table(name);
            if (Catalog.getMetadata(table) != com.sleepycat.je.OperationStatus.SUCCESS) {
                throw error(ErrorCode.NOT_FOUND, "Table was not found", null, null);
            }
            List<ColumnSchema> columns = new ArrayList<>();
            String[] types = {"INTEGER", "REAL", "CHAR", "VARCHAR"};
            for (int i = 0; i < table.numColumns(); i++) {
                Column column = table.getColumn(i);
                boolean keyNotNull = column.isPrimaryKey() && !table.usesRowIds()
                        || i == table.getRowIdColumn();
                columns.add(new ColumnSchema(column.getName(), types[column.getType()], column.getLength(),
                        !column.isNotNull() && !keyNotNull, column.isPrimaryKey(), column.isUnique(),
                        column.hasDefault(), column.getDefaultValue(), column.getDefaultSql()));
            }
            String rowIdColumn = table.getRowIdColumn() < 0 ? null
                    : table.getColumn(table.getRowIdColumn()).getName();
            return new TableSchema(table.getName(), columns, table.usesRowIds(), rowIdColumn,
                    table.getChecks(), table.getForeignKeys(), IndexCatalog.forTable(table.getName()),
                    table.getPrimaryKeyColumns(),table.getUniqueKeys(),table.getConstraints());
        });
    }

    public ReadResult queryReadOnly(String sql) { return queryReadOnly(sql, List.of(), maximumLimits); }
    public ReadResult queryReadOnly(String sql, List<?> parameters) {
        return queryReadOnly(sql, parameters, maximumLimits);
    }

    public ReadResult queryReadOnly(String sql, List<?> parameters, QueryLimits limits) {
        String requestId = UUID.randomUUID().toString();
        return read(requestId, limits, budget -> {
            long started = System.nanoTime();
            List<Object> bound = validateRequest(sql, parameters);
            SQLStatement statement = DBMS.parse(sql, bound);
            if (!(statement instanceof QueryStatement)) {
                throw error(ErrorCode.ACCESS_DENIED, "Only read queries are allowed", requestId, null);
            }
            for (String table : ((QueryStatement)statement).readTables()) { authorize(table); }
            statement.execute();
            QueryBudget.check();
            QueryResult result = statement.getResult();
            List<ResultColumn> columns = new ArrayList<>();
            for (int i = 0; i < result.getColumns().size(); i++) {
                columns.add(new ResultColumn(result.getColumns().get(i), result.getColumnTypes().get(i)));
            }
            long bytes = QueryBudget.payloadBytes(result.getRows()) + QueryBudget.payloadBytes(result.getColumns())
                    + QueryBudget.payloadBytes(result.getColumnTypes()) + QueryBudget.payloadBytes(bound)
                    + sql.getBytes(StandardCharsets.UTF_8).length;
            Instant captured = clock.instant();
            ReadResult artifact = new ReadResult(requestId, UUID.randomUUID().toString(), databaseId, policy.callerId(),
                    sql, bound, columns, result.getRows(), result.getTruncationReason(), captured, captured.plus(retention.ttl()),
                    (System.nanoTime() - started) / 1_000_000, budget.getScannedRows(), budget.getWorkingRows(),
                    budget.getWorkingBytes(), bytes, limits);
            retain(artifact);
            return artifact;
        });
    }

    public ReadResult getResult(String resultId) {
        return read(null, maximumLimits, budget -> {
            purgeExpired();
            ReadResult result = results.get(resultId);
            if (result == null) { throw error(ErrorCode.NOT_FOUND, "Result was not found or has expired", null, null); }
            return result;
        });
    }

    private void authorize(String table) {
        if (!policy.permits(table)) { throw error(ErrorCode.ACCESS_DENIED, "Table access is not allowed", null, null); }
    }

    List<Object> validateRequest(String sql, List<?> parameters) {
        if (sql == null || sql.isBlank() || parameters == null || parameters.size() > MAX_PARAMETERS) {
            throw error(ErrorCode.INVALID_REQUEST, "SQL and at most " + MAX_PARAMETERS + " parameters are required", null, null);
        }
        long bytes = sql.getBytes(StandardCharsets.UTF_8).length;
        List<Object> bound = new ArrayList<>();
        for (Object value : parameters) {
            Object normalized;
            try { normalized = ParameterTerm.normalize(value); }
            catch (IllegalArgumentException e) { throw error(ErrorCode.INVALID_REQUEST, e.getMessage(), null, e); }
            bytes += QueryBudget.payloadBytes(normalized);
            if (bytes > MAX_REQUEST_BYTES) { throw error(ErrorCode.INVALID_REQUEST, "Request payload is too large", null, null); }
            bound.add(normalized);
        }
        if (bytes > MAX_REQUEST_BYTES) { throw error(ErrorCode.INVALID_REQUEST, "Request payload is too large", null, null); }
        validateSqlShape(sql);
        return bound;
    }

    /** Bound parser/evaluator recursion before constructing an expression tree. */
    private void validateSqlShape(String sql) {
        Lexer lexer = new Lexer(new StringReader(sql));
        int tokens = 0;
        int nesting = 0;
        int prefixes = 0;
        try {
            int token;
            while ((token = lexer.next_token().sym) != sym.EOF) {
                QueryBudget.check();
                if (++tokens > MAX_SQL_TOKENS) {
                    throw error(ErrorCode.INVALID_REQUEST, "SQL contains too many tokens", null, null);
                }
                if (token == sym.LPAREN && ++nesting > MAX_SQL_NESTING) {
                    throw error(ErrorCode.INVALID_REQUEST, "SQL nesting is too deep", null, null);
                }
                if (token == sym.RPAREN) { nesting--; }
                prefixes = token == sym.NOT || token == sym.MINUS || token == sym.PLUS ? prefixes + 1 : 0;
                if (prefixes > MAX_SQL_NESTING) {
                    throw error(ErrorCode.INVALID_REQUEST, "SQL contains too many consecutive prefix operators", null, null);
                }
            }
        } catch (IOException e) { throw new IllegalArgumentException("Could not read SQL", e); }
    }

    private <T> T read(String requestId, QueryLimits limits, Function<QueryBudget, T> operation) {
        synchronized (DBMS.class) {
            if (closed || DBMS.getEnv() != environment) { throw error(ErrorCode.CLOSED, "Adapter is closed", requestId, null); }
            if (DBMS.getTransaction() != null) {
                throw error(ErrorCode.TRANSACTION_ACTIVE, "Finish the host transaction before using the adapter", requestId, null);
            }
            if (limits == null || !maximumLimits.permits(limits)) {
                throw error(ErrorCode.INVALID_REQUEST, "Requested limits exceed configured ceilings", requestId, null);
            }
            try (QueryBudget budget = QueryBudget.start(limits)) {
                QueryBudget.check();
                T value = operation.apply(budget);
                try { QueryBudget.check(); }
                catch (QueryBudget.Exceeded e) {
                    // A failed query must not leave an unreachable retained artifact.
                    if (requestId != null && value instanceof ReadResult) {
                        ReadResult removed = results.remove(((ReadResult) value).resultId());
                        if (removed != null) { retainedBytes -= removed.payloadBytes(); }
                    }
                    throw e;
                }
                return value;
            } catch (AdapterException e) {
                throw error(e.getCode(), e.getMessage(), requestId, e);
            } catch (QueryBudget.Exceeded e) {
                throw error(ErrorCode.LIMIT_EXCEEDED, e.getMessage(), requestId, e);
            } catch (IllegalArgumentException e) {
                throw error(ErrorCode.SQL_ERROR, e.getMessage(), requestId, e);
            } catch (RuntimeException e) {
                throw error(ErrorCode.DATABASE_ERROR, "The database could not complete the request", requestId, e);
            }
        }
    }

    private void retain(ReadResult result) {
        if (result.payloadBytes() > retention.maxBytes()) { throw new QueryBudget.Exceeded("retained result bytes"); }
        purgeExpired();
        while (!results.isEmpty() && (results.size() >= retention.maxResults()
                || result.payloadBytes() > retention.maxBytes() - retainedBytes)) {
            String oldest = results.keySet().iterator().next();
            retainedBytes -= results.remove(oldest).payloadBytes();
        }
        results.put(result.resultId(), result);
        retainedBytes += result.payloadBytes();
    }

    private void purgeExpired() {
        Instant now = clock.instant();
        Iterator<ReadResult> entries = results.values().iterator();
        while (entries.hasNext()) {
            ReadResult result = entries.next();
            if (!result.expiresAt().isAfter(now)) {
                retainedBytes -= result.payloadBytes();
                entries.remove();
            }
        }
    }

    private static AdapterException error(ErrorCode code, String message, String requestId, Throwable cause) {
        return new AdapterException(code, message, requestId, cause);
    }

    public void close() {
        synchronized (DBMS.class) {
            if (closed) { return; }
            closed = true;
            results.clear();
            retainedBytes = 0;
            if (DBMS.getEnv() == environment) { DBMS.shutdown(); }
        }
    }
}
