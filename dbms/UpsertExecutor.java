import java.nio.ByteBuffer;
import java.util.*;
import com.sleepycat.je.*;

/** Simulate per-input UPSERT decisions, then validate and write the resulting change once. */
final class UpsertExecutor {
    static QueryResult execute(Table table,List<Column> targets,List<List<Object>> input,Upsert upsert,List<Object> returning) {
        List<Integer> target=upsert.target.isEmpty() ? List.of() : UniqueKeys.indexes(table,upsert.target);
        boolean physical=table.usesRowIds() && (target.equals(List.of(table.numColumns()))
                || target.equals(List.of(table.getRowIdColumn())));
        List<List<Integer>> uniqueKeys=UniqueKeys.keys(table);
        if(!target.isEmpty() && !physical && uniqueKeys.stream().noneMatch(key->UniqueKeys.matches(key,target))) {
            throw new IllegalArgumentException("ON CONFLICT target is not a PRIMARY KEY or UNIQUE key");
        }
        Map<ByteBuffer,InsertRow> before=new LinkedHashMap<>(), state=new LinkedHashMap<>();
        Map<ByteBuffer,ByteBuffer> origins=new HashMap<>();
        try(TableIterator iterator=new TableIterator(table)) {
            while(iterator.next()) {
                Long id=table.usesRowIds() ? RowIdentity.value(new DatabaseEntry(iterator.getKey())) : null;
                InsertRow row=new InsertRow(table,iterator.getRow(),id); row.marshall();
                ByteBuffer key=key(row); before.put(key,row); state.put(key,row); origins.put(key,key);
            }
        }
        Table excluded=new Table(table.getName());
        excluded.setRangeVariable("excluded"); excluded.setQualifiedOnly(true); excluded.open();
        List<Object[]> affected=new ArrayList<>();
        try(TableIterator current=new TableIterator(table);TableIterator proposed=new TableIterator(excluded)) {
            List<TableIterator> sources=List.of(current,proposed);
            Set<Integer> assigned=new HashSet<>();
            if(upsert.assignments!=null) {
                for(Column assignment:upsert.assignments) {
                    Column column=table.resolveColumn(assignment);
                    if(column==null || !assigned.add(column.getIndex())) { throw new IllegalArgumentException("Unknown/duplicate UPSERT assignment: "+assignment); }
                    assignment.useColInfo(column);
                    RowExpressions.bind(expression(assignment),sources,true);
                }
            }
            if(upsert.where!=null) { RowExpressions.bind(upsert.where,sources,true); }
            try(QueryContext.Scope scope=QueryContext.sources(sources)) {
                for(List<Object> values:input) {
                    InsertValues.Input evaluated;
                    try(QueryContext.Scope inputScope=QueryContext.withoutSources()) {
                        evaluated=InsertValues.evaluate(table,targets,values);
                    }
                    Object requested=table.getRowIdColumn()>=0 ? evaluated.values()[table.getRowIdColumn()] : evaluated.requestedRowId();
                    Long id=table.usesRowIds() ? nextId(requested,state) : null;
                    InsertRow candidate=InsertValues.encode(table,evaluated,id);
                    ConstraintValidator.checkRows(table,List.of(candidate));
                    ByteBuffer conflict=find(state,candidate,target,physical,uniqueKeys);
                    if(conflict==null) {
                        if(find(state,candidate,List.of(),false,uniqueKeys)!=null) { throw new IllegalArgumentException("Uniqueness conflict outside ON CONFLICT target"); }
                        state.put(key(candidate),candidate); origins.put(key(candidate),null);
                        affected.add(InsertValues.snapshot(table,candidate));
                        continue;
                    }
                    if(upsert.assignments==null) { continue; }
                    InsertRow original=state.get(conflict);
                    current.useSnapshot(InsertValues.snapshot(table,original));
                    proposed.useSnapshot(InsertValues.snapshot(table,candidate));
                    if(upsert.where!=null && !upsert.where.isTrue()) { continue; }
                    Object[] row=original.getValues();
                    Long rowId=table.usesRowIds() ? RowIdentity.value(original.keyEntry()) : null;
                    for(Column assignment:upsert.assignments) {
                        Object value=expression(assignment).getValue();
                        int index=assignment.getIndex();
                        if(table.usesRowIds() && (index==table.numColumns() || index==table.getRowIdColumn())) {
                            rowId=RowIdentity.integer(value);
                            if(table.getRowIdColumn()>=0) { row[table.getRowIdColumn()]=rowId; }
                        } else { row[index]=table.getColumn(index).adjustValue(value); }
                    }
                    InsertRow replacement=new InsertRow(table,row,rowId); replacement.marshall();
                    ConstraintValidator.checkRows(table,List.of(replacement));
                    ByteBuffer origin=origins.remove(conflict);
                    state.remove(conflict);
                    if(find(state,replacement,List.of(),false,uniqueKeys)!=null) { throw new IllegalArgumentException("UPSERT update violates uniqueness"); }
                    state.put(key(replacement),replacement); origins.put(key(replacement),origin);
                    affected.add(InsertValues.snapshot(table,replacement));
                }
            }
        }
        Map<ByteBuffer,InsertRow> byOrigin=new HashMap<>();
        List<InsertRow> additions=new ArrayList<>();
        for(Map.Entry<ByteBuffer,InsertRow> entry:state.entrySet()) {
            ByteBuffer origin=origins.get(entry.getKey());
            if(origin==null) { additions.add(entry.getValue()); } else { byOrigin.put(origin,entry.getValue()); }
        }
        Set<ByteBuffer> removed=new HashSet<>();
        Map<ByteBuffer,InsertRow> updates=new LinkedHashMap<>();
        for(Map.Entry<ByteBuffer,InsertRow> entry:before.entrySet()) {
            InsertRow after=byOrigin.get(entry.getKey());
            if(after!=entry.getValue()) {
                removed.add(entry.getKey()); updates.put(entry.getKey(),after);
                if(after!=null) { additions.add(after); }
            }
        }
        MutationBatch batch=MutationBatch.prepare(table,additions,removed,updates);
        QueryResult result=Returning.evaluate(table,returning,affected,affected.size(),"Upserted "+affected.size()+" row(s).");
        batch.write();
        return result;
    }
    private static CompareTerm expression(Column column) {
        Object value=column.getUpdateVal();
        return value instanceof CompareTerm ? (CompareTerm)value : new CompareTerm(value);
    }
    private static ByteBuffer key(InsertRow row) { return ByteBuffer.wrap(row.keyEntry().getData()); }
    private static ByteBuffer find(Map<ByteBuffer,InsertRow> rows,InsertRow candidate,List<Integer> target,
            boolean physical,List<List<Integer>> keys) {
        if(physical) { return rows.containsKey(key(candidate)) ? key(candidate) : null; }
        if(target.isEmpty() && rows.containsKey(key(candidate))) { return key(candidate); }
        for(Map.Entry<ByteBuffer,InsertRow> entry:rows.entrySet()) {
            for(List<Integer> columns:target.isEmpty() ? keys : List.of(target)) {
                Object[] a=entry.getValue().getValues(),b=candidate.getValues();
                boolean same=true;
                for(int column:columns) {
                    if(a[column]==null || b[column]==null || !Objects.equals(SqlValues.key(a[column]),SqlValues.key(b[column]))) { same=false; break; }
                }
                if(same) { return entry.getKey(); }
            }
        }
        return null;
    }
    private static long nextId(Object requested,Map<ByteBuffer,InsertRow> rows) {
        if(requested!=null) { return RowIdentity.integer(requested); }
        Long largest=null;
        for(InsertRow row:rows.values()) {
            long id=RowIdentity.value(row.keyEntry());
            largest=largest==null ? id : Math.max(largest,id);
        }
        if(largest==null) { return 1; }
        if(largest!=Long.MAX_VALUE) { return largest+1; }
        for(int attempt=0;attempt<100;attempt++) {
            long id=java.util.concurrent.ThreadLocalRandom.current().nextLong(Long.MAX_VALUE)+1;
            if(!rows.containsKey(ByteBuffer.wrap(RowIdentity.key(id)))) { return id; }
        }
        throw new IllegalArgumentException("Could not allocate a row ID");
    }
}
