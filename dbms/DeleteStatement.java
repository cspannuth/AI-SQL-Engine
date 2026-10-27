import java.util.*;
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
        try (TableIterator iterator = new TableIterator(this, table, true)) {
            bindColumns(List.of(iterator));
            while (iterator.next()) { keys.add(iterator.getKey()); }
        }
        for (byte[] key : keys) {
            table.getDB().delete(DBMS.getTransaction(), new DatabaseEntry(key));
        }
        result = QueryResult.changed("Deleted " + keys.size() + " row(s).", keys.size());
    }
}
