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
    private Map<String, SecondaryDatabase> indexHandles = new LinkedHashMap<>();
    private Charset stringCharset = StandardCharsets.UTF_8;
    private int storageVersion = 1;
    private int rowFormat = 1;
    private int rowIdColumn = -1;
    private List<String> checks = List.of();
    private List<ForeignKey> foreignKeys = List.of();
    private boolean qualifiedOnly;
    private List<List<String>> uniqueKeys = List.of();
    private List<String> primaryKeyNames=List.of();
    private List<SchemaConstraint> constraints = List.of();

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
            if(!IndexCatalog.rebuilding) {
                for (IndexDefinition index : IndexCatalog.forTable(name)) { IndexCatalog.attach(this, index, false); }
            }
        } else {
            name = cached.name;
            db = cached.db;
            indexHandles = cached.indexHandles;
            stringCharset = cached.stringCharset;
            storageVersion = cached.storageVersion;
            rowFormat = cached.rowFormat;
            rowIdColumn = cached.rowIdColumn;
            checks = cached.checks;
            foreignKeys = cached.foreignKeys;
            uniqueKeys = cached.uniqueKeys;
            primaryKeyNames = cached.primaryKeyNames;
            constraints = cached.constraints;
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
        if (cached != null) { cached.closeHandles(); }
        db = null;
    }

    public Column primaryKeyColumn() {
        List<String> names=getPrimaryKeyColumns();
        return names.size()==1 ? resolveColumn(new Column(names.get(0))) : null;
    }
    List<String> getPrimaryKeyColumns() {
        if(!primaryKeyNames.isEmpty()) { return primaryKeyNames; }
        List<String> names=new ArrayList<>();
        for(Column column:columns) { if(column.isPrimaryKey()) { names.add(column.getName()); } }
        return names;
    }
    void setPrimaryKeyColumns(List<String> names) { primaryKeyNames=List.copyOf(names); }

    /** Declared columns shadow SQLite's three names for the hidden row ID. */
    public Column resolveColumn(Column reference) {
        if (qualifiedOnly && reference.getTable() == null) { return null; }
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

    public boolean usesRowIds() { return rowFormat >= 2; }
    public int getStorageVersion() { return storageVersion; }
    public void setStorageVersion(int version) { storageVersion = version; rowFormat = Math.min(version, 2); }
    public int getRowFormat() { return rowFormat; }
    public void setRowFormat(int format) { rowFormat = format; }
    public int getRowIdColumn() { return rowIdColumn; }
    public void setRowIdColumn(int index) { rowIdColumn = index; }
    public List<String> getChecks() { return checks; }
    public void setChecks(List<String> values) { checks = List.copyOf(values); }
    public List<ForeignKey> getForeignKeys() { return foreignKeys; }
    public void setForeignKeys(List<ForeignKey> values) { foreignKeys = List.copyOf(values); }
    public List<SchemaConstraint> getConstraints() { return constraints; }
    public void setConstraints(List<SchemaConstraint> values) { constraints = List.copyOf(values); }
    void setQualifiedOnly(boolean value) { qualifiedOnly = value; }
    List<List<String>> getUniqueKeys() { return uniqueKeys; }
    void setUniqueKeys(List<List<String>> keys) {
        List<List<String>> copy = new ArrayList<>();
        for (List<String> key : keys) { copy.add(List.copyOf(key)); }
        uniqueKeys = List.copyOf(copy);
    }

    public String dbName() { return name + ".tab.db"; }
    public String getName() { return name; }
    public String getRangeVar() { return rangeVar; }
    public String getReferenceName() { return rangeVar == null ? name : rangeVar; }
    public Database getDB() { return db; }
    public List<List<Object>> memoryRows() { return null; }
    public Column getColumn(int index) { return columns.get(index); }
    public int numColumns() { return columns == null ? 0 : columns.size(); }
    public Charset getStringCharset() { return stringCharset; }
    public void setStringCharset(Charset charset) { stringCharset = charset; }
    public void setName(String name) { this.name = name; }
    public void setRangeVariable(String alias) { rangeVar = alias; }
    public void setDB(Database db) { this.db = db; }
    void addIndex(IndexDefinition index, SecondaryDatabase database) { indexHandles.put(index.name(), database); }
    SecondaryDatabase getIndex(String name) { return indexHandles.get(name); }
    void adoptColumns(Table schema) { columns = schema.columns; }
    public String toString() { return name; }

    public void addColumn(Column column) {
        if (columns == null) { columns = new ArrayList<>(); }
        column.setIndex(columns.size());
        columns.add(column);
    }

    public static void cacheInit() { tableCache = new HashMap<>(); }
    void clearColumns() { columns = new ArrayList<>(); }

    public static void cacheClose() {
        try {
            for (Table table : tableCache.values()) {
                table.closeHandles();
            }
        } finally { tableCache.clear(); }
    }

    private void closeHandles() {
        for (SecondaryDatabase index : indexHandles.values()) { index.close(); }
        indexHandles.clear();
        if (db != null) { db.close(); db = null; }
    }
}
