import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import com.sleepycat.je.*;

/** API, catalog, and compatibility checks that complement SQLite comparisons. */
public final class ConstraintTests {
    private static int assertions;

    public static void main(String[] args) throws Exception {
        Path home = Paths.get(args[0]);
        try {
            DBMS.init(home);
            DBMS.execute("CREATE TABLE defaults(id INTEGER PRIMARY KEY,code TEXT UNIQUE DEFAULT 'ready',"
                    + "qty INTEGER NOT NULL DEFAULT 7 CHECK(qty>0),optional TEXT DEFAULT NULL,CHECK(code<>'bad'));");
            DBMS.execute("INSERT INTO defaults DEFAULT VALUES;");
            fails(() -> DBMS.execute("INSERT INTO defaults(code,qty) VALUES(?,?);", List.of("other",-1)));
            DBMS.execute("INSERT INTO defaults(code,qty) VALUES(?,?);", Arrays.asList(null,5));
            check(DBMS.execute("SELECT id,code,qty,optional FROM defaults ORDER BY id;").getRows()
                    .equals(List.of(Arrays.asList(1L,"ready",7L,null),Arrays.asList(2L,null,5L,null))),
                    "Bound values and omitted defaults");
            fails(() -> DBMS.execute("UPDATE defaults SET qty=? WHERE id=?;",List.of(0,1)));
            DBMS.execute("BEGIN;");
            DBMS.execute("INSERT INTO defaults(code) VALUES('kept');");
            fails(() -> DBMS.execute("INSERT INTO defaults(code) VALUES('new'),('ready');"));
            check(DBMS.getTransaction() != null && DBMS.getTransaction().isValid(), "Logical error preserves transaction");
            check(DBMS.execute("SELECT COUNT(*) FROM defaults;").getRows().equals(List.of(List.of(3L))),
                    "Earlier statement retained and failed batch wrote nothing");
            DBMS.execute("ROLLBACK;");
            DBMS.execute("CREATE TABLE parent(id INTEGER PRIMARY KEY,code TEXT UNIQUE);");
            DBMS.execute("CREATE TABLE child(pid INTEGER DEFAULT 1 REFERENCES parent,code TEXT REFERENCES parent(code));");
            DBMS.execute("INSERT INTO parent VALUES(1,'a');");
            DBMS.execute("INSERT INTO child(code) VALUES(?);",List.of("a"));
            fails(() -> DBMS.execute("INSERT INTO child VALUES(?,?);",List.of(2,"a")));
            fails(() -> DBMS.execute("DELETE FROM parent;"));
            fails(() -> DBMS.execute("DROP TABLE parent;"));

            for (String sql : List.of(
                    "CREATE TABLE bad(value INTEGER DEFAULT (1+2));",
                    "CREATE TABLE bad(value INTEGER DEFAULT CURRENT_TIMESTAMP);",
                    "CREATE TABLE bad(a INTEGER,b INTEGER,UNIQUE(a,b));",
                    "CREATE TABLE bad(a INTEGER,b INTEGER,FOREIGN KEY(a,b) REFERENCES parent(id,code));",
                    "CREATE TABLE bad(value INTEGER CHECK(missing>0));",
                    "CREATE TABLE bad(value INTEGER CHECK(SUM(value)>0));",
                    "CREATE TABLE bad(value INTEGER PRIMARY KEY,PRIMARY KEY(value));",
                    "CREATE TABLE bad(value INTEGER,CHECK(value>0),other INTEGER);")) {
                fails(() -> DBMS.execute(sql));
                check(Catalog.findName("bad") == null, "Invalid definition did not persist");
            }
            fails(() -> DBMS.execute("CREATE TABLE bad(value INTEGER CHECK(value>?));",List.of(0)));
            fails(() -> DBMS.execute("CREATE TABLE bad(value INTEGER DEFAULT ?);",List.of(0)));
            previousRowIdFixture();
            DBMS.execute("INSERT INTO version_two(label) VALUES('second');");
            DBMS.execute("CREATE TABLE old_child(pid INTEGER REFERENCES version_two(id));");
            DBMS.execute("INSERT INTO old_child VALUES(1);");
            fails(() -> DBMS.execute("DELETE FROM version_two WHERE id=1;"));
            DBMS.shutdown();

            DBMS.init(home);
            Table old = new Table("version_two");
            Catalog.getMetadata(old);
            check(old.getStorageVersion() == 2 && old.getChecks().isEmpty() && old.getForeignKeys().isEmpty(),
                    "Version 2 catalog was not rewritten");
            check(!old.getColumn(1).isUnique() && !old.getColumn(1).hasDefault(), "Old column flags default safely");
            check(DBMS.execute("SELECT id,label FROM version_two ORDER BY id;").getRows()
                    .equals(List.of(List.of(1L,"\u00e9"),List.of(2L,"second"))), "Version 2 row encoding preserved");
            fails(() -> DBMS.execute("UPDATE version_two SET id=10 WHERE id=1;"));
            DBMS.execute("INSERT INTO defaults(code) VALUES('after restart');");
            fails(() -> DBMS.execute("INSERT INTO defaults(code) VALUES('ready');"));
            fails(() -> DBMS.execute("INSERT INTO defaults(qty) VALUES(-1);"));
            DBMS.shutdown();

            try (DatabaseAdapter adapter = DatabaseAdapter.open(home, DatabaseAdapter.AccessPolicy.readAll("reader"))) {
                DatabaseAdapter.TableSchema schema = adapter.describeTable("defaults");
                check(schema.columns().get(1).unique(), "UNIQUE metadata persisted");
                check(schema.columns().get(1).hasDefault() && schema.columns().get(1).defaultValue().equals("ready"),
                        "Text default metadata persisted");
                check(schema.columns().get(2).defaultValue().equals(7L), "Integer default metadata persisted");
                check(schema.columns().get(3).hasDefault() && schema.columns().get(3).defaultValue() == null,
                        "Explicit NULL default distinguished from no default");
                check(!schema.columns().get(0).hasDefault(), "Absent default metadata");
                check(schema.checks().equals(List.of("qty>0","code<>'bad'")), "CHECK SQL persisted");
                check(adapter.describeTable("child").foreignKeys().equals(List.of(
                        new ForeignKey("pid","parent",null),new ForeignKey("code","parent","code"))),
                        "Foreign-key metadata persisted");
                check(adapter.describeTable("version_two").foreignKeys().isEmpty(), "Version 2 adapter metadata");
                check(adapter.queryReadOnly("SELECT qty FROM defaults WHERE code='after restart';").rows()
                        .equals(List.of(List.of(7L))), "Persisted default used after restart");
            }
            System.out.println("Constraint API checks passed: " + assertions);
        } finally { DBMS.shutdown(); }
    }

