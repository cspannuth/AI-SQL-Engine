import java.nio.charset.StandardCharsets;
import java.io.*;
import java.util.*;
import com.sleepycat.je.*;

/** Index definitions and secondary handles use the same transactions as table data. */
final class IndexCatalog {
    static final String NAME="indexes.catalog.db";
    private static Database metadata;
    static boolean rebuilding;

    static void open() {
        DatabaseConfig config = new DatabaseConfig().setTransactional(true).setAllowCreate(true);
        metadata = DBMS.getEnv().openDatabase(DBMS.getTransaction(), NAME, config);
    }

    static void close() {
        if (metadata != null) { metadata.close(); metadata = null; }
    }

    static List<IndexDefinition> all() {
        List<IndexDefinition> result = new ArrayList<>();
        DatabaseEntry key = new DatabaseEntry(), value = new DatabaseEntry();
        try (Cursor cursor = metadata.openCursor(DBMS.getTransaction(), null)) {
            while (cursor.getNext(key, value, null) == OperationStatus.SUCCESS) {
                QueryBudget.check();
                Map<?, ?> data = (Map<?, ?>) Json.parse(new String(value.getData(), value.getOffset(),
                        value.getSize(), StandardCharsets.UTF_8));
                List<String> columns = data.containsKey("columns")
                        ? ((List<?>) data.get("columns")).stream().map(Object::toString).toList()
                        : List.of((String) data.get("column"));
                result.add(new IndexDefinition((String)data.get("name"), (String)data.get("table"),
                        columns, (Boolean)data.get("unique"), data.containsKey("origin") ? (String)data.get("origin") : "CREATE INDEX"));
            }
        }
        return result;
    }

    static List<IndexDefinition> forTable(String name) {
        List<IndexDefinition> result = new ArrayList<>();
        for (IndexDefinition index : all()) {
            if (index.table().equalsIgnoreCase(name)) { result.add(index); }
        }
        return result;
    }

    static IndexDefinition find(String name) {
        for (IndexDefinition index : all()) {
            if (index.name().equalsIgnoreCase(name)) { return index; }
        }
        return null;
    }

    static boolean unique(Table table, Column column) {
        return forTable(table.getName()).stream().anyMatch(index -> index.unique()
                && index.columns().size() == 1 && index.column().equalsIgnoreCase(column.getName()));
    }

    static void save(IndexDefinition index) {
        metadata.put(DBMS.getTransaction(), entry(index.name()), entry(Json.stringify(index)));
    }

    static void drop(IndexDefinition index) {
        DBMS.getEnv().removeDatabase(DBMS.getTransaction(), index.dbName());
        metadata.delete(DBMS.getTransaction(), entry(index.name()));
    }

    static void attach(Table table, IndexDefinition index, boolean create) {
        List<Integer> columns = UniqueKeys.indexes(table, index.columns());
        SecondaryConfig config = new SecondaryConfig();
        config.setTransactional(true);
        config.setAllowCreate(create);
        config.setAllowPopulate(create);
        config.setSortedDuplicates(true);
        config.setKeyCreator((database, key, value, result) -> {
            List<Object> cells = new ArrayList<>();
            for (int column : columns) { cells.add(TableIterator.readColumn(table, key, value, column)); }
            if (cells.contains(null)) { return false; }
            result.setData(indexKey(cells));
            return true;
        });
        SecondaryDatabase database = DBMS.getEnv().openSecondaryDatabase(DBMS.getTransaction(),
                index.dbName(), table.getDB(), config);
        table.addIndex(index, database);
    }

    /** Equality keys normalize numeric values; range ordering is not advertised. */
    static byte[] indexKey(Object value) {
        return (value instanceof Number ? "N" + ((java.math.BigDecimal)SqlValues.key(value)).toPlainString()
                : "T" + value).getBytes(StandardCharsets.UTF_8);
    }

    /** Length prefixes prevent collisions between composite values containing separators. */
    static byte[] indexKey(List<Object> values) {
        if (values.size() == 1) { return indexKey(values.get(0)); }
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream();
             DataOutputStream out = new DataOutputStream(bytes)) {
            for (Object value : values) {
                byte[] part = indexKey(value);
                out.writeInt(part.length);
                out.write(part);
            }
            return bytes.toByteArray();
        } catch (IOException impossible) { throw new IllegalStateException(impossible); }
    }

    /** Called only by CREATE or writable startup, never by schema discovery or SELECT. */
    static void ensureConstraints(Table table) {
        List<List<String>> keys = new ArrayList<>();
        List<String> origins = new ArrayList<>();
        if (!table.getPrimaryKeyColumns().isEmpty()) {
            keys.add(table.getPrimaryKeyColumns()); origins.add("PRIMARY KEY");
        }
        for (int i = 0; i < table.numColumns(); i++) {
            if (table.getColumn(i).isUnique()) { keys.add(List.of(table.getColumn(i).getName())); origins.add("UNIQUE"); }
        }
        for (List<String> key : table.getUniqueKeys()) { keys.add(key); origins.add("UNIQUE"); }
        List<Integer> physical = KeyLookup.physicalColumns(table);
        for (int i = 0; i < keys.size(); i++) {
            List<String> names = keys.get(i);
            List<Integer> positions = UniqueKeys.indexes(table, names);
            if (UniqueKeys.matches(positions, physical) || forTable(table.getName()).stream().anyMatch(index ->
                    index.internal() && UniqueKeys.matches(positions, UniqueKeys.indexes(table, index.columns())))) { continue; }
            String name;
            do { name = "__dbms_auto_" + UUID.randomUUID().toString().replace("-", ""); }
            while (find(name) != null || Catalog.findName(name) != null);
            IndexDefinition index = new IndexDefinition(name, table.getName(), names, true, origins.get(i));
            attach(table, index, true);
            save(index);
        }
    }

    static void rename(String table, String newTable, String column, String newColumn) {
        for (IndexDefinition index : forTable(table)) {
            List<String> names = index.columns().stream().map(name ->
                    column != null && name.equalsIgnoreCase(column) ? newColumn : name).toList();
            save(new IndexDefinition(index.name(), newTable, names, index.unique(), index.origin()));
        }
    }
    static void rebuild() {
        rebuild(all());
    }
    static void rebuild(List<IndexDefinition> indexes) {
        rebuilding=true;
        try {
            for(IndexDefinition index:indexes) {
                Table table=new Table(index.table()); table.open(); attach(table,index,true);
            }
        } finally { Table.cacheClose(); Table.cacheInit(); rebuilding=false; }
    }

    private static DatabaseEntry entry(String text) { return new DatabaseEntry(text.getBytes(StandardCharsets.UTF_8)); }
}
