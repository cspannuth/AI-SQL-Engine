import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import com.sleepycat.je.*;

/** Behavioral checks using the public SQL interface and isolated storage. */
public class EngineTests {
    private static int assertions;
    private static Path home;

    public static void main(String[] args) throws Exception {
        home = Paths.get(args[0]);
        try {
            DBMS.init(home);
            queries();
            joins();
            aggregates();
            mutations();
            transactions();
            storage();
            rowIdentity();
            syntaxAndErrors();
            DBMS.shutdown();
            DBMS.init(home);
            rows("SELECT label FROM persisted;", row("saved"));
            rows("SELECT id,label FROM previous ORDER BY id;", row(7,"changed"), row(8,"caf\u00e9"));
            sql("INSERT INTO previous VALUES(9,'after restart');");
            rows("SELECT id,label FROM previous WHERE id=9;", row(9,"after restart"));
            fails("INSERT INTO previous(label) VALUES('no generated key');");
            fails("SELECT * FROM unfinished;");
            DBMS.execute("BEGIN;");
            DBMS.execute("INSERT INTO persisted VALUES (2, 'uncommitted');");
            DBMS.shutdown();
            DBMS.init(home);
            rows("SELECT id FROM persisted ORDER BY id;", row(1));
            sql("ALTER TABLE legacy ADD COLUMN extra INTEGER DEFAULT 5;");
            sql("ALTER TABLE legacy RENAME label TO caption;");
            sql("ALTER TABLE legacy RENAME TO migrated_legacy;");
            DBMS.shutdown();
            DBMS.init(home);
            rows("SELECT id,caption,extra FROM migrated_legacy ORDER BY id;",row(7,"\u00e9",5),row(8,"caf\u00e9",5));
            fails("INSERT INTO migrated_legacy(id,caption) VALUES(9,'\u6f22');");
            Table migrated = new Table("migrated_legacy");
            Catalog.getMetadata(migrated);
            equal(migrated.getRowFormat(),0,"ALTER retains original row encoding");
            fails("ALTER TABLE migrated_legacy ADD bad INTEGER DEFAULT -1 CHECK(bad>0);");
            System.out.println("Engine checks passed: " + assertions);
        } finally { DBMS.shutdown(); }
    }

