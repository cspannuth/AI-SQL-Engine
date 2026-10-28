import java.util.*;
import com.sleepycat.je.*;

/** Named marks use raw in-memory snapshots because JE has no SQL savepoint API.
 * Snapshots preserve stored values exactly; rollback never replays SQL or defaults.
 */
final class Savepoints {
    private record Entry(byte[] key,byte[] value) { }
    private record Data(String name,boolean duplicates,List<Entry> entries) { }
    private record Mark(String name,List<Data> snapshot) { }
    private static final List<Mark> marks=new ArrayList<>();
    private static boolean ownsTransaction;
    static boolean catalogRecreated;

    static void clear() { marks.clear(); ownsTransaction=false; catalogRecreated=false; }

    static void save(String name) {
        boolean started=DBMS.getTransaction()==null;
        if(started) { DBMS.beginTransaction(); ownsTransaction=true; }
        try { marks.add(new Mark(name,capture())); }
        catch(RuntimeException error) { if(started) { DBMS.rollbackTransaction(); } throw error; }
    }

    static void release(String name) {
        int index=find(name);
        if(index==0 && ownsTransaction) { DBMS.commitTransaction(); }
        else { marks.subList(index,marks.size()).clear(); }
    }

    static void rollbackTo(String name) {
        int index=find(name);
        try { restore(marks.get(index).snapshot()); }
        catch(RuntimeException error) {
            DBMS.rollbackTransaction();
            throw new IllegalStateException("Could not restore savepoint",error);
        }
        marks.subList(index+1,marks.size()).clear();
    }

    private static int find(String name) {
        for(int i=marks.size()-1;i>=0;i--) { if(marks.get(i).name().equalsIgnoreCase(name)) { return i; } }
        throw new IllegalArgumentException("No such savepoint: "+name);
    }

    private static List<String> primaryNames() {
        List<String> names=new ArrayList<>();
        for(String table:Catalog.listTableNames()) { names.add(table+".tab.db"); }
        names.add(Catalog.CATALOG_NAME); names.add(IndexCatalog.NAME);
        return names;
    }

    private static List<Data> capture() {
        Table.cacheClose();
        List<Data> snapshot=new ArrayList<>();
        // Only engine-owned databases are touched; secondary contents are rebuilt from definitions.
        for(String name:primaryNames()) {
            DatabaseConfig config=new DatabaseConfig().setTransactional(true).setUseExistingConfig(true);
            try(Database database=DBMS.getEnv().openDatabase(DBMS.getTransaction(),name,config);
                Cursor cursor=database.openCursor(DBMS.getTransaction(),null)) {
                List<Entry> entries=new ArrayList<>();
                DatabaseEntry key=new DatabaseEntry(),value=new DatabaseEntry();
                while(cursor.getNext(key,value,null)==OperationStatus.SUCCESS) {
                    QueryBudget.reserveWorkingRow(key.getSize()+value.getSize());
                    entries.add(new Entry(bytes(key),bytes(value)));
                }
                snapshot.add(new Data(name,database.getConfig().getSortedDuplicates(),entries));
            }
        }
        return snapshot;
    }

    private static void restore(List<Data> snapshot) {
        Table.cacheClose();
        List<String> current=primaryNames();
        List<IndexDefinition> indexes=IndexCatalog.all();
        catalogRecreated=true;
        Catalog.close();
        for(IndexDefinition index:indexes) { DBMS.getEnv().removeDatabase(DBMS.getTransaction(),index.dbName()); }
        for(String name:current) { DBMS.getEnv().removeDatabase(DBMS.getTransaction(),name); }
        for(Data data:snapshot) {
            DatabaseConfig config=new DatabaseConfig().setTransactional(true).setAllowCreate(true)
                    .setSortedDuplicates(data.duplicates());
            try(Database database=DBMS.getEnv().openDatabase(DBMS.getTransaction(),data.name(),config)) {
                for(Entry entry:data.entries()) {
                    database.put(DBMS.getTransaction(),new DatabaseEntry(entry.key()),new DatabaseEntry(entry.value()));
                }
            }
        }
        Catalog.open(); Table.cacheInit(); IndexCatalog.rebuild();
    }

    private static byte[] bytes(DatabaseEntry entry) {
        return Arrays.copyOfRange(entry.getData(),entry.getOffset(),entry.getOffset()+entry.getSize());
    }
}
