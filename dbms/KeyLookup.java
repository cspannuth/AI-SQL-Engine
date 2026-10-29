import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.*;

/** Shared equality access to physical keys and declared secondary indexes. */
final class KeyLookup {
    record Path(IndexDefinition index, List<Integer> columns) {
        Path { columns = List.copyOf(columns); }
        boolean physical() { return index == null; }
    }

    private KeyLookup() { }

    static List<Integer> physicalColumns(Table table) {
        if (table.usesRowIds()) {
            return List.of(table.getRowIdColumn() < 0 ? table.numColumns() : table.getRowIdColumn());
        }
        Column key = table.primaryKeyColumn();
        return key == null ? List.of() : List.of(key.getIndex());
    }

    static Path choose(Table table, Collection<Integer> available) {
        if (table instanceof QueryTable) { return null; }
        List<Integer> primary = physicalColumns(table);
        // Legacy REAL keys store raw double bits: +0.0 and -0.0 can occupy different keys.
        // Their SQL equality needs a numeric secondary index or a scan.
        boolean exactPhysical = table.usesRowIds() || table.primaryKeyColumn() == null
                || table.primaryKeyColumn().getType() != Column.REAL;
        if (exactPhysical && !primary.isEmpty() && available.containsAll(primary)) { return new Path(null, primary); }
        Path best = null;
        for (IndexDefinition index : IndexCatalog.forTable(table.getName())) {
            List<Integer> columns = UniqueKeys.indexes(table, index.columns());
            if (available.containsAll(columns) && (best == null || index.unique() && !best.index().unique()
                    || index.unique() == best.index().unique() && columns.size() > best.columns().size())) {
                best = new Path(index, columns);
            }
        }
        return best;
    }

    /** A NULL or unrepresentable lookup value cannot equal a stored physical key. */
    static byte[] physicalKey(Table table, Object value) {
        if (value == null) { return null; }
        try {
            if (table.usesRowIds()) { return RowIdentity.key(RowIdentity.integer(value)); }
            Column column = table.primaryKeyColumn();
            try (RowOutput out = new RowOutput()) {
                if (column.getType() == Column.INTEGER) {
                    long number = RowIdentity.integer(value);
                    if (column.getLength() == 4) {
                        if (number < Integer.MIN_VALUE || number > Integer.MAX_VALUE) { return null; }
                        out.writeInt((int) number);
                    } else { out.writeLong(number); }
                } else if (column.getType() == Column.REAL) {
                    if (!(value instanceof Number)) { return null; }
                    out.writeDouble(((Number) value).doubleValue());
                } else {
                    String text = value.toString();
                    if (!table.getStringCharset().newEncoder().canEncode(text)) { return null; }
                    out.write(text.getBytes(table.getStringCharset()));
                }
                return out.getBufferBytes();
            }
        } catch (IllegalArgumentException invalid) { return null; }
        catch (IOException impossible) { throw new IllegalStateException(impossible); }
    }

    static boolean exists(Table table, List<Column> columns, List<Object> values, Set<ByteBuffer> removed) {
        if (table.getDB() == null) { table.open(); }
        List<Integer> positions = columns.stream().map(Column::getIndex).toList();
        Path path = choose(table, positions);
        try (TableIterator iterator = new TableIterator(table)) {
            if (path != null) {
                List<CompareTerm> terms = new ArrayList<>();
                for (int position : path.columns()) { terms.add(new CompareTerm(values.get(positions.indexOf(position)))); }
                iterator.lookup(path, terms);
            }
            while (iterator.next()) {
                if (removed.contains(ByteBuffer.wrap(iterator.getKey()))) { continue; }
                boolean equal = true;
                for (int i = 0; i < columns.size(); i++) {
                    if (!Objects.equals(SqlValues.key(iterator.getColumnVal(positions.get(i))), values.get(i))) {
                        equal = false; break;
                    }
                }
                if (equal) { return true; }
            }
        }
        return false;
    }
}
