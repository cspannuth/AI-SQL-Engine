import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import com.sleepycat.je.*;

/** Physical row IDs for version 2 and later; older tables keep their original keys. */
public final class RowIdentity {
    private RowIdentity() { }

    /** Flip the sign bit so Berkeley DB's byte ordering matches signed integer order. */
    public static byte[] key(long rowId) {
        return ByteBuffer.allocate(Long.BYTES).putLong(rowId ^ Long.MIN_VALUE).array();
    }

    public static long value(DatabaseEntry key) {
        if (key.getSize() != Long.BYTES) { throw new IllegalStateException("Invalid row ID key"); }
        return ByteBuffer.wrap(key.getData(), key.getOffset(), key.getSize()).getLong() ^ Long.MIN_VALUE;
    }

    /** Apply SQLite-style integer, numeric-text, and integral-REAL conversion. */
    public static long integer(Object value) {
        if (value == null) { throw new IllegalArgumentException("Row IDs cannot be null in UPDATE"); }
        if (value instanceof Integer || value instanceof Long) { return ((Number) value).longValue(); }
        String text = value.toString().trim();
        try { return Long.parseLong(text); }
        catch (NumberFormatException notIntegerText) {
            // Decimal/exponent text follows SQLite's REAL conversion, including rounding.
            // BigDecimal rejects Java-only spellings such as hexadecimal floats and NaN.
            try {
                double number = new BigDecimal(text).doubleValue();
                // REAL conversion excludes both 2^63 endpoints; integer text uses long parsing above.
                if (number > -0x1.0p63 && number < 0x1.0p63 && number == Math.rint(number)) {
                    return (long) number;
                }
            } catch (NumberFormatException invalidNumber) { /* Report the common type error below. */ }
        }
        throw new IllegalArgumentException("Row ID must be a signed 64-bit integer: " + value);
    }

    /** One INSERT's allocator includes pending rows without persisting a separate counter. */
    public static final class Allocator {
        private final Table table;
        private final Set<Long> pending = new HashSet<>();
        private long largest;
        private boolean hasRows;

        public Allocator(Table table) {
            this.table = table;
            DatabaseEntry key = new DatabaseEntry();
            try (Cursor cursor = table.getDB().openCursor(DBMS.getTransaction(), null)) {
                if (cursor.getLast(key, new DatabaseEntry(), null) == OperationStatus.SUCCESS) {
                    largest = value(key);
                    hasRows = true;
                }
            }
        }

        public long next(Object requested) {
            long rowId;
            if (requested != null) { rowId = integer(requested); }
            else if (largest != Long.MAX_VALUE) { rowId = largest + 1; }
            else { rowId = unusedPositive(); }
            if (pending.contains(rowId) || exists(rowId)) {
                throw new IllegalArgumentException("Duplicate row ID: " + rowId);
            }
            pending.add(rowId);
            largest = hasRows ? Math.max(largest, rowId) : rowId;
            hasRows = true;
            return rowId;
        }

        private long unusedPositive() {
            for (int attempt = 0; attempt < 100; attempt++) {
                long candidate = ThreadLocalRandom.current().nextLong(Long.MAX_VALUE) + 1;
                if (!pending.contains(candidate) && !exists(candidate)) { return candidate; }
            }
            throw new IllegalArgumentException("Could not allocate an unused row ID");
        }

        private boolean exists(long rowId) {
            return table.getDB().get(DBMS.getTransaction(), new DatabaseEntry(key(rowId)),
                    new DatabaseEntry(), null) == OperationStatus.SUCCESS;
        }
    }

}
