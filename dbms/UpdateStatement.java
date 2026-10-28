import java.nio.ByteBuffer;
import java.util.*;
import com.sleepycat.je.*;

/** UPDATE evaluates assignments against each original row, then writes atomically. */
public class UpdateStatement extends SQLStatement {
    private final List<Column> assignments;
    private List<Object> returning;
    void setReturning(List<Object> values) { returning=values; }

    public UpdateStatement(Table table, ArrayList<Column> columns, ConditionalExpression where) {
        super(table, columns, where);
        assignments = new ArrayList<>(columns);
        for (Column assignment : assignments) {
            if (assignment.getUpdateVal() instanceof CompareTerm) {
                for (Column reference : ((CompareTerm) assignment.getUpdateVal()).getColumns()) {
                    addColumn(reference);
                }
            }
        }
    }

    protected void executeInternal() {
        Table table = getTable(0);
        table.open();
        if (getWhere() != null && !getWhere().getAggregates().isEmpty()) {
            throw new IllegalArgumentException("Aggregates are not allowed in WHERE");
        }
        Set<Integer> assigned = new HashSet<>();
        List<byte[]> originalKeys = new ArrayList<>();
        List<InsertRow> replacements = new ArrayList<>();
        Set<ByteBuffer> oldKeys = new HashSet<>();
        Set<ByteBuffer> newKeys = new HashSet<>();
        try (TableIterator iterator = new TableIterator(this, table, true);
             QueryContext.Scope scope=QueryContext.sources(List.of(iterator))) {
            bindColumns(List.of(iterator));
            for (Column assignment : assignments) {
                if (!assigned.add(assignment.getIndex())) {
                    throw new IllegalArgumentException("Duplicate assignment: " + assignment);
                }
                if (assignment.getUpdateVal() instanceof CompareTerm
                        && !((CompareTerm) assignment.getUpdateVal()).getAggregates().isEmpty()) {
                    throw new IllegalArgumentException("Aggregates are not allowed in UPDATE");
                }
                if(assignment.getUpdateVal() instanceof CompareTerm) {
                    for(QueryStatement query:((CompareTerm)assignment.getUpdateVal()).getSubqueries()) { query.describeChecked(); }
                }
            }
            if(getWhere()!=null) { for(QueryStatement query:getWhere().getSubqueries()) { query.describeChecked(); } }
            while (iterator.next()) {
                Object[] row = iterator.getRow();
                Long rowId = table.usesRowIds() ? RowIdentity.value(new DatabaseEntry(iterator.getKey())) : null;
                for (Column assignment : assignments) {
                    Object value = assignment.getUpdateVal();
                    if (value instanceof CompareTerm) { value = ((CompareTerm) value).getValue(); }
                    int index = assignment.getIndex();
                    if (table.usesRowIds() && (index == table.numColumns() || index == table.getRowIdColumn())) {
                        rowId = RowIdentity.integer(value);
                        if (table.getRowIdColumn() >= 0) { row[table.getRowIdColumn()] = rowId; }
                    } else { row[index] = table.getColumn(index).adjustValue(value); }
                }
                InsertRow replacement = new InsertRow(table, row, rowId);
                replacement.marshall();
                byte[] oldKey = iterator.getKey();
                originalKeys.add(oldKey);
                oldKeys.add(ByteBuffer.wrap(oldKey));
                if (!newKeys.add(ByteBuffer.wrap(replacement.keyEntry().getData()))) {
                    throw new IllegalArgumentException("UPDATE would create duplicate primary keys");
                }
                replacements.add(replacement);
            }
        }
        for (InsertRow row : replacements) {
            if (!oldKeys.contains(ByteBuffer.wrap(row.keyEntry().getData()))
                    && table.getDB().get(DBMS.getTransaction(), row.keyEntry(),
                            new DatabaseEntry(), null) == OperationStatus.SUCCESS) {
                throw new IllegalArgumentException("UPDATE conflicts with an existing primary key");
            }
        }
        Map<ByteBuffer,InsertRow> updates=new LinkedHashMap<>();
        List<Object[]> snapshots=new ArrayList<>();
        for(int i=0;i<replacements.size();i++) {
            updates.put(ByteBuffer.wrap(originalKeys.get(i)),replacements.get(i));
            if(returning!=null) { snapshots.add(InsertValues.snapshot(table,replacements.get(i))); }
        }
        MutationBatch batch=MutationBatch.prepare(table,replacements,oldKeys,updates);
        result=Returning.evaluate(table,returning,snapshots,replacements.size(),"Updated "+replacements.size()+" row(s).");
        batch.write();
    }
}
