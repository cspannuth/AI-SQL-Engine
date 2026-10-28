import java.nio.charset.*;
import java.util.*;
import com.sleepycat.je.*;

/** A table's catalog schema and its Berkeley DB handle. */
public class Table {
    private static Map<String, Table> tableCache = new HashMap<>();
    private String name;
    private String rangeVar;
    private ArrayList<Column> columns;
    private Database db;
    private Charset stringCharset = StandardCharsets.UTF_8;
    private int storageVersion = 1;
    private int rowIdColumn = -1;
    private List<String> checks = List.of();
    private List<ForeignKey> foreignKeys = List.of();

    public Table(String name) { this.name = name; }

    public OperationStatus open() {
        Table cached = tableCache.get(name.toLowerCase(Locale.ROOT));
        if (cached == this) { return OperationStatus.SUCCESS; }
        if (cached == null) {
            columns = null;
            if (Catalog.getMetadata(this) == OperationStatus.NOTFOUND) {
                throw new IllegalArgumentException(name + ": no such table");
            }
            DatabaseConfig config = new DatabaseConfig();
            config.setTransactional(true);
            db = DBMS.getEnv().openDatabase(DBMS.getTransaction(), dbName(), config);
            tableCache.put(name.toLowerCase(Locale.ROOT), this);
        } else {
            name = cached.name;
            db = cached.db;
            stringCharset = cached.stringCharset;
            storageVersion = cached.storageVersion;
            rowIdColumn = cached.rowIdColumn;
            checks = cached.checks;
            foreignKeys = cached.foreignKeys;
            columns = new ArrayList<>();
            for (Column source : cached.columns) {
                Column copy = new Column(source.getName(), this);
                copy.useColInfo(source);
                columns.add(copy);
            }
        }
        return OperationStatus.SUCCESS;
    }

    public void close() {
        Table cached = tableCache.remove(name.toLowerCase(Locale.ROOT));
        if (cached != null && cached.db != null) {
            cached.db.close();
            cached.db = null;
        }
        db = null;
    }

    public Column primaryKeyColumn() {
        for (Column column : columns) {
            if (column.isPrimaryKey()) { return column; }
        }
        return null;
    }

    /** Declared columns shadow SQLite's three names for the hidden row ID. */
    public Column resolveColumn(Column reference) {
        for (Column column : columns) {
            if (reference.nameMatches(column, this)) { return column; }
        }
        String name = reference.getName();
        if (!usesRowIds() || !(name.equalsIgnoreCase("rowid")
                || name.equalsIgnoreCase("oid") || name.equalsIgnoreCase("_rowid_"))) { return null; }
        Column hidden = new Column(name, this);
        hidden.setType(Column.INTEGER);
        hidden.setLength(8);
        hidden.setIndex(rowIdColumn >= 0 ? rowIdColumn : numColumns());
        return reference.nameMatches(hidden, this) ? hidden : null;
    }

    public boolean usesRowIds() { return storageVersion >= 2; }
    public int getStorageVersion() { return storageVersion; }
    public void setStorageVersion(int version) { storageVersion = version; }
    public int getRowIdColumn() { return rowIdColumn; }
    public void setRowIdColumn(int index) { rowIdColumn = index; }
    public List<String> getChecks() { return checks; }
    public void setChecks(List<String> values) { checks = List.copyOf(values); }
    public List<ForeignKey> getForeignKeys() { return foreignKeys; }
    public void setForeignKeys(List<ForeignKey> values) { foreignKeys = List.copyOf(values); }

    public String dbName() { return name + ".tab.db"; }
    public String getName() { return name; }
    public String getRangeVar() { return rangeVar; }
    public String getReferenceName() { return rangeVar == null ? name : rangeVar; }
    public Database getDB() { return db; }
    public Column getColumn(int index) { return columns.get(index); }
    public int numColumns() { return columns == null ? 0 : columns.size(); }
    public Charset getStringCharset() { return stringCharset; }
    public void setStringCharset(Charset charset) { stringCharset = charset; }
    public void setName(String name) { this.name = name; }
    public void setRangeVariable(String alias) { rangeVar = alias; }
    public void setDB(Database db) { this.db = db; }
    public String toString() { return name; }

    public void addColumn(Column column) {
        if (columns == null) { columns = new ArrayList<>(); }
        column.setIndex(columns.size());
        columns.add(column);
    }

    public static void cacheInit() { tableCache = new HashMap<>(); }

    public static void cacheClose() {
        try {
            for (Table table : tableCache.values()) {
                if (table.db != null) {
                    table.db.close();
                    table.db = null;
                }
            }
        } finally { tableCache.clear(); }
    }
}
