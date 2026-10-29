import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import com.sleepycat.je.*;

/** Verify physical identity, older encodings, and actual index access after DROP. */
public final class DropColumnTests {
    public static void main(String[] args) throws Exception {
        Path home = Path.of(args[0]);
        try {
            DBMS.init(home);
            for (int version = 0; version <= 5; version++) {
                String name = "legacy_" + version;
                fixture(name, version);
                DBMS.execute("CREATE INDEX lookup_" + version + " ON " + name + "(tail);");
                DBMS.execute("ALTER TABLE " + name + " DROP spare;");
                Table table = new Table(name);
                table.open();
                check(table.getStorageVersion() == 6 && table.getRowFormat() == Math.min(version, 2), "Catalog upgrade preserves row format");
                check(table.getColumn(0).getLength() == (version == 0 ? 4 : 8), "Legacy integer width preserved");
                try (TableIterator iterator = new TableIterator(table)) {
                    check(iterator.next() && Arrays.equals(iterator.getKey(), key(version)), "Physical key unchanged");
                    check(((Number) iterator.getColumnVal(0)).longValue() == 11 && iterator.getColumnVal(1).equals("\u00e9"),
                            "Primary key and text survive first-column removal");
                }
                Table.cacheClose();
            }
            fixture("legacy_checked", 5, "LENGTH(spare)>0");
            try {
                DBMS.execute("ALTER TABLE legacy_checked DROP spare;");
                throw new AssertionError("Ambiguous legacy CHECK ownership must block removal");
            } catch (IllegalArgumentException expected) {
                Table table = new Table("legacy_checked");
                Catalog.getMetadata(table);
                check(table.numColumns() == 3 && table.getStorageVersion() == 5, "Rejected legacy DROP preserves original metadata");
            }
            DBMS.execute("CREATE TABLE named(a INTEGER,b INTEGER CONSTRAINT removed_check CHECK(b>0),"
                    + "c INTEGER CONSTRAINT surviving_default DEFAULT (1+2));");
            DBMS.execute("INSERT INTO named(a,b) VALUES(1,2);");
            DBMS.execute("ALTER TABLE named DROP b;");
            DBMS.shutdown();
            try (DatabaseAdapter adapter = DatabaseAdapter.open(home, DatabaseAdapter.AccessPolicy.readAll("reader"))) {
                QueryLimits oneScan = new QueryLimits(10, 10000, 1, 100, 100000, 5000);
                for (int version = 0; version <= 5; version++) {
                    DatabaseAdapter.ReadResult result = adapter.queryReadOnly("SELECT id,tail FROM legacy_" + version + " WHERE tail=?;",
                            List.of("\u00e9"), oneScan);
                    check(result.scannedRows() == 1 && result.rows().get(0).get(1).equals("\u00e9"), "Rebuilt index survives restart");
                    DBMS.execute("UPDATE legacy_" + version + " SET tail=? WHERE id=11;", List.of("updated"));
                    DBMS.execute("INSERT INTO legacy_" + version + " VALUES(12,'inserted');");
                    List<List<Object>> updated = adapter.queryReadOnly(
                            "SELECT id FROM legacy_" + version + " WHERE tail='updated';", List.of(), oneScan).rows();
                    check(updated.size() == 1 && ((Number) updated.get(0).get(0)).longValue() == 11,
                            "Legacy rows remain writable through rebuilt indexes");
                    check(adapter.queryReadOnly("SELECT tail FROM legacy_" + version + " WHERE tail='inserted';", List.of(), oneScan)
                            .rows().equals(List.of(List.of("inserted"))), "New rows retain the old encoding after DROP");
                }
                DatabaseAdapter.TableSchema schema = adapter.describeTable("named");
                check(schema.checks().isEmpty() && schema.constraints().stream().noneMatch(item -> "removed_check".equals(item.name())),
                        "Removed column CHECK declaration was removed");
                check(schema.constraints().stream().anyMatch(item -> "surviving_default".equals(item.name())), "Other names survive");
                check(adapter.queryReadOnly("SELECT * FROM named;").rows().equals(List.of(List.of(1L, 3L))), "Other default and values survive");
            }
            System.out.println("DROP COLUMN checks passed: versions 0-5, keys, widths, text, index budgets, and retained metadata");
        } finally { DBMS.shutdown(); }
    }

    /** Independent catalog/payload bytes make compatibility checks independent of the writer. */
    private static void fixture(String name, int version) throws Exception {
        fixture(name, version, null);
    }
    private static void fixture(String name, int version, String checkSql) throws Exception {
        DatabaseConfig config = new DatabaseConfig().setTransactional(true).setAllowCreate(true);
        try (Database catalog = DBMS.getEnv().openDatabase(null, Catalog.CATALOG_NAME, config);
             Database data = DBMS.getEnv().openDatabase(null, name + ".tab.db", config);
             RowOutput metadata = new RowOutput(); RowOutput row = new RowOutput()) {
            if (version > 0) { metadata.writeByte(0); metadata.writeByte(version); }
            metadata.writeByte(3);
            if (version >= 4) { metadata.writeByte(2); }
            if (version >= 2) { metadata.writeShort(1); }
            String[] names = {"spare", "id", "tail"};
            for (int i = 0; i < names.length; i++) {
                metadata.writeByte(names[i].length()); metadata.writeBytes(names[i]);
                metadata.writeByte(i == 1 ? Column.INTEGER : Column.VARCHAR);
                metadata.writeInt(i == 1 ? (version == 0 ? 4 : 8) : 20);
                metadata.writeBoolean(false); metadata.writeBoolean(i == 1);
                if (version >= 3) { metadata.writeBoolean(false); metadata.writeBoolean(false); }
            }
            if (version >= 3) {
                metadata.writeInt(checkSql == null ? 0 : 1);
                if (checkSql != null) {
                    byte[] sql = checkSql.getBytes(StandardCharsets.UTF_8);
                    metadata.writeInt(sql.length); metadata.write(sql);
                }
                metadata.writeInt(0);
            }
            if (version >= 5) {
                metadata.writeInt(1); metadata.writeInt(2); metadata.writeBytes("id"); metadata.writeInt(0);
            }
            byte[] text = "\u00e9".getBytes(version == 0 ? StandardCharsets.ISO_8859_1 : StandardCharsets.UTF_8);
            row.writeShort(8); row.writeShort(InsertRow.IS_PKEY); row.writeShort(12); row.writeShort(12 + text.length);
            row.writeBytes("gone"); row.write(text);
            catalog.put(null, new DatabaseEntry(name.getBytes(StandardCharsets.UTF_8)), new DatabaseEntry(metadata.getBufferBytes()));
            data.put(null, new DatabaseEntry(key(version)), new DatabaseEntry(row.getBufferBytes()));
        }
    }

    private static byte[] key(int version) {
        return version == 0 ? ByteBuffer.allocate(4).putInt(11).array()
                : ByteBuffer.allocate(8).putLong(version == 1 ? 11 : 11 ^ Long.MIN_VALUE).array();
    }
    private static void check(boolean condition, String message) {
        if (!condition) { throw new AssertionError(message); }
    }
}
