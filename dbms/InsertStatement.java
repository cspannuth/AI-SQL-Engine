import java.nio.ByteBuffer;
import java.util.*;
import com.sleepycat.je.*;

/** INSERT with optional column lists and one or more VALUES rows. */
public class InsertStatement extends SQLStatement {
    private final List<List<Object>> values = new ArrayList<>();

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

    protected void executeInternal() {
        Table table = getTable(0);
        table.open();
        List<Column> targets = new ArrayList<>();
        if (numColumns() == 0) {
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
        List<InsertRow> encoded = new ArrayList<>();
        Set<ByteBuffer> keys = new HashSet<>();
        RowIdentity.Allocator rowIds = table.usesRowIds() ? new RowIdentity.Allocator(table) : null;
        for (List<Object> input : values) {
            if (input.size() != targets.size()) {
                throw new IllegalArgumentException("Value count does not match the INSERT column count");
            }
            Object[] row = new Object[table.numColumns()];
            Object requestedRowId = null;
            for (int i = 0; i < targets.size(); i++) {
                Object value = input.get(i);
                if (value instanceof CompareTerm) {
                    CompareTerm expression = (CompareTerm) value;
                    if (!expression.getColumns().isEmpty() || !expression.getAggregates().isEmpty()) {
                        throw new IllegalArgumentException("VALUES expressions must be constant");
                    }
                    value = expression.getValue();
                }
                int index = targets.get(i).getIndex();
                if (index == table.numColumns()) { requestedRowId = value; }
                else { row[index] = value; }
            }
            Long rowId = null;
            if (rowIds != null) {
                int alias = table.getRowIdColumn();
                rowId = rowIds.next(alias >= 0 ? row[alias] : requestedRowId);
                if (alias >= 0) { row[alias] = rowId; }
            }
            for (int i = 0; i < row.length; i++) { row[i] = table.getColumn(i).adjustValue(row[i]); }
            InsertRow record = new InsertRow(table, row, rowId);
            record.marshall();
            if (!keys.add(ByteBuffer.wrap(record.keyEntry().getData()))
                    || table.getDB().get(DBMS.getTransaction(), record.keyEntry(),
                            new DatabaseEntry(), null) == OperationStatus.SUCCESS) {
                throw new IllegalArgumentException("Duplicate primary key");
            }
            encoded.add(record);
        }
        RowIdentity.validatePrimaryKeys(table, encoded, Collections.emptySet());
        // All logical validation precedes the first write.
        for (InsertRow record : encoded) {
            if (table.getDB().putNoOverwrite(DBMS.getTransaction(),
                    record.keyEntry(), record.valueEntry()) != OperationStatus.SUCCESS) {
                throw new IllegalStateException("Primary-key conflict during INSERT");
            }
        }
        result = QueryResult.changed("Added " + encoded.size() + " row(s) to " + table + ".", encoded.size());
    }
}
