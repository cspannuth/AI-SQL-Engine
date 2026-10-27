import java.nio.ByteBuffer;
import java.util.*;
import com.sleepycat.je.*;

/** UPDATE evaluates assignments against each original row, then writes atomically. */
public class UpdateStatement extends SQLStatement {
    private final List<Column> assignments;

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
        try (TableIterator iterator = new TableIterator(this, table, true)) {
            bindColumns(List.of(iterator));
            for (Column assignment : assignments) {
                if (!assigned.add(assignment.getIndex())) {
                    throw new IllegalArgumentException("Duplicate assignment: " + assignment);
                }
                if (assignment.getUpdateVal() instanceof CompareTerm
                        && !((CompareTerm) assignment.getUpdateVal()).getAggregates().isEmpty()) {
                    throw new IllegalArgumentException("Aggregates are not allowed in UPDATE");
                }
            }
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
        RowIdentity.validatePrimaryKeys(table, replacements, oldKeys);
        for (byte[] key : originalKeys) {
            table.getDB().delete(DBMS.getTransaction(), new DatabaseEntry(key));
        }
        for (InsertRow row : replacements) {
            table.getDB().put(DBMS.getTransaction(), row.keyEntry(), row.valueEntry());
        }
        result = QueryResult.changed("Updated " + replacements.size() + " row(s).", replacements.size());
    }
}
