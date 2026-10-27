import java.util.*;
import com.sleepycat.je.*;

/** A transaction-scoped table scan with a rewind operation for nested-loop joins. */
public class TableIterator implements AutoCloseable {
    private final Table table;
    private Cursor cursor;
    private final DatabaseEntry key = new DatabaseEntry();
    private final DatabaseEntry value = new DatabaseEntry();
    private final ConditionalExpression where;
    private boolean beforeFirst = true;
    private boolean positioned;
    private boolean exhausted;
    private Object[] snapshot;
    private int numTuples;

    public TableIterator(SQLStatement statement, Table table, boolean evalWhere) {
        this.table = table;
        if (table.getDB() == null) { throw new IllegalStateException("Table is not open: " + table); }
        cursor = table.getDB().openCursor(DBMS.getTransaction(), null);
        where = evalWhere ? statement.getWhere() : null;
    }

    public Table getTable() { return table; }
    public Column getColumn(int index) { return table.getColumn(index); }
    public int numColumns() { return table.numColumns(); }
    public int numTuples() { return numTuples; }

    public void rewind() {
        beforeFirst = true;
        exhausted = false;
        positioned = false;
        snapshot = null;
    }

    public boolean first() {
        rewind();
        return advance();
    }

    public boolean next() {
        while (advance()) {
            if (where == null || where.isTrue()) {
                numTuples++;
                return true;
            }
        }
        return false;
    }

    private boolean advance() {
        if (cursor == null) { throw new IllegalStateException("This iterator is closed"); }
        snapshot = null;
        if (exhausted) { return false; }
        OperationStatus status = beforeFirst ? cursor.getFirst(key, value, null)
                : cursor.getNext(key, value, null);
        beforeFirst = false;
        positioned = status == OperationStatus.SUCCESS;
        exhausted = !positioned;
        return positioned;
    }

    public Object getColumnVal(int index) {
        Column column = table.getColumn(index);
        if (snapshot != null) { return snapshot[index]; }
        if (!positioned) { throw new IllegalStateException("Iterator is not positioned on a row"); }
        RowInput data = input(value);
        int offset = data.readShortAtOffset(index * 2);
        if (offset == InsertRow.IS_NULL) { return null; }
        RowInput source = offset == InsertRow.IS_PKEY ? input(key) : data;
        int position = offset == InsertRow.IS_PKEY ? 0 : offset;
        if (column.getType() == Column.INTEGER) {
            if (column.getLength() == 4) { return source.readIntAtOffset(position); }
            return source.readLongAtOffset(position);
        }
        if (column.getType() == Column.REAL) { return source.readDoubleAtOffset(position); }
        int length;
        if (offset == InsertRow.IS_PKEY) {
            length = key.getSize();
        } else {
            int end = data.readShortAtOffset((index + 1) * 2);
            int next = index + 1;
            while (end < 0 && next < table.numColumns()) {
                end = data.readShortAtOffset(++next * 2);
            }
            length = end - position;
        }
        return source.readStringAtOffset(position, length, table.getStringCharset());
    }

    private static RowInput input(DatabaseEntry entry) {
        return new RowInput(Arrays.copyOfRange(entry.getData(),
                entry.getOffset(), entry.getOffset() + entry.getSize()));
    }

    public Object[] getRow() {
        Object[] row = new Object[numColumns()];
        for (int i = 0; i < row.length; i++) { row[i] = getColumnVal(i); }
        return row;
    }

    public byte[] getKey() {
        if (!positioned) { throw new IllegalStateException("Iterator is not positioned"); }
        return Arrays.copyOfRange(key.getData(), key.getOffset(), key.getOffset() + key.getSize());
    }

    /** Used for aggregate representatives and the null side of a LEFT JOIN. */
    public void useSnapshot(Object[] row) { snapshot = row; }

    public void close() {
        if (cursor != null) {
            cursor.close();
            cursor = null;
        }
        positioned = false;
        snapshot = null;
    }

    public void printAll(java.io.PrintStream out) {
        List<String> names = new ArrayList<>();
        List<List<Object>> rows = new ArrayList<>();
        for (int i = 0; i < numColumns(); i++) { names.add(getColumn(i).getName()); }
        while (next()) { rows.add(Arrays.asList(getRow())); }
        new QueryResult(names, rows).print(out);
    }
}
