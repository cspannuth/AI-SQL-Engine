import java.util.*;
import java.nio.ByteBuffer;
import com.sleepycat.je.*;

/** DELETE scans and validates before modifying any stored rows. */
public class DeleteStatement extends SQLStatement {
    public DeleteStatement(Table table, ConditionalExpression where) { super(table, null, where); }

    protected void executeInternal() {
        Table table = getTable(0);
        table.open();
        if (getWhere() != null && !getWhere().getAggregates().isEmpty()) {
            throw new IllegalArgumentException("Aggregates are not allowed in WHERE");
        }
        List<byte[]> keys = new ArrayList<>();
        Set<ByteBuffer> removed = new HashSet<>();
        try (TableIterator iterator = new TableIterator(this, table, true)) {
            bindColumns(List.of(iterator));
            while (iterator.next()) {
                byte[] key = iterator.getKey();
                keys.add(key);
                removed.add(ByteBuffer.wrap(key));
            }
        }
        ConstraintValidator.validateChange(table, List.of(), removed);
        for (byte[] key : keys) {
            table.getDB().delete(DBMS.getTransaction(), new DatabaseEntry(key));
        }
        result = QueryResult.changed("Deleted " + keys.size() + " row(s).", keys.size());
    }
}