    /** Independent golden bytes for the preceding row ID format, not the current serializer. */
    private static void previousRowIdFixture() throws Exception {
        DatabaseConfig config = new DatabaseConfig();
        config.setTransactional(true);
        config.setAllowCreate(true);
        try (Database catalog = DBMS.getEnv().openDatabase(null,Catalog.CATALOG_NAME,config);
             Database table = DBMS.getEnv().openDatabase(null,"version_two.tab.db",config);
             RowOutput metadata = new RowOutput()) {
            metadata.writeByte(0); metadata.writeByte(2); metadata.writeByte(2); metadata.writeShort(0);
            metadata.writeByte(2); metadata.writeBytes("id");
            metadata.writeByte(0); metadata.writeInt(8); metadata.writeBoolean(false); metadata.writeBoolean(true);
            metadata.writeByte(5); metadata.writeBytes("label");
            metadata.writeByte(3); metadata.writeInt(20); metadata.writeBoolean(false); metadata.writeBoolean(false);
            catalog.put(null,new DatabaseEntry("version_two".getBytes(StandardCharsets.UTF_8)),
                    new DatabaseEntry(metadata.getBufferBytes()));
            table.put(null,new DatabaseEntry(new byte[]{(byte)0x80,0,0,0,0,0,0,1}),
                    new DatabaseEntry(new byte[]{-1,-2,0,6,0,8,(byte)0xc3,(byte)0xa9}));
        }
    }

    private static void fails(Runnable action) {
        try { action.run(); }
        catch (IllegalArgumentException expected) { assertions++; return; }
        throw new AssertionError("Expected a SQL error");
    }

    private static void check(boolean condition, String message) {
        assertions++;
        if (!condition) { throw new AssertionError(message); }
    }
}
