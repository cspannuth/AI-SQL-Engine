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
    private KeyLookup.Path access;
    private List<CompareTerm> lookupValues;
    private SecondaryCursor indexCursor;
    private final DatabaseEntry secondaryKey = new DatabaseEntry();
    private final List<List<Object>> memory;
    private int memoryPosition = -1;
    private boolean closed;

    /** Scan all rows for storage-level validation, without a SQL filter. */
    public TableIterator(Table table) { this(null, table, false); }

    public TableIterator(SQLStatement statement, Table table, boolean evalWhere) {
        this.table = table;
        memory = table.memoryRows();
        if (memory == null) {
            if (table.getDB() == null) { throw new IllegalStateException("Table is not open: " + table); }
            cursor = table.getDB().openCursor(DBMS.getTransaction(), null);
        }
        where = evalWhere ? statement.getWhere() : null;
    }

    public Table getTable() { return table; }
    public Column getColumn(int index) { return table.getColumn(index); }
    public int numColumns() { return table.numColumns(); }
    public int numTuples() { return numTuples; }
    void lookup(KeyLookup.Path access, List<CompareTerm> values) {
        this.access = access;
        lookupValues = List.copyOf(values);
    }
    String accessDescription() {
        if (access == null) { return "SCAN " + table.getReferenceName(); }
        if (access.physical()) { return "SEARCH " + table.getReferenceName() + " USING PRIMARY KEY"; }
        return "SEARCH " + table.getReferenceName() + " USING INDEX " + access.index().name()
                + " (" + String.join("=? AND ", access.index().columns()) + "=?)";
    }

    public void rewind() {
        beforeFirst = true;
        exhausted = false;
        positioned = false;
        snapshot = null;
        memoryPosition = -1;
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
        QueryBudget.check();
        if (closed) { throw new IllegalStateException("This iterator is closed"); }
        snapshot = null;
        if (exhausted) { return false; }
        OperationStatus status;
        if (memory != null) {
            status = ++memoryPosition < memory.size() ? OperationStatus.SUCCESS : OperationStatus.NOTFOUND;
        } else if (access == null) {
            status = beforeFirst ? cursor.getFirst(key, value, null) : cursor.getNext(key, value, null);
        } else {
            if (!access.physical() && indexCursor == null) {
                indexCursor = table.getIndex(access.index().name()).openCursor(DBMS.getTransaction(), null);
            }
            if (beforeFirst) {
                List<Object> terms = new ArrayList<>();
                for (int i = 0; i < access.columns().size(); i++) {
                    int position = access.columns().get(i);
                    Object term = lookupValues.get(i).getValue();
                    if (position < table.numColumns()) { term = SqlValues.applyAffinity(table.getColumn(position), term); }
                    terms.add(term);
                }
                byte[] lookup = terms.contains(null) ? null : access.physical()
                        ? KeyLookup.physicalKey(table, terms.get(0)) : IndexCatalog.indexKey(terms);
                if (lookup == null) { status = OperationStatus.NOTFOUND; }
                else if (access.physical()) {
                    key.setData(lookup);
                    status = cursor.getSearchKey(key, value, null);
                } else {
                    secondaryKey.setData(lookup);
                    status = indexCursor.getSearchKey(secondaryKey, key, value, null);
                }
            } else { status = access.physical() ? OperationStatus.NOTFOUND : indexCursor.getNextDup(secondaryKey, key, value, null); }
        }
        beforeFirst = false;
        positioned = status == OperationStatus.SUCCESS;
        if (positioned) { QueryBudget.scannedRow(); }
        exhausted = !positioned;
        return positioned;
    }

    public Object getColumnVal(int index) {
        if (snapshot != null) { return snapshot[index]; }
        if (!positioned) { throw new IllegalStateException("Iterator is not positioned on a row"); }
        if (memory != null) { return memory.get(memoryPosition).get(index); }
        return readColumn(table, key, value, index);
    }

    static Object readColumn(Table table, DatabaseEntry key, DatabaseEntry value, int index) {
        if (table.usesRowIds() && (index == table.numColumns() || index == table.getRowIdColumn())) {
            return RowIdentity.value(key);
        }
        Column column = table.getColumn(index);
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

    /** Aggregate representatives must retain the hidden row ID as well as data. */
    public Object[] getSnapshot() {
        Object[] row = Arrays.copyOf(getRow(), numColumns() + 1);
        if (table.usesRowIds()) { row[numColumns()] = getColumnVal(numColumns()); }
        return row;
    }

    public byte[] getKey() {
        if (!positioned) { throw new IllegalStateException("Iterator is not positioned"); }
        return Arrays.copyOfRange(key.getData(), key.getOffset(), key.getOffset() + key.getSize());
    }

    /** Used for aggregate representatives and the null side of a LEFT JOIN. */
    public void useSnapshot(Object[] row) { snapshot = row; }

    public void close() {
        closed = true;
        if (indexCursor != null) { indexCursor.close(); indexCursor = null; }
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
