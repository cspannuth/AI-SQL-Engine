import java.util.*;

/** Create/populate or drop a secondary index atomically. */
public final class IndexStatement extends SQLStatement {
    private final String name;
    private final String column;
    private final boolean unique;
    private final boolean optional;

    public IndexStatement(String name, Table table, String column, boolean unique, boolean optional) {
        super();
        if (table != null) { addTable(table); }
        this.name = name;
        this.column = column;
        this.unique = unique;
        this.optional = optional;
    }

    protected void executeInternal() {
        IndexDefinition existing = IndexCatalog.find(name);
        if (numTables() == 0) {
            if (existing == null) {
                if (!optional) { throw new IllegalArgumentException("No such index: " + name); }
            } else {
                Table.cacheClose();
                IndexCatalog.drop(existing);
            }
            result = QueryResult.changed("Dropped index " + name + ".", 0);
            return;
        }
        if (existing != null) {
            if (!optional) { throw new IllegalArgumentException("Index already exists: " + name); }
            result = QueryResult.changed("Index already exists.", 0);
            return;
        }
        if (Catalog.findName(name) != null || name.isEmpty() || name.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 255) {
            throw new IllegalArgumentException("Invalid or occupied index name: " + name);
        }
        Table table = getTable(0);
        table.open();
        Column target = table.resolveColumn(new Column(column));
        if (target == null || target.getIndex() == table.numColumns()
                || !target.getName().equalsIgnoreCase(table.getColumn(target.getIndex()).getName())) {
            throw new IllegalArgumentException("Indexes require a declared column: " + column);
        }
        if (unique) {
            Set<Object> seen = new HashSet<>();
            try (TableIterator iterator = new TableIterator(table)) {
                while (iterator.next()) {
                    Object value = iterator.getColumnVal(target.getIndex());
                    if (value != null && !seen.add(SqlValues.key(value))) {
                        throw new IllegalArgumentException("Existing rows are not unique: " + column);
                    }
                }
            }
        }
        IndexDefinition index = new IndexDefinition(name, table.getName(), target.getName(), unique);
        IndexCatalog.attach(table, index, true);
        IndexCatalog.save(index);
        result = QueryResult.changed("Created index " + name + ".", 0);
    }
}