    private static void queries() {
        sql("CREATE TABLE items (id INTEGER PRIMARY KEY, category VARCHAR(20), amount REAL, note VARCHAR(20));");
        changed("INSERT INTO items VALUES (1,'A',10.5,NULL),(2,'A',20.0,''),(3,'B',NULL,'tail'),(4,NULL,5.0,NULL);", 4);
        rows("SELECT id, category FROM items ORDER BY id;", row(1,"A"),row(2,"A"),row(3,"B"),row(4,null));
        rows("SELECT id, id, 'fixed', NULL FROM items WHERE id = 2;", row(2,2,"fixed",null));
        rows("SELECT DISTINCT category FROM items ORDER BY category;", row((Object)null),row("A"),row("B"));
        rows("SELECT id FROM items ORDER BY id LIMIT 1 OFFSET 2;", row(3));
        rows("SELECT id FROM items ORDER BY id LIMIT 2,1;", row(3));
        rows("SELECT id FROM items LIMIT 0;");
        rows("SELECT id FROM items ORDER BY id LIMIT -1 OFFSET -3;",row(1),row(2),row(3),row(4));
        rows("SELECT amount AS price, id FROM items ORDER BY price DESC, 2 DESC LIMIT 2;",row(20.0,2),row(10.5,1));
        rows("SELECT 1+2*3, (1+2)*3, 7/2, 7/2.0, 1/0, 'a'||'b';",row(7,9,3,3.5,null,"ab"));
        rows("SELECT 1<2,2<1,NULL=1,NOT NULL,NULL IS NULL,NULL IS NOT NULL,1 IS 1.0;",
                row(1,0,null,null,1,0,1));
        rows("SELECT 1 AND NULL,0 AND NULL,1 OR NULL,0 OR NULL,'2x'+3,'no'+4,1.0%0.5;",
                row(null,0,1,null,5,4,null));
        rows("SELECT -9223372036854775808,9223372036854775807;",row(Long.MIN_VALUE,Long.MAX_VALUE));
        rows("SELECT id FROM items WHERE id='2';",row(2));
        rows("SELECT id FROM items WHERE id IN ('1','3') ORDER BY id;",row(1),row(3));
        rows("SELECT id FROM items WHERE 1 ORDER BY id LIMIT 1;",row(1));
        rows("SELECT id FROM items WHERE NULL;");
        rows("SELECT id FROM items WHERE NOT (amount > 10) ORDER BY id;",row(4));
        rows("SELECT id FROM items WHERE amount IS NULL OR amount < 6 ORDER BY id;",row(3),row(4));
        rows("SELECT id FROM items WHERE NOT (amount > 10 AND id = 1) ORDER BY id;",row(2),row(3),row(4));
        rows("SELECT id FROM items WHERE id IN (1,3,NULL) ORDER BY id;",row(1),row(3));
        rows("SELECT id FROM items WHERE id NOT IN (1,NULL);");
        rows("SELECT id FROM items WHERE id IN ();");
        rows("SELECT id FROM items WHERE id BETWEEN 2 AND 3 ORDER BY id;",row(2),row(3));
        rows("SELECT id FROM items WHERE id NOT BETWEEN 2 AND 3 ORDER BY id;",row(1),row(4));
        rows("SELECT id FROM items WHERE note LIKE 'TAIL';",row(3));
        rows("SELECT id FROM items WHERE category NOT LIKE 'a' ORDER BY id;",row(3));
        sql("CREATE TABLE patterns (id INT PRIMARY KEY, value TEXT);");
        sql("INSERT INTO patterns VALUES (1,'a.b'),(2,'axb'),(3,'a[b');");
        rows("SELECT id FROM patterns WHERE value LIKE 'a.b';",row(1));
        rows("SELECT id FROM patterns WHERE value LIKE 'a_b' ORDER BY id;",row(1),row(2),row(3));
        sql("CREATE TABLE emptytable (id INTEGER PRIMARY KEY, value TEXT);");
        fails("SELECT missing FROM emptytable;");
        fails("SELECT * FROM emptytable WHERE missing = 1;");
        fails("SELECT id FROM items ORDER BY 0;");
        fails("SELECT id FROM items ORDER BY 2;");
    }

    private static void joins() {
        sql("CREATE TABLE labels (id INTEGER PRIMARY KEY, label TEXT);");
        sql("INSERT INTO labels VALUES (1,'first'),(3,'third'),(9,'unused');");
        rows("SELECT i.id, l.label FROM items i JOIN labels l ON i.id=l.id ORDER BY i.id;",
                row(1,"first"),row(3,"third"));
        rows("SELECT i.id,l.label FROM items i LEFT OUTER JOIN labels l ON i.id=l.id ORDER BY i.id;",
                row(1,"first"),row(2,null),row(3,"third"),row(4,null));
        rows("SELECT i.id FROM items i LEFT JOIN labels l ON i.id=l.id WHERE l.id IS NULL ORDER BY i.id;",
                row(2),row(4));
        rows("SELECT i.id FROM items i LEFT JOIN labels l ON i.id=l.id AND l.id<0 ORDER BY i.id;",
                row(1),row(2),row(3),row(4));
        rows("SELECT i.id FROM items i LEFT JOIN emptytable e ON i.id=e.id ORDER BY i.id;",
                row(1),row(2),row(3),row(4));
        rows("SELECT a.id,b.id FROM items a,items b WHERE a.id<b.id ORDER BY a.id,b.id LIMIT 2;",row(1,2),row(1,3));
        rows("SELECT i.id,l.id FROM items i CROSS JOIN labels l ORDER BY i.id,l.id LIMIT 2;",row(1,1),row(1,3));
        rows("SELECT l.* FROM items i JOIN labels l ON i.id=l.id ORDER BY l.id;",row(1,"first"),row(3,"third"));
        fails("SELECT id FROM items i JOIN labels l ON i.id=l.id;");
        fails("SELECT * FROM items i, labels i;");
        fails("SELECT items.id FROM items i;");
        fails("SELECT i.id FROM items i JOIN labels l ON e.id=l.id JOIN emptytable e ON e.id=i.id;");
    }

