import java.nio.charset.StandardCharsets;
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
                result.add(new IndexDefinition((String)data.get("name"), (String)data.get("table"),
                        (String)data.get("column"), (Boolean)data.get("unique")));
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
                && index.column().equalsIgnoreCase(column.getName()));
    }

    static void save(IndexDefinition index) {
        metadata.put(DBMS.getTransaction(), entry(index.name()), entry(Json.stringify(index)));
    }

    static void drop(IndexDefinition index) {
        DBMS.getEnv().removeDatabase(DBMS.getTransaction(), index.dbName());
        metadata.delete(DBMS.getTransaction(), entry(index.name()));
    }

    static void attach(Table table, IndexDefinition index, boolean create) {
        int column = table.resolveColumn(new Column(index.column())).getIndex();
        SecondaryConfig config = new SecondaryConfig();
        config.setTransactional(true);
        config.setAllowCreate(create);
        config.setAllowPopulate(create);
        config.setSortedDuplicates(true);
        config.setKeyCreator((database, key, value, result) -> {
            Object cell = TableIterator.readColumn(table, key, value, column);
            if (cell == null) { return false; }
            result.setData(indexKey(cell));
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

    static void rename(String table, String newTable, String column, String newColumn) {
        for (IndexDefinition index : forTable(table)) {
            save(new IndexDefinition(index.name(), newTable,
                    column != null && index.column().equalsIgnoreCase(column) ? newColumn : index.column(), index.unique()));
        }
    }
    static void rebuild() {
        rebuilding=true;
        try {
            for(IndexDefinition index:all()) {
                Table table=new Table(index.table()); table.open(); attach(table,index,true);
            }
        } finally { Table.cacheClose(); Table.cacheInit(); rebuilding=false; }
    }

    private static DatabaseEntry entry(String text) { return new DatabaseEntry(text.getBytes(StandardCharsets.UTF_8)); }
}
