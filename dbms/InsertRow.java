import java.io.*;
import java.util.*;
import com.sleepycat.je.DatabaseEntry;

/** Encode versioned row keys and the shared signed-short offset payload format. */
public class InsertRow {
    public static final int IS_NULL = -1;
    public static final int IS_PKEY = -2;
    private final Table table;
    private final Object[] columnVals;
    private final RowOutput keyBuffer = new RowOutput();
    private final RowOutput valueBuffer = new RowOutput();
    private final int[] offsets;
    private final Long rowId;

    public InsertRow(Table table, Object[] values) {
        this(table, values, null);
    }

    public InsertRow(Table table, Object[] values, Long rowId) {
        if (values.length != table.numColumns()) {
            throw new IllegalArgumentException("Row width does not match table");
        }
        this.table = table;
        this.rowId = rowId;
        this.columnVals = values;
        this.offsets = new int[values.length + 1];
    }

    public void marshall() {
        if (valueBuffer.getBufferLength() != 0) {
            throw new IllegalStateException("This row was already encoded");
        }
        int offset = 2 * offsets.length;
        byte[][] encoded = new byte[columnVals.length][];
        try {
            if (table.usesRowIds()) {
                if (rowId == null) { throw new IllegalStateException("A row ID is required for this table"); }
                keyBuffer.write(RowIdentity.key(rowId));
            }
            for (int i = 0; i < columnVals.length; i++) {
                Column column = table.getColumn(i);
                Object value = columnVals[i];
                if (value == null) {
                    if (column.isPrimaryKey() && !table.usesRowIds()) {
                        throw new IllegalArgumentException("Primary keys cannot be null");
                    }
                    offsets[i] = IS_NULL;
                    continue;
                }
                encoded[i] = encode(column, value);
                if (table.usesRowIds() && i == table.getRowIdColumn()) {
                    offsets[i] = IS_PKEY;
                } else if (!table.usesRowIds() && column.isPrimaryKey()) {
                    offsets[i] = IS_PKEY;
                    keyBuffer.write(encoded[i]);
                } else {
                    offsets[i] = offset;
                    offset = Math.addExact(offset, encoded[i].length);
                }
            }
            if (offset > Short.MAX_VALUE) {
                throw new IllegalArgumentException("Encoded row exceeds the 32767-byte storage limit");
            }
            offsets[columnVals.length] = offset;
            for (int position : offsets) { valueBuffer.writeShort(position); }
            for (int i = 0; i < columnVals.length; i++) {
                if (offsets[i] >= 0) { valueBuffer.write(encoded[i]); }
            }
        } catch (IOException e) { throw new IllegalStateException("Could not encode row", e); }
    }

    private byte[] encode(Column column, Object value) throws IOException {
        try (RowOutput out = new RowOutput()) {
            switch (column.getType()) {
                case Column.INTEGER:
                    if (column.getLength() == 4) { out.writeInt(((Number) value).intValue()); }
                    else { out.writeLong(((Number) value).longValue()); }
                    break;
                case Column.REAL: out.writeDouble((Double) value); break;
                default: out.write(((String) value).getBytes(table.getStringCharset()));
            }
            return out.getBufferBytes();
        }
    }

    public DatabaseEntry keyEntry() { return new DatabaseEntry(keyBuffer.getBufferBytes()); }
    public Object getColumnValue(int index) { return columnVals[index]; }
    public DatabaseEntry valueEntry() { return new DatabaseEntry(valueBuffer.getBufferBytes()); }
    public RowOutput getKeyBuffer() { return keyBuffer; }
    public RowOutput getValueBuffer() { return valueBuffer; }
    public String toString() { return "offsets: " + Arrays.toString(offsets); }
}