    private static void aggregates() {
        rows("SELECT category,COUNT(*),COUNT(amount),SUM(amount),AVG(amount),MIN(amount),MAX(amount) "
                + "FROM items GROUP BY category ORDER BY category;",
                row(null,1,1,5.0,5.0,5.0,5.0),row("A",2,2,30.5,15.25,10.5,20.0),
                row("B",1,0,null,null,null,null));
        rows("SELECT COUNT(*),SUM(amount),AVG(amount),MIN(amount),MAX(amount),TOTAL(amount) FROM items WHERE id<0;",
                row(0,null,null,null,null,0.0));
        rows("SELECT category,COUNT(*) AS n FROM items GROUP BY category HAVING COUNT(*)>1 ORDER BY n DESC;",
                row("A",2));
        rows("SELECT COUNT(DISTINCT category),COUNT(*),COUNT(note) FROM items;",row(2,4,2));
        rows("SELECT COUNT(*)+1 FROM items;",row(5));
        rows("SELECT id,MAX(amount) FROM items;",row(2,20.0));
        rows("SELECT category,COUNT(*) AS n FROM items GROUP BY category HAVING n>1;",row("A",2));
        rows("SELECT category,SUM(amount) AS total FROM items GROUP BY category HAVING total>20;",row("A",30.5));
        try {
            SQLStatement statement = (SQLStatement) new Parser(new Lexer(new java.io.StringReader(
                    "SELECT category,SUM(amount) AS total FROM items GROUP BY category HAVING total>20;"))).parse().value;
            statement.execute();
            List<List<Object>> first = statement.getResult().getRows();
            statement.execute();
            equal(statement.getResult().getRows(),first,"Repeated statement execution");
        } catch (Exception e) { throw new AssertionError(e); }
        rows("SELECT COUNT(*) FROM items HAVING COUNT(*)>0;",row(4));
        rows("SELECT category FROM items GROUP BY category ORDER BY category;",row((Object)null),row("A"),row("B"));
        rows("SELECT category AS c, SUM(amount) AS total FROM items GROUP BY c ORDER BY total DESC;",
                row("A",30.5),row(null,5.0),row("B",null));
        fails("SELECT * FROM items WHERE COUNT(*)>0;");
        fails("SELECT id FROM items GROUP BY COUNT(*);");
        fails("SELECT SUM(COUNT(*)) FROM items;");
        fails("SELECT id FROM items HAVING id>0;");
    }

    private static void mutations() {
        sql("CREATE TABLE edits (id INTEGER PRIMARY KEY, value INTEGER NOT NULL, label VARCHAR(5));");
        changed("INSERT INTO edits(label,id,value) VALUES ('a',1,10),('b',2,20);",2);
        changed("UPDATE edits SET value=value+1,label='x' WHERE id=1;",1);
        rows("SELECT * FROM edits ORDER BY id;",row(1,11,"x"),row(2,20,"b"));
        changed("UPDATE edits SET id=3 WHERE id=1;",1);
        fails("UPDATE edits SET id=2 WHERE id=3;");
        fails("UPDATE edits SET id=9;");
        rows("SELECT id,value FROM edits ORDER BY id;",row(2,20),row(3,11));
        fails("UPDATE edits SET value=NULL;");
        fails("UPDATE edits SET value=10,label='toolong';");
        rows("SELECT value FROM edits ORDER BY id;",row(20),row(11));
        fails("INSERT INTO edits VALUES (4,40,'ok'),(2,22,'bad');");
        fails("INSERT INTO edits VALUES (4,40,'ok'),(4,41,'bad');");
        rows("SELECT id FROM edits ORDER BY id;",row(2),row(3));
        fails("INSERT INTO edits(id) VALUES (5);");
        fails("INSERT INTO edits(id,id,value) VALUES (5,5,50);");
        fails("INSERT INTO edits(missing) VALUES (1);");
        fails("UPDATE edits SET missing=1;");
        fails("UPDATE edits SET value=1,value=2;");
        changed("UPDATE edits SET value=value+1 WHERE id<0;",0);
        changed("DELETE FROM edits WHERE id=2;",1);
        rows("SELECT id FROM edits;",row(3));
        changed("DELETE FROM edits;",1);
        rows("SELECT * FROM edits;");
        sql("CREATE TABLE swaps(id INTEGER PRIMARY KEY,a INTEGER,b INTEGER);");
        sql("INSERT INTO swaps VALUES(1,10,20);");
        sql("UPDATE swaps SET a=b,b=a;");
        rows("SELECT a,b FROM swaps;",row(20,10));
    }

