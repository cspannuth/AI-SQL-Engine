import java.util.*;
import com.sleepycat.je.*;

/** CREATE TABLE, retaining the project's single-primary-key schema. */
public class CreateStatement extends SQLStatement {
    private boolean ifNotExists;

    public CreateStatement(Table table, ArrayList<Column> columns) { super(table, columns); }
    public void setIfNotExists(boolean value) { ifNotExists = value; }

    protected void executeInternal() {
        Table table = getTable(0);
        if (Catalog.findName(table.getName()) != null) {
            if (ifNotExists) { result = QueryResult.changed("Table already exists.", 0); return; }
            throw new IllegalArgumentException(table + ": table already exists");
        }
        Set<String> names = new HashSet<>();
        table.setStorageVersion(2);
        int keys = 0;
        for (int i = 0; i < numColumns(); i++) {
            Column definition = getColumn(i);
            if (definition.getTable() != null) {
                throw new IllegalArgumentException("Column definitions cannot be qualified");
            }
            if (!names.add(definition.getName().toLowerCase(Locale.ROOT))) {
                throw new IllegalArgumentException("Duplicate column: " + definition.getName());
            }
            if (definition.isPrimaryKey()) { keys++; }
            Column column = new Column(definition.getName(), table);
            column.useColInfo(definition);
            // New INTEGER columns use SQLite's signed 64-bit integer range.
            if (column.getType() == Column.INTEGER) { column.setLength(8); }
            table.addColumn(column);
        }
        if (keys > 1) { throw new IllegalArgumentException("Only one primary-key column is supported"); }
        Column primaryKey = table.primaryKeyColumn();
        if (primaryKey != null && primaryKey.getType() == Column.INTEGER
                && primaryKey.isDeclaredInteger()) {
            table.setRowIdColumn(primaryKey.getIndex());
        }
        if (Catalog.putMetadata(table) == OperationStatus.KEYEXIST) {
            throw new IllegalStateException("Table appeared during CREATE");
        }
        DatabaseConfig config = new DatabaseConfig();
        config.setAllowCreate(true);
        config.setExclusiveCreate(true);
        config.setTransactional(true);
        try (Database ignored = DBMS.getEnv().openDatabase(
                DBMS.getTransaction(), table.dbName(), config)) {
            result = QueryResult.changed("Created table " + table + ".", 0);
        }
    }
}
