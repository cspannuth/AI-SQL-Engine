import com.sleepycat.je.*;

/** DROP updates both the catalog and stored table in the same transaction. */
public class DropStatement extends SQLStatement {
    private boolean ifExists;
    public DropStatement(Table table) { super(table); }
    public void setIfExists(boolean value) { ifExists = value; }

    protected void executeInternal() {
        Table table = getTable(0);
        String name = Catalog.findName(table.getName());
        if (name == null) {
            if (ifExists) { result = QueryResult.changed("Table does not exist.", 0); return; }
            throw new IllegalArgumentException(table + ": no such table");
        }
        table.setName(name);
        table.open();
        MutationBatch batch=MutationBatch.dropping(table);
        batch.write();
        table.close();
        for (IndexDefinition index : IndexCatalog.forTable(table.getName())) { IndexCatalog.drop(index); }
        Catalog.removeMetadata(table);
        DBMS.getEnv().removeDatabase(DBMS.getTransaction(), table.dbName());
        result = QueryResult.changed("Dropped table " + table + ".", 0);
    }
}