    private static void transactions() {
        sql("BEGIN;");
        sql("UPDATE items SET amount=99 WHERE id=1;");
        sql("DELETE FROM items WHERE id=2;");
        sql("INSERT INTO items VALUES(8,'Z',1,NULL);");
        sql("ROLLBACK;");
        rows("SELECT id,amount FROM items WHERE id<3 ORDER BY id;",row(1,10.5),row(2,20.0));
        rows("SELECT id FROM items WHERE id=8;");
        sql("BEGIN TRANSACTION;");
        sql("CREATE TABLE persisted(id INTEGER PRIMARY KEY,label TEXT);");
        sql("INSERT INTO persisted VALUES(1,'saved');");
        sql("COMMIT;");
        sql("BEGIN WORK;");
        sql("DROP TABLE persisted;");
        fails("SELECT * FROM persisted;");
        sql("ROLLBACK;");
        rows("SELECT label FROM persisted;",row("saved"));
        sql("BEGIN;");
        sql("CREATE TABLE temporarytable(id INTEGER PRIMARY KEY);");
        sql("INSERT INTO temporarytable VALUES(1);");
        sql("ROLLBACK;");
        fails("SELECT * FROM temporarytable;");
        sql("BEGIN;");
        sql("INSERT INTO persisted VALUES(2,'kept');");
        fails("INSERT INTO persisted VALUES(3,'new'),(1,'duplicate');");
        rows("SELECT id FROM persisted ORDER BY id;",row(1),row(2));
        sql("ROLLBACK;");
        rows("SELECT id FROM persisted;",row(1));
        fails("COMMIT;");
        fails("ROLLBACK;");
        sql("BEGIN;");
        fails("BEGIN;");
        sql("ROLLBACK;");
        sql("CREATE TABLE IF NOT EXISTS persisted(id INTEGER);");
        sql("DROP TABLE IF EXISTS unknown;");
        sql("CREATE TABLE droptest(id INTEGER PRIMARY KEY);");
        sql("DROP TABLE droptest;");
        fails("SELECT * FROM droptest;");
        sql("BEGIN;");
        sql("CREATE TABLE unfinished(id INTEGER PRIMARY KEY);");
        DBMS.shutdown();
        DBMS.init(home);
        fails("SELECT * FROM unfinished;");
    }

    private static void storage() throws Exception {
        sql("CREATE TABLE encoding(id INTEGER PRIMARY KEY,a VARCHAR(20),b CHAR(6),c VARCHAR(20),d REAL);");
        sql("INSERT INTO encoding VALUES(1,'\u00e9\u6f22','\u00e9',NULL,1.5),(2,'','x','',NULL),(3,NULL,'y',NULL,NULL);");
        rows("SELECT * FROM encoding ORDER BY id;",
                row(1,"\u00e9\u6f22","\u00e9    ",null,1.5),row(2,"","x     ","",null),row(3,null,"y     ",null,null));
        sql("INSERT INTO encoding VALUES(4294967296,'large','ok',NULL,0);");
        rows("SELECT id FROM encoding WHERE id=4294967296;",row(4294967296L));
        sql("INSERT INTO encoding VALUES(-9223372036854775808,'min','ok',NULL,0),(9223372036854775807,'max','ok',NULL,0);");
        rows("SELECT id FROM encoding WHERE id<0;",row(Long.MIN_VALUE));
        fails("INSERT INTO encoding VALUES(5,'ok','toolong',NULL,0);");
        sql("CREATE TABLE bigrow(id INTEGER PRIMARY KEY,value VARCHAR(32767));");
        fails("INSERT INTO bigrow VALUES(1,'" + "a".repeat(32767) + "');");
        rows("SELECT COUNT(*) FROM bigrow;",row(0));
        legacyFixture();
        rows("SELECT * FROM legacy;",row(7,"\u00e9"));
        sql("INSERT INTO legacy VALUES(8,'caf\u00e9');");
        rows("SELECT label FROM LEGACY WHERE id=8;",row("caf\u00e9"));
        fails("INSERT INTO legacy VALUES(9,'\u6f22');");
        fails("INSERT INTO legacy VALUES(4294967296,'large');");
        try { new RowInput(new byte[3]).readBytesAtOffset(0,-1); throw new AssertionError("Negative length accepted"); }
        catch (IllegalArgumentException expected) { assertions++; }
    }

