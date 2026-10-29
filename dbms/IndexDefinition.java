import java.util.List;

/** Persisted index metadata; the column field preserves the original single-column API. */
public record IndexDefinition(String name, String table, String column, boolean unique,
        List<String> columns, String origin) {
    public IndexDefinition {
        columns = List.copyOf(columns);
        if (columns.isEmpty()) { throw new IllegalArgumentException("An index needs columns"); }
    }
    public IndexDefinition(String name, String table, String column, boolean unique) {
        this(name, table, column, unique, List.of(column), "CREATE INDEX");
    }
    public IndexDefinition(String name, String table, List<String> columns, boolean unique, String origin) {
        this(name, table, columns.size() == 1 ? columns.get(0) : null, unique, columns, origin);
    }
    boolean internal() { return !origin.equals("CREATE INDEX"); }
    String dbName() { return name + ".idx.db"; }
}
