import java.nio.ByteBuffer;
import java.util.*;
import com.sleepycat.je.*;

/** INSERT with optional column lists and one or more VALUES rows. */
public class InsertStatement extends SQLStatement {
    private final List<List<Object>> values = new ArrayList<>();
    private boolean defaultValues;
    private QueryStatement source;
    private Upsert upsert;
    private List<Object> returning;

    public InsertStatement(Table table, ArrayList<Column> columns, ArrayList<Object> values) {
        super(table, columns, values);
        this.values.add(values);
    }

    public static InsertStatement fromRows(Table table, ArrayList<Column> columns,
            ArrayList<ArrayList<Object>> rows) {
        InsertStatement statement = new InsertStatement(table, columns, rows.get(0));
        for (int i = 1; i < rows.size(); i++) { statement.values.add(rows.get(i)); }
        return statement;
    }

    public static InsertStatement defaults(Table table) {
        InsertStatement statement = new InsertStatement(table, new ArrayList<>(), new ArrayList<>());
        statement.defaultValues = true;
        return statement;
    }
    public static InsertStatement fromQuery(Table table,ArrayList<Column> columns,QueryStatement source) {
        InsertStatement statement=new InsertStatement(table,columns,new ArrayList<>());
        statement.source=source;
        return statement;
    }
    void configure(Upsert upsert,List<Object> returning) {
        if(defaultValues && upsert!=null) { throw new IllegalArgumentException("DEFAULT VALUES cannot have ON CONFLICT"); }
        this.upsert=upsert; this.returning=returning;
    }

    protected void executeInternal() {
        Table table = getTable(0);
        table.open();
        List<Column> targets = new ArrayList<>();
        if (numColumns() == 0 && !defaultValues) {
            for (int i = 0; i < table.numColumns(); i++) { targets.add(table.getColumn(i)); }
        } else {
            Set<Integer> assigned = new HashSet<>();
            for (int i = 0; i < numColumns(); i++) {
                Column reference = getColumn(i);
                Column match = table.resolveColumn(reference);
                if (match == null) { throw new IllegalArgumentException("Unknown column: " + reference); }
                if (!assigned.add(match.getIndex())) {
                    throw new IllegalArgumentException("Duplicate column: " + reference);
                }
                targets.add(match);
            }
        }
        List<List<Object>> inputs=values;
        if(source!=null) {
            QueryResult selected=source.nested();
            if(selected.getColumns().size()!=targets.size()) { throw new IllegalArgumentException("INSERT query column count differs from its target"); }
            inputs=selected.getRows();
        }
        if(upsert!=null) { result=UpsertExecutor.execute(table,targets,inputs,upsert,returning); return; }
        List<InsertRow> encoded = new ArrayList<>();
        Set<ByteBuffer> keys = new HashSet<>();
        RowIdentity.Allocator rowIds = table.usesRowIds() ? new RowIdentity.Allocator(table) : null;
        for (List<Object> input : inputs) {
            InsertValues.Input row=InsertValues.evaluate(table,targets,input);
            Long rowId = null;
            if (rowIds != null) {
                int alias = table.getRowIdColumn();
                rowId = rowIds.next(alias >= 0 ? row.values()[alias] : row.requestedRowId());
            }
            InsertRow record = InsertValues.encode(table,row,rowId);
            if (!keys.add(ByteBuffer.wrap(record.keyEntry().getData()))
                    || table.getDB().get(DBMS.getTransaction(), record.keyEntry(),
                            new DatabaseEntry(), null) == OperationStatus.SUCCESS) {
                throw new IllegalArgumentException("Duplicate primary key");
            }
            encoded.add(record);
        }
        MutationBatch batch=MutationBatch.prepare(table,encoded,Set.of(),Map.of());
        List<Object[]> snapshots=new ArrayList<>();
        if(returning!=null) { for(InsertRow record:encoded) { snapshots.add(InsertValues.snapshot(table,record)); } }
        result=Returning.evaluate(table,returning,snapshots,encoded.size(),"Added "+encoded.size()+" row(s) to "+table+".");
        batch.write();
    }
}