    /** A golden fixture for the original unversioned, 32-bit, single-byte format. */
    private static void legacyFixture() throws Exception {
        DatabaseConfig config = new DatabaseConfig();
        config.setTransactional(true);
        config.setAllowCreate(true);
        try (Database catalog = DBMS.getEnv().openDatabase(null,Catalog.CATALOG_NAME,config);
             Database table = DBMS.getEnv().openDatabase(null,"Legacy.tab.db",config);
             RowOutput metadata = new RowOutput()) {
            metadata.writeByte(2);
            metadata.writeByte(2); metadata.writeBytes("id");
            metadata.writeByte(0); metadata.writeInt(4); metadata.writeBoolean(true); metadata.writeBoolean(true);
            metadata.writeByte(5); metadata.writeBytes("label");
            metadata.writeByte(3); metadata.writeInt(20); metadata.writeBoolean(false); metadata.writeBoolean(false);
            catalog.put(null,new DatabaseEntry("Legacy".getBytes(StandardCharsets.UTF_8)),
                    new DatabaseEntry(metadata.getBufferBytes()));
            table.put(null,new DatabaseEntry(new byte[]{0,0,0,7}),
                    new DatabaseEntry(new byte[]{-1,-2,0,6,0,7,(byte)0xe9}));
        }
    }

    private static void rowIdentity() throws Exception {
        long[] ids = {Long.MIN_VALUE, -100, -1, 0, 1, 100, Long.MAX_VALUE};
        byte[] prior = null;
        for (long id : ids) {
            byte[] key = RowIdentity.key(id);
            equal(RowIdentity.value(new DatabaseEntry(key)), id, "Row ID key round trip");
            if (prior != null) { equal(Arrays.compareUnsigned(prior, key) < 0, true, "Signed key order"); }
            byte[] wrapped = new byte[12];
            System.arraycopy(key, 0, wrapped, 2, key.length);
            equal(RowIdentity.value(new DatabaseEntry(wrapped, 2, key.length)), id, "Entry offsets");
            prior = key;
        }
        sql("CREATE TABLE bound_ids(id INTEGER PRIMARY KEY,label TEXT);");
        DBMS.execute("INSERT INTO bound_ids(label) VALUES(?);", List.of("generated"));
        DBMS.execute("INSERT INTO bound_ids VALUES(?,?);", Arrays.asList(null,"null parameter"));
        DBMS.execute("INSERT INTO bound_ids(rowid,label) VALUES(?,?);", List.of("8","numeric parameter"));
        rows("SELECT rowid,id,label FROM bound_ids ORDER BY id;",
                row(1,1,"generated"),row(2,2,"null parameter"),row(8,8,"numeric parameter"));
        sql("CREATE TABLE allocation_ceiling(id INTEGER PRIMARY KEY,label TEXT);");
        sql("INSERT INTO allocation_ceiling VALUES(9223372036854775807,'maximum');");
        sql("INSERT INTO allocation_ceiling(label) VALUES('random'),('random'),('random');");
        rows("SELECT COUNT(*),COUNT(DISTINCT id),MIN(id)>0,MAX(id) FROM allocation_ceiling;",
                row(4,4,1,Long.MAX_VALUE));
        sql("BEGIN;");
        sql("INSERT INTO allocation_ceiling(label) VALUES('rolled back');");
        sql("ROLLBACK;");
        rows("SELECT COUNT(*) FROM allocation_ceiling;", row(4));

        previousFixture();
        rows("SELECT * FROM previous;",row(7,"\u00e9"));
        sql("INSERT INTO previous VALUES(8,'caf\u00e9');");
        sql("UPDATE previous SET label='changed' WHERE id=7;");
        fails("INSERT INTO previous VALUES(NULL,'no generated key');");
        fails("SELECT rowid FROM previous;");
        fails("UPDATE previous SET id=8 WHERE id=7;");
        sql("BEGIN;");
        sql("DELETE FROM previous WHERE id=8;");
        sql("UPDATE previous SET id=10 WHERE id=7;");
        sql("ROLLBACK;");
        rows("SELECT * FROM previous ORDER BY id;",row(7,"changed"),row(8,"caf\u00e9"));
        Table metadata = new Table("previous");
        Catalog.getMetadata(metadata);
        equal(metadata.getStorageVersion(),1,"Previous catalog version retained");
        equal(metadata.usesRowIds(),false,"Previous keys remain unchanged");
    }

