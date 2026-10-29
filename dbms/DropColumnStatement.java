import java.nio.ByteBuffer;
import java.util.*;
import com.sleepycat.je.*;

/** Remove a column only after every dependency and replacement row is validated. */
public final class DropColumnStatement extends SQLStatement {
    private final String columnName;

    public DropColumnStatement(Table table, String columnName) {
        super(table);
        this.columnName = columnName;
    }

    protected void executeInternal() {
        Table original = getTable(0);
        original.open();
        Column removed = declaredColumn(original);
        List<IndexDefinition> indexes = IndexCatalog.forTable(original.getName());
        validateKeys(original, removed, indexes);
        Table edited = replacement(original, removed);
        edited.setChecks(retainedChecks(original));
        edited.setForeignKeys(retainedReferences(original));
        edited.setConstraints(original.getConstraints().stream()
                .filter(constraint -> !matches(constraint.ownerColumn())).toList());
        edited.setDB(original.getDB());
        ConstraintValidator.validateDefinition(edited);
        byte[] metadata = Catalog.encodeMetadata(edited);

        List<InsertRow> records = new ArrayList<>();
        Set<ByteBuffer> keys = new HashSet<>();
        try (TableIterator iterator = new TableIterator(original)) {
            while (iterator.next()) {
                Object[] before = iterator.getRow();
                Object[] after = new Object[edited.numColumns()];
                System.arraycopy(before, 0, after, 0, removed.getIndex());
                System.arraycopy(before, removed.getIndex() + 1, after, removed.getIndex(),
                        after.length - removed.getIndex());
                byte[] key = iterator.getKey();
                Long rowId = original.usesRowIds() ? RowIdentity.value(new DatabaseEntry(key)) : null;
                InsertRow record = new InsertRow(edited, after, rowId);
                record.marshall();
                if (!Arrays.equals(key, record.keyEntry().getData())) {
                    throw new IllegalArgumentException("DROP COLUMN cannot change physical row keys");
                }
                records.add(record);
                keys.add(ByteBuffer.wrap(key));
            }
        }
        ConstraintValidator.validateChange(edited, records, keys);
        rewrite(original, edited, metadata, records, indexes);
        result = QueryResult.changed("Dropped column " + columnName + ".", 0);
    }

    private Column declaredColumn(Table table) {
        for (int i = 0; i < table.numColumns(); i++) {
            if (matches(table.getColumn(i).getName())) { return table.getColumn(i); }
        }
        throw new IllegalArgumentException("Unknown column: " + columnName);
    }

    private void validateKeys(Table table, Column column, List<IndexDefinition> indexes) {
        if (table.numColumns() == 1) { throw new IllegalArgumentException("Cannot drop the last column"); }
        if (column.isPrimaryKey() || column.isUnique() || table.getUniqueKeys().stream().anyMatch(this::contains)) {
            throw new IllegalArgumentException("Column belongs to a PRIMARY KEY or UNIQUE constraint: " + columnName);
        }
        if (indexes.stream().anyMatch(index -> matches(index.column()))) {
            throw new IllegalArgumentException("Column is indexed: " + columnName);
        }
    }

    private Table replacement(Table original, Column removed) {
        Table edited = AlterStatement.copy(original, original.getName());
        edited.clearColumns();
        for (int i = 0; i < original.numColumns(); i++) {
            if (i == removed.getIndex()) { continue; }
            Column column = new Column(original.getColumn(i).getName(), edited);
            column.useColInfo(original.getColumn(i));
            edited.addColumn(column);
        }
        int alias = original.getRowIdColumn();
        edited.setRowIdColumn(alias > removed.getIndex() ? alias - 1 : alias);
        return edited;
    }

    private List<String> retainedChecks(Table table) {
        List<SchemaConstraint> declarations = declarations(table, "CHECK");
        List<String> checks = new ArrayList<>();
        for (int i = 0; i < table.getChecks().size(); i++) {
            String sql = table.getChecks().get(i);
            // Older catalogs did not retain column/table scope. Keep those checks conservatively.
            if (declarations.size() == table.getChecks().size() && matches(declarations.get(i).ownerColumn())) { continue; }
            if (new CheckConstraint(sql, table, null).references(columnName)) {
                throw new IllegalArgumentException("Column is referenced by CHECK: " + sql);
            }
            checks.add(sql);
        }
        return checks;
    }

    private List<ForeignKey> retainedReferences(Table table) {
        List<SchemaConstraint> declarations = declarations(table, "FOREIGN KEY");
        List<ForeignKey> references = new ArrayList<>();
        for (int i = 0; i < table.getForeignKeys().size(); i++) {
            ForeignKey reference = table.getForeignKeys().get(i);
            if (declarations.size() == table.getForeignKeys().size() && matches(declarations.get(i).ownerColumn())) { continue; }
            if (contains(reference.columns())) { throw new IllegalArgumentException("Column is used by a table FOREIGN KEY"); }
            references.add(reference);
        }
        for (String name : Catalog.listTableNames()) {
            Table child = new Table(name);
            Catalog.getMetadata(child);
            List<ForeignKey> incoming = name.equalsIgnoreCase(table.getName()) ? references : child.getForeignKeys();
            for (ForeignKey reference : incoming) {
                if (reference.parentTable().equalsIgnoreCase(table.getName()) && contains(reference.parentColumns())) {
                    throw new IllegalArgumentException("Column is referenced by FOREIGN KEY in " + name);
                }
            }
        }
        return references;
    }

    /** Existing key creators use old column positions; detach them before rewriting payloads. */
    private static void rewrite(Table original, Table edited, byte[] metadata,
            List<InsertRow> records, List<IndexDefinition> indexes) {
        try {
            Table.cacheClose();
            for (IndexDefinition index : indexes) { DBMS.getEnv().removeDatabase(DBMS.getTransaction(), index.dbName()); }
            Catalog.replaceMetadata(edited, metadata);
            DatabaseConfig config = new DatabaseConfig().setTransactional(true);
            try (Database database = DBMS.getEnv().openDatabase(DBMS.getTransaction(), original.dbName(), config)) {
                for (InsertRow record : records) {
                    database.put(DBMS.getTransaction(), record.keyEntry(), record.valueEntry());
                }
            }
            IndexCatalog.rebuild(indexes);
        } catch (RuntimeException error) {
            // Failures after the first storage change must abort the enclosing transaction.
            throw new IllegalStateException("Could not rewrite table for DROP COLUMN", error);
        }
    }

    private static List<SchemaConstraint> declarations(Table table, String kind) {
        return table.getConstraints().stream().filter(constraint -> constraint.kind().equals(kind)).toList();
    }
    private boolean matches(String name) { return name != null && name.equalsIgnoreCase(columnName); }
    private boolean contains(List<String> names) { return names.stream().anyMatch(this::matches); }
}
