import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;

/** Default metadata must be discoverable without evaluating its expressions. */
public final class SchemaDefaultsTests {
    public static void main(String[] args) {
        Path home = Path.of(args[0]);
        try {
            DBMS.init(home);
            try {
                DBMS.execute("CREATE TABLE invalid_default(n INTEGER DEFAULT (SUM(1)));");
                throw new AssertionError("Aggregate defaults must fail clearly");
            } catch (IllegalArgumentException expected) {
                check(Catalog.findName("invalid_default") == null, "Invalid default did not create a table");
            }
            DBMS.execute("CREATE TABLE defaults(id INTEGER PRIMARY KEY,stamp TEXT DEFAULT CURRENT_TIMESTAMP,"
                    + "n INTEGER DEFAULT (ABS(-9223372036854775808)));");
            DBMS.execute("INSERT INTO defaults(id,n) VALUES(1,7),(2,8);");
            List<List<Object>> rows = DBMS.execute("SELECT stamp,n FROM defaults ORDER BY id;").getRows();
            check(rows.get(0).get(0).equals(rows.get(1).get(0)), "One statement clock for all defaulted rows");
            LocalDateTime stamp = LocalDateTime.parse((String) rows.get(0).get(0), DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
            check(Duration.between(stamp.toInstant(ZoneOffset.UTC), java.time.Instant.now()).abs().getSeconds() < 120,
                    "CURRENT_TIMESTAMP default uses current UTC time");
            DBMS.shutdown();
            try (DatabaseAdapter adapter = DatabaseAdapter.open(home, DatabaseAdapter.AccessPolicy.readAll("reader"))) {
                DatabaseAdapter.TableSchema schema = adapter.describeTable("defaults");
                check(schema.columns().get(1).hasDefault() && schema.columns().get(1).defaultValue() == null
                        && "CURRENT_TIMESTAMP".equals(schema.columns().get(1).defaultSql()), "Clock SQL persisted separately from a literal");
                check("(ABS(-9223372036854775808))".equals(schema.columns().get(2).defaultSql()),
                        "Discovery retains SQL and does not evaluate a failing default");
                check(adapter.queryReadOnly("SELECT n FROM defaults ORDER BY id;").rows().equals(List.of(List.of(7L), List.of(8L))),
                        "Explicit INSERT values bypass the failing default");
            }
            System.out.println("Default API checks passed: statement clock, UTC values, persisted SQL, and schema-only evaluation");
        } finally { DBMS.shutdown(); }
    }

    private static void check(boolean condition, String message) {
        if (!condition) { throw new AssertionError(message); }
    }
}
