import java.nio.ByteBuffer;
import java.util.*;
import com.sleepycat.je.*;

/** A validated change set; callers finish RETURNING evaluation before calling write. */
final class MutationBatch {
    final Table table;
    final List<InsertRow> rows;
    final Set<ByteBuffer> removed;
    final Map<ByteBuffer,InsertRow> updates;
    final boolean dropping;
    private List<MutationBatch> plan;
    MutationBatch(Table table,List<InsertRow> rows,Set<ByteBuffer> removed,Map<ByteBuffer,InsertRow> updates,boolean dropping) {
        this.table=table; this.rows=List.copyOf(rows); this.removed=Set.copyOf(removed);
        this.updates=new LinkedHashMap<>(updates);
        this.dropping=dropping;
    }
    static MutationBatch prepare(Table table,List<InsertRow> rows,Set<ByteBuffer> removed,Map<ByteBuffer,InsertRow> updates) {
        return prepare(new MutationBatch(table,rows,removed,updates,false));
    }
    static MutationBatch dropping(Table table) {
        Set<ByteBuffer> removed=new HashSet<>(); Map<ByteBuffer,InsertRow> updates=new LinkedHashMap<>();
        try(TableIterator iterator=new TableIterator(table)) {
            while(iterator.next()) { ByteBuffer key=ByteBuffer.wrap(iterator.getKey()); removed.add(key); updates.put(key,null); }
        }
        return prepare(new MutationBatch(table,List.of(),removed,updates,true));
    }
    private static MutationBatch prepare(MutationBatch root) {
        ReferentialActions.Expansion expanded=ReferentialActions.expand(root);
        root.plan=expanded.batches();
        try(QueryContext.Scope scope=ConstraintValidator.overlay(expanded.overlay())) {
            for(MutationBatch batch:root.plan) {
                if(batch.dropping) { ConstraintValidator.validateDrop(batch.table); }
                else { ConstraintValidator.validateChange(batch.table,batch.rows,batch.removed); }
            }
        }
        return root;
    }
    void write() {
        for(MutationBatch batch:plan) {
            for(ByteBuffer key:batch.removed) { batch.table.getDB().delete(DBMS.getTransaction(),new DatabaseEntry(key.array())); }
        }
        for(MutationBatch batch:plan) { for(InsertRow row:batch.rows) {
            if(batch.table.getDB().putNoOverwrite(DBMS.getTransaction(),row.keyEntry(),row.valueEntry())!=OperationStatus.SUCCESS) {
                throw new IllegalStateException("Unexpected key conflict while applying a validated mutation");
            }
        } }
    }
}
