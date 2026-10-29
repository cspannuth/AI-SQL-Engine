import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import com.sleepycat.je.*;

/** Earlier catalogs/index JSON remain usable; backfills never rewrite primary bytes. */
public final class IndexMigrationTests {
    public static void main(String[] args) throws Exception {
        Path home = Path.of(args[0]);
        try {
            if (args[1].equals("prepare")) { prepare(home); }
            else if (args[1].equals("reader")) { reader(home); }
            else { upgrade(home); }
            System.out.println("Index migration " + args[1] + " passed");
        } finally { DBMS.shutdown(); }
    }

    private static void prepare(Path home) throws Exception {
        DBMS.init(home);
        DBMS.execute("CREATE TABLE earlier(id CHAR(7) PRIMARY KEY,code TEXT UNIQUE,a TEXT,b TEXT,UNIQUE(a,b));");
        DBMS.execute("INSERT INTO earlier VALUES('0000001','one','a','b'),('0000002','two','c','d');");
        DBMS.execute("CREATE INDEX code_lookup ON earlier(code);");
        DBMS.execute("BEGIN;");
        legacy("legacy_int", "INTEGER", List.of(new Object[]{7L,"seven"}, new Object[]{-2147483648L,"minimum"}));
        legacy("legacy_text", "CHAR(7)", List.of(new Object[]{"0000001","one"},new Object[]{"0000002","two"}));
        legacy("legacy_real", "REAL", List.of(new Object[]{0.0,"positive"},new Object[]{-0.0,"negative"}));
        Table.cacheClose();
        for (IndexDefinition index : IndexCatalog.all()) { if (index.internal()) { IndexCatalog.drop(index); } }
        DatabaseConfig config = new DatabaseConfig().setTransactional(true);
        try (Database metadata = DBMS.getEnv().openDatabase(DBMS.getTransaction(), IndexCatalog.NAME, config)) {
            // Original metadata had exactly these four fields and the same scalar key encoding.
            metadata.put(DBMS.getTransaction(), new DatabaseEntry("code_lookup".getBytes(StandardCharsets.UTF_8)),
                    new DatabaseEntry(Json.stringify(Map.of("name","code_lookup","table","earlier",
                            "column","code","unique",false)).getBytes(StandardCharsets.UTF_8)));
        }
        DBMS.execute("COMMIT;");
        Files.writeString(home.resolveSibling("rows.sha256"), digest());
    }

    private static void reader(Path home) {
        try (DatabaseAdapter adapter = DatabaseAdapter.open(home, DatabaseAdapter.AccessPolicy.readTables("schema", Set.of("earlier")))) {
            check(adapter.describeTable("earlier").indexes().size() == 1, "Metadata did not backfill indexes");
            check(adapter.queryReadOnly("SELECT code FROM earlier WHERE id='0000002';").rows()
                    .equals(List.of(List.of("two"))), "Earlier database remains readable");
            check(adapter.describeTable("earlier").indexes().size() == 1, "Read query did not backfill indexes");
        }
    }

    private static void upgrade(Path home) throws Exception {
        DBMS.init(home);
        check(digest().equals(Files.readString(home.resolveSibling("rows.sha256"))), "Primary rows are byte-for-byte unchanged");
        check(IndexCatalog.forTable("earlier").size() == 4, "Three missing constraints indexed once");
        try (QueryBudget budget = QueryBudget.start(new QueryLimits(10,10000,1,100,100000,5000))) {
            check(DBMS.execute("SELECT id FROM earlier WHERE a='c' AND b='d';").getRows()
                    .equals(List.of(List.of("0000002"))), "Composite backfill lookup");
        }
        check(DBMS.execute("SELECT id FROM earlier WHERE code='two';").getRows().equals(List.of(List.of("0000002"))),
                "Old manual index encoding still works");
        try (QueryBudget budget = QueryBudget.start(new QueryLimits(10,10000,1,100,100000,5000))) {
            check(DBMS.execute("SELECT label FROM legacy_int WHERE id='7.0';").getRows().equals(List.of(List.of("seven"))), "Legacy integer physical lookup");
        }
        try (QueryBudget budget = QueryBudget.start(new QueryLimits(10,10000,1,100,100000,5000))) {
            check(DBMS.execute("SELECT label FROM legacy_text WHERE id='0000002';").getRows().equals(List.of(List.of("two"))), "Legacy text physical lookup");
        }
        check(DBMS.execute("SELECT COUNT(*) FROM legacy_real WHERE id=0.0;").getRows().equals(List.of(List.of(2L))),
                "Legacy signed zero keys both match SQL numeric equality");
    }

    private static void legacy(String name, String type, List<Object[]> rows) {
        SQLStatement definition = DBMS.parse("CREATE TABLE " + name + "(id " + type + " PRIMARY KEY,label TEXT);", List.of());
        Table table = new Table(name); table.setStorageVersion(1);
        for (int i = 0; i < definition.numColumns(); i++) {
            Column column = new Column(definition.getColumn(i).getName(), table);
            column.useColInfo(definition.getColumn(i)); table.addColumn(column);
        }
        Catalog.putMetadata(table);
        DatabaseConfig config = new DatabaseConfig().setTransactional(true).setAllowCreate(true);
        try (Database database = DBMS.getEnv().openDatabase(DBMS.getTransaction(), table.dbName(), config)) {
            for (Object[] values : rows) {
                InsertRow row = new InsertRow(table, values); row.marshall();
                database.put(DBMS.getTransaction(), row.keyEntry(), row.valueEntry());
            }
        }
    }

    private static String digest() throws Exception {
        MessageDigest hash = MessageDigest.getInstance("SHA-256");
        for (String name : Catalog.listTableNames()) {
            Table table = new Table(name); table.open();
            try (Cursor cursor = table.getDB().openCursor(DBMS.getTransaction(), null)) {
                DatabaseEntry key = new DatabaseEntry(), value = new DatabaseEntry();
                while (cursor.getNext(key, value, null) == OperationStatus.SUCCESS) {
                    hash.update(key.getData(),key.getOffset(),key.getSize());
                    hash.update(value.getData(),value.getOffset(),value.getSize());
                }
            }
        }
        Table.cacheClose(); Table.cacheInit();
        return HexFormat.of().formatHex(hash.digest());
    }

    private static void check(boolean value, String message) { if (!value) { throw new AssertionError(message); } }
}