    /** Version 1 uses UTF-8 and 64-bit integers, but still stores the declared PK as its key. */
    private static void previousFixture() throws Exception {
        DatabaseConfig config = new DatabaseConfig();
        config.setTransactional(true);
        config.setAllowCreate(true);
        try (Database catalog = DBMS.getEnv().openDatabase(null,Catalog.CATALOG_NAME,config);
             Database table = DBMS.getEnv().openDatabase(null,"previous.tab.db",config);
             RowOutput metadata = new RowOutput(); RowOutput key = new RowOutput()) {
            metadata.writeByte(0); metadata.writeByte(1); metadata.writeByte(2);
            metadata.writeByte(2); metadata.writeBytes("id");
            metadata.writeByte(0); metadata.writeInt(8); metadata.writeBoolean(true); metadata.writeBoolean(true);
            metadata.writeByte(5); metadata.writeBytes("label");
            metadata.writeByte(3); metadata.writeInt(20); metadata.writeBoolean(false); metadata.writeBoolean(false);
            catalog.put(null,new DatabaseEntry("previous".getBytes(StandardCharsets.UTF_8)),
                    new DatabaseEntry(metadata.getBufferBytes()));
            key.writeLong(7);
            table.put(null,new DatabaseEntry(key.getBufferBytes()),
                    new DatabaseEntry(new byte[]{-1,-2,0,6,0,8,(byte)0xc3,(byte)0xa9}));
        }
    }

    private static void syntaxAndErrors() {
        sql("CREATE TABLE \"odd_table\"(\"select\" INT PRIMARY KEY, \"display_name\" TEXT);");
        sql("INSERT INTO \"odd_table\" VALUES(1,'it''s a; test');");
        rows("SELECT display_name FROM odd_table WHERE \"select\"=1;",row("it's a; test"));
        rows("-- comment\nSELECT 1 /* comment */ + 2;",row(3));
        fails("SELECT @ FROM items;");
        fails("SELECT id FROM items; SELECT id FROM items;");
        fails("SELECT 1 /* unfinished;");
        fails("CREATE TABLE duplicate(id INT,ID INT);");
        fails("CREATE TABLE wrong(id INT PRIMARY KEY,other INT PRIMARY KEY);");
        fails("SELECT * FROM missingtable;");
        rows("SELECT 123;",row(123));
        StringBuilder script = new StringBuilder("-- first\nSELECT 'a;''b'; /* ; */ SELECT\n1+2; SELECT 'pending");
        List<String> statements = SqlScript.takeStatements(script);
        equal(statements.size(),2,"Script split");
        equal(script.toString()," SELECT 'pending","Incomplete SQL preserved");
    }

    private static QueryResult sql(String sql) { return DBMS.execute(sql); }

    private static void changed(String sql,int expected) {
        equal(DBMS.execute(sql).getAffectedRows(),expected,sql);
    }

    private static List<Object> row(Object... values) { return Arrays.asList(values); }

    @SafeVarargs
    private static void rows(String sql,List<Object>... expected) {
        List<List<Object>> actual = DBMS.execute(sql).getRows();
        equal(actual.size(),expected.length,sql);
        for (int i=0;i<expected.length;i++) {
            equal(actual.get(i).size(),expected[i].size(),sql);
            for (int j=0;j<expected[i].size();j++) { equal(actual.get(i).get(j),expected[i].get(j),sql); }
        }
    }

    private static void fails(String sql) {
        try { DBMS.execute(sql); }
        catch (IllegalArgumentException expected) { assertions++; return; }
        throw new AssertionError("Expected a SQL error: "+sql);
    }

    private static void equal(Object actual,Object expected,String context) {
        assertions++;
        if (actual instanceof Number && expected instanceof Number) {
            if (SqlValues.compare(actual,expected)==0) { return; }
        } else if (Objects.equals(actual,expected)) { return; }
        throw new AssertionError(context+": expected "+expected+", got "+actual);
    }
}
