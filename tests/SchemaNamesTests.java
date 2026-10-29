import java.nio.file.Path;
import java.util.List;

/** Names describe actual declarations, including column ownership and composite order. */
public final class SchemaNamesTests {
    public static void main(String[] args) {
        Path home = Path.of(args[0]);
        try {
            DBMS.init(home);
            DBMS.execute("CREATE TABLE named(id INTEGER CONSTRAINT named_pk PRIMARY KEY,"
                    + "code TEXT CONSTRAINT code_required NOT NULL CONSTRAINT code_unique UNIQUE,"
                    + "n INTEGER CONSTRAINT initial_value DEFAULT (2+3) CONSTRAINT n CHECK(n>0),"
                    + "CONSTRAINT table_check CHECK(n<10));");
            DBMS.execute("INSERT INTO named(id,code) VALUES(1,'ok');");
            try {
                DBMS.execute("INSERT INTO named(id,code,n) VALUES(2,'bad',0);");
                throw new AssertionError("Expected a named CHECK failure");
            } catch (IllegalArgumentException expected) {
                check(expected.getMessage().equals("CHECK constraint failed: n"), "CHECK reports its declared name");
            }
            DBMS.execute("ALTER TABLE named RENAME n TO quantity;");
            DBMS.execute("ALTER TABLE named RENAME TO named_new;");
            DBMS.execute("ALTER TABLE named_new ADD label TEXT CONSTRAINT label_default DEFAULT 'label';");
            DBMS.execute("BEGIN;");
            DBMS.execute("ALTER TABLE named_new RENAME quantity TO pending;");
            DBMS.execute("ROLLBACK;");
            DBMS.execute("CREATE TABLE composite(a INTEGER,b TEXT,CONSTRAINT ordered_pk PRIMARY KEY(b,a));");
            DBMS.shutdown();
            try (DatabaseAdapter adapter = DatabaseAdapter.open(home, DatabaseAdapter.AccessPolicy.readAll("reader"))) {
                DatabaseAdapter.TableSchema schema = adapter.describeTable("named_new");
                SchemaConstraint check = named(schema, "n");
                check(check.columns().equals(List.of("quantity")) && check.ownerColumn().equals("quantity")
                        && check.expression().contains("\"quantity\""), "Renamed CHECK retains its original constraint name");
                check(named(schema, "table_check").ownerColumn() == null, "Table CHECK scope persisted");
                check(named(schema, "initial_value").expression().equals("(2+3)"), "Named expression default persisted");
                check(named(schema, "code_required").kind().equals("NOT NULL")
                        && named(schema, "code_unique").kind().equals("UNIQUE"), "Column constraint kinds persisted");
                check(named(schema, "label_default").ownerColumn().equals("label"), "ADD COLUMN preserves names");
                check(named(adapter.describeTable("composite"), "ordered_pk").columns().equals(List.of("b", "a")),
                        "Composite key order persisted");
                String json = Json.stringify(schema);
                check(json.contains("\"name\":\"n\"") && json.contains("\"ownerColumn\":\"quantity\""),
                        "Named declarations are available to JSON clients");
                check(adapter.queryReadOnly("SELECT quantity FROM named_new;").rows().equals(List.of(List.of(5L))),
                        "Metadata edits preserved row values");
            }
            System.out.println("Named constraint checks passed: enforcement, scope, metadata, renames, rollback, and restart");
        } finally { DBMS.shutdown(); }
    }

    private static SchemaConstraint named(DatabaseAdapter.TableSchema schema, String name) {
        return schema.constraints().stream().filter(item -> name.equals(item.name())).findFirst().orElseThrow();
    }
    private static void check(boolean condition, String message) {
        if (!condition) { throw new AssertionError(message); }
    }
}
