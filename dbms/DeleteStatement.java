import java.util.*;
import java.nio.ByteBuffer;
import com.sleepycat.je.*;

/** DELETE scans and validates before modifying any stored rows. */
public class DeleteStatement extends SQLStatement {
    private List<Object> returning;
    void setReturning(List<Object> values) { returning=values; }
    public DeleteStatement(Table table, ConditionalExpression where) { super(table, null, where); }

    protected void executeInternal() {
        Table table = getTable(0);
        table.open();
        if (getWhere() != null && !getWhere().getAggregates().isEmpty()) {
            throw new IllegalArgumentException("Aggregates are not allowed in WHERE");
        }
        List<byte[]> keys = new ArrayList<>();
        Set<ByteBuffer> removed = new HashSet<>();
        List<Object[]> snapshots=new ArrayList<>();
        try (TableIterator iterator = new TableIterator(this, table, true);
             QueryContext.Scope scope=QueryContext.sources(List.of(iterator))) {
            bindColumns(List.of(iterator));
            QueryPlan.choose(iterator, getWhere(), 0, List.of(iterator));
            if(getWhere()!=null) { for(QueryStatement query:getWhere().getSubqueries()) { query.describeChecked(); } }
            while (iterator.next()) {
                byte[] key = iterator.getKey();
                keys.add(key);
                removed.add(ByteBuffer.wrap(key));
                if(returning!=null) { snapshots.add(iterator.getSnapshot()); }
            }
        }
        Map<ByteBuffer,InsertRow> updates=new LinkedHashMap<>();
        for(ByteBuffer key:removed) { updates.put(key,null); }
        MutationBatch batch=MutationBatch.prepare(table,List.of(),removed,updates);
        result=Returning.evaluate(table,returning,snapshots,keys.size(),"Deleted "+keys.size()+" row(s).");
        batch.write();
    }
}
