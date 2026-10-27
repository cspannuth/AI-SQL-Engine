"""Compare row identity and mutation behavior with SQLite across process restarts."""
import random
import sqlite3


def check_rowids(classpath, home, run_engine):
    statements = [
        "CREATE TABLE loose(label TEXT, amount REAL);",
        "INSERT INTO loose VALUES(NULL,NULL),('same',1),('same',1);",
        "SELECT rowid,oid,_rowid_,* FROM loose ORDER BY rowid;",
        "SELECT * FROM loose ORDER BY rowid;",
        "INSERT INTO loose(rowid,label) VALUES(-5,'negative'),(0,'zero'),(9,'explicit');",
        "INSERT INTO loose(label) VALUES('generated'),('generated');",
        "SELECT rowid,label FROM loose ORDER BY rowid;",
        "UPDATE loose SET amount=rowid,rowid=rowid+30 WHERE rowid<0;",
        "SELECT rowid,label,amount FROM loose ORDER BY rowid;",
        "UPDATE loose SET rowid=1 WHERE rowid=2;",
        "UPDATE loose SET rowid=NULL WHERE rowid=2;",
        "UPDATE loose SET rowid=1.5 WHERE rowid=2;",
        "INSERT INTO loose(rowid) VALUES(20),(1);",
        "SELECT rowid,label FROM loose ORDER BY rowid;",
        "DELETE FROM loose WHERE rowid=25;",
        "INSERT INTO loose(label) VALUES('after delete');",
        "SELECT rowid,label FROM loose ORDER BY rowid;",
        "BEGIN;",
        "INSERT INTO loose(label) VALUES('kept in transaction');",
        "INSERT INTO loose(rowid) VALUES(500),(1);",
        "SELECT rowid,label FROM loose ORDER BY rowid;",
        "ROLLBACK;",
        "INSERT INTO loose(label) VALUES('after rollback');",
        "SELECT rowid,label FROM loose ORDER BY rowid;",
        "CREATE TABLE automatic(id INTEGER PRIMARY KEY NOT NULL, label TEXT);",
        "INSERT INTO automatic(label) VALUES('one'),('two');",
        "INSERT INTO automatic VALUES(NULL,'three');",
        "INSERT INTO automatic(_rowid_,label) VALUES(10,'ten');",
        "INSERT INTO automatic VALUES(NULL,'eleven'),(50,'fifty'),(NULL,'fifty one');",
        "SELECT id,rowid,oid,_rowid_,label FROM automatic ORDER BY id;",
        "UPDATE automatic SET oid=20,label=id WHERE id=10;",
        "SELECT id,rowid,label FROM automatic ORDER BY id;",
        "UPDATE automatic SET id=NULL WHERE id=20;",
        "INSERT INTO automatic VALUES(1.5,'bad');",
        "INSERT INTO automatic VALUES(9223372036854775808,'bad');",
        "INSERT INTO automatic VALUES('60','string'),(61.0,'real');",
        "SELECT id,label FROM automatic ORDER BY id;",
        "CREATE TABLE negative(id INTEGER,PRIMARY KEY(id));",
        "INSERT INTO negative VALUES(-5),(NULL),(-2),(NULL);",
        "SELECT id,rowid FROM negative ORDER BY id;",
        "CREATE TABLE int_key(id INT PRIMARY KEY, label TEXT);",
        "INSERT INTO int_key VALUES(NULL,'a'),(NULL,'b'),(8,'c');",
        "SELECT rowid,id,label FROM int_key ORDER BY rowid;",
        "UPDATE int_key SET id=9 WHERE rowid=3;",
        "SELECT rowid,id FROM int_key ORDER BY rowid;",
        "INSERT INTO int_key VALUES(9,'duplicate');",
        "SELECT rowid,id FROM int_key ORDER BY rowid;",
        "CREATE TABLE text_key(code TEXT PRIMARY KEY, amount INTEGER);",
        "INSERT INTO text_key VALUES(NULL,1),(NULL,2),('a',3),('b',4);",
        "SELECT rowid,* FROM text_key ORDER BY rowid;",
        "INSERT INTO text_key VALUES('c',5),('a',6);",
        "UPDATE text_key SET code='z' WHERE amount<3;",
        "UPDATE text_key SET code='a' WHERE code='b';",
        "SELECT rowid,* FROM text_key ORDER BY rowid;",
        "UPDATE text_key SET code='new',amount=amount+1 WHERE code='a';",
        "UPDATE text_key SET code=NULL WHERE code='b';",
        "SELECT rowid,* FROM text_key ORDER BY rowid;",
        "CREATE TABLE strict_key(code TEXT PRIMARY KEY NOT NULL);",
        "INSERT INTO strict_key VALUES(NULL);",
        "CREATE TABLE real_key(code REAL PRIMARY KEY);",
        "INSERT INTO real_key VALUES(0.0);",
        "INSERT INTO real_key VALUES(-0.0);",
        "INSERT INTO real_key VALUES(NULL),(NULL);",
        "SELECT rowid,code FROM real_key ORDER BY rowid;",
        "CREATE TABLE shadow(rowid TEXT,oid INTEGER,_rowid_ TEXT,id INTEGER PRIMARY KEY);",
        "INSERT INTO shadow VALUES('r',7,'u',NULL);",
        "SELECT rowid,oid,_rowid_,id FROM shadow;",
        "UPDATE shadow SET rowid='changed';",
        "SELECT * FROM shadow;",
        "CREATE TABLE partial_shadow(rowid TEXT);",
        "INSERT INTO partial_shadow(rowid,oid) VALUES('visible',-5);",
        "SELECT rowid,oid,_rowid_ FROM partial_shadow;",
        "SELECT a.rowid,b.rowid,b.label FROM loose a LEFT JOIN loose b ON b.rowid=a.rowid+1 ORDER BY a.rowid;",
        "SELECT a.rowid,b.rowid,COUNT(*) FROM loose a LEFT JOIN loose b ON b.rowid=a.rowid+1 GROUP BY a.rowid ORDER BY a.rowid;",
        "SELECT rowid,label,MAX(amount) FROM loose;",
        "SELECT rowid,COUNT(*) FROM loose WHERE rowid>999;",
        "SELECT rowid AS amount,COUNT(*) FROM loose GROUP BY rowid ORDER BY 1;",
        "SELECT amount AS rowid,COUNT(*) FROM loose GROUP BY rowid ORDER BY 1;",
        "SELECT rowid FROM loose a,loose b;",
        "SELECT missing.rowid FROM loose;",
        "CREATE TABLE extremes(id INTEGER PRIMARY KEY);",
        "INSERT INTO extremes VALUES(-9223372036854775808),(-1),(0),(9223372036854775807);",
        "SELECT rowid,id FROM extremes ORDER BY id;",
        "UPDATE extremes SET id=9223372036854775808 WHERE id=0;",
        "DELETE FROM extremes WHERE id=9223372036854775807;",
        "INSERT INTO extremes VALUES(NULL);",
        "SELECT id FROM extremes ORDER BY id;",
        "CREATE TABLE random_rows(label TEXT,amount INTEGER);",
    ]
    for value in ["'1.0'", "'1e2'", "'9007199254740993.0'", "'9223372036854775807.0'",
                  "'-9223372036854775808.0'", "'9223372036854775807'", "'-9223372036854775808'",
                  "9223372036854774784.0", "'2x'", "'0x1p0'", "'NaN'", "'Infinity'", "'1.25'"]:
        statements += [
            "DELETE FROM extremes;",
            f"INSERT INTO extremes VALUES({value});",
            "SELECT id,rowid FROM extremes;",
        ]
    # Repeated duplicates and NULLs exercise independent physical row identities.
    rng = random.Random(18)
    for _ in range(30):
        label = rng.choice(["NULL", "'a'", "'b'"])
        amount = rng.choice(["NULL", "-1", "0", "8"])
        statements.append(f"INSERT INTO random_rows VALUES({label},{amount});")
    statements += [
        "UPDATE random_rows SET amount=amount+rowid WHERE rowid%3=0;",
        "DELETE FROM random_rows WHERE rowid%4=0;",
        "SELECT rowid,* FROM random_rows ORDER BY rowid;",
        "BEGIN;",
        "UPDATE random_rows SET rowid=rowid+100 WHERE rowid<5;",
        "INSERT INTO random_rows VALUES('uncommitted',1);",
        "ROLLBACK;",
        "SELECT rowid,* FROM random_rows ORDER BY rowid;",
    ]
    reference = sqlite3.connect(":memory:", isolation_level=None)
    total = 0

    def compare(batch):
        nonlocal total
        for sql, result in zip(batch, run_engine(classpath, home, batch)):
            total += 1
            try:
                cursor = reference.execute(sql)
                expected = [list(row) for row in cursor.fetchall()]
            except sqlite3.Error as error:
                if "error" not in result:
                    raise AssertionError(f"SQLite rejected {sql!r}, engine accepted it: {error}")
            else:
                if "error" in result or result["rows"] != expected:
                    raise AssertionError(f"{sql}\nSQLite: {expected}\nEngine: {result}")
                if cursor.rowcount >= 0 and result["affected"] != cursor.rowcount:
                    raise AssertionError(f"Wrong affected row count for {sql}: {result}")

    try:
        compare(statements)
        # A second JVM must decode new schemas and continue allocation from stored keys.
        compare([
            "INSERT INTO automatic(label) VALUES('after restart');",
            "SELECT rowid,id,label FROM automatic ORDER BY rowid;",
            "INSERT INTO loose(label) VALUES('after restart');",
            "SELECT rowid,* FROM loose ORDER BY rowid;",
            "INSERT INTO text_key VALUES('new',0);",
            "SELECT rowid,* FROM text_key ORDER BY rowid;",
            "INSERT INTO int_key VALUES(NULL,'after restart');",
            "SELECT rowid,id,label FROM int_key ORDER BY rowid;",
            "SELECT a.rowid,b.rowid FROM loose a LEFT JOIN loose b ON 0 ORDER BY a.rowid;",
        ])
    finally:
        reference.close()
    print(f"Row identity SQLite comparisons passed: {total} statements, including a JVM restart")
