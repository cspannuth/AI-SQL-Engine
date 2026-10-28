import java.nio.file.*;
import java.util.*;

/** Composite metadata and rebuilt index behavior after savepoint restoration. */
public final class IntegrityTests {
    public static void main(String[] args) {
        Path home=Paths.get(args[0]);
        try {
            DBMS.init(home);
            DBMS.execute("CREATE TABLE parent(a INTEGER,b TEXT,PRIMARY KEY(b,a),UNIQUE(a,b));");
            DBMS.execute("CREATE TABLE child(id INTEGER PRIMARY KEY,x INTEGER,y TEXT,"
                    +"FOREIGN KEY(x,y) REFERENCES parent(a,b) ON UPDATE CASCADE ON DELETE SET NULL DEFERRABLE INITIALLY DEFERRED);");
            DBMS.execute("CREATE INDEX child_lookup ON child(x);");
            DBMS.execute("INSERT INTO parent VALUES(1,'key');");
            DBMS.execute("INSERT INTO child VALUES(1,1,'key');");
            DBMS.execute("SAVEPOINT before_cascade;");
            DBMS.execute("UPDATE parent SET a=2,b='new';");
            check(DBMS.execute("SELECT x,y FROM child;").getRows().equals(List.of(List.of(2L,"new"))),"Composite cascade");
            DBMS.execute("ROLLBACK TO before_cascade;");
            DBMS.execute("INSERT INTO parent VALUES(3,'temporary');");
            DBMS.execute("ROLLBACK TO before_cascade;");
            check(DBMS.execute("SELECT a,b FROM parent;").getRows().equals(List.of(List.of(1L,"key"))),"Snapshot reused exactly");
            DBMS.execute("RELEASE before_cascade;");
            DBMS.shutdown();
            try(DatabaseAdapter adapter=DatabaseAdapter.open(home,DatabaseAdapter.AccessPolicy.readAll("reader"))) {
                DatabaseAdapter.TableSchema parent=adapter.describeTable("parent");
                check(parent.primaryKeyColumns().equals(List.of("b","a")),"Primary-key order persisted");
                check(parent.uniqueKeys().equals(List.of(List.of("a","b"))),"Composite unique metadata persisted");
                check(parent.rowIdColumn()==null && parent.columns().get(0).nullable(),"Composite key keeps ordinary row ID/null rules");
                ForeignKey reference=adapter.describeTable("child").foreignKeys().get(0);
                check(reference.columns().equals(List.of("x","y")) && reference.parentColumns().equals(List.of("a","b")),"Composite reference metadata");
                check(reference.onUpdate().equals("CASCADE") && reference.onDelete().equals("SET NULL") && reference.deferred(),"Action/deferral metadata");
                QueryLimits tight=new QueryLimits(5,10000,1,100,100000,5000);
                DatabaseAdapter.ReadResult result=adapter.queryReadOnly("SELECT x,y FROM child WHERE x=?;",List.of(1),tight);
                check(result.rows().equals(List.of(List.of(1L,"key"))) && result.scannedRows()==1,"Restored index uses matching-row lookup");
                Map<?,?> schema=(Map<?,?>)Json.parse(Json.stringify(adapter.describeTable("child")));
                check(((List<?>)schema.get("foreignKeys")).size()==1,"Composite schema JSON serialization");
            }
            System.out.println("Integrity API checks passed: composite metadata, cascade, reusable savepoint, restart, and restored index budget");
        } finally { DBMS.shutdown(); }
    }
    private static void check(boolean condition,String message) { if(!condition) { throw new AssertionError(message); } }
}
