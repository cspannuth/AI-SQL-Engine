import java.nio.ByteBuffer;
import java.util.*;
import com.sleepycat.je.*;

/** Expand referential actions in memory; validate the entire graph before writing. */
final class ReferentialActions {
    record Expansion(List<MutationBatch> batches,Map<String,List<Object[]>> overlay) { }
    private record Link(Table child,ForeignKey reference) { }
    private record Event(Table parent,InsertRow before,InsertRow after,int depth) { }
    private final Map<String,State> states=new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    private final Map<String,List<Link>> links=new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    private final Deque<Event> events=new ArrayDeque<>();
    private final MutationBatch root;

    private ReferentialActions(MutationBatch root) { this.root=root; }

    static Expansion expand(MutationBatch root) {
        if(root.updates.isEmpty()) { return new Expansion(List.of(root),null); }
        ReferentialActions actions=new ReferentialActions(root);
        for(String name:Catalog.listTableNames()) {
            Table child=new Table(name); Catalog.getMetadata(child);
            for(ForeignKey reference:child.getForeignKeys()) {
                if(!reference.onDelete().equals("NO ACTION") || !reference.onUpdate().equals("NO ACTION")) {
                    actions.links.computeIfAbsent(reference.parentTable(),ignored->new ArrayList<>()).add(new Link(child,reference));
                }
            }
        }
        if(!actions.links.containsKey(root.table.getName())) { return new Expansion(List.of(root),null); }
        return actions.run();
    }

    private Expansion run() {
        State state=state(root.table);
        for(ByteBuffer key:root.removed) { state.after.remove(key); state.origins.remove(key); }
        Map<InsertRow,ByteBuffer> updated=new IdentityHashMap<>();
        for(Map.Entry<ByteBuffer,InsertRow> entry:root.updates.entrySet()) {
            if(entry.getValue()!=null) { updated.put(entry.getValue(),entry.getKey()); }
            InsertRow before=state.before.get(entry.getKey());
            if(before!=null) { events.add(new Event(root.table,before,entry.getValue(),0)); }
        }
        for(InsertRow row:root.rows) {
            ByteBuffer key=key(row);
            if(state.after.putIfAbsent(key,row)!=null) { throw new IllegalArgumentException("Duplicate row key in mutation"); }
            state.origins.put(key,updated.get(row));
        }
        while(!events.isEmpty()) {
            Event event=events.removeFirst();
            if(event.depth()>1000) { throw new IllegalArgumentException("Foreign-key action depth exceeds 1000"); }
            for(Link link:links.getOrDefault(event.parent().getName(),List.of())) { apply(event,link); }
        }
        List<MutationBatch> batches=new ArrayList<>();
        Map<String,List<Object[]>> overlay=new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for(State value:states.values()) {
            List<Object[]> rows=new ArrayList<>();
            for(InsertRow row:value.after.values()) { rows.add(row.getValues()); }
            overlay.put(value.table.getName(),rows);
            MutationBatch batch=value.batch();
            if(!batch.rows.isEmpty() || !batch.removed.isEmpty()) { batches.add(batch); }
        }
        return new Expansion(batches,overlay);
    }

    private void apply(Event event,Link link) {
        ForeignKey reference=link.reference();
        List<Column> parents;
        try { parents=ForeignKeys.parents(event.parent(),reference); }
        catch(IllegalArgumentException mismatch) { if(root.dropping && event.parent()==root.table) { return; } throw mismatch; }
        List<Object> oldKey=ForeignKeys.parentKey(parents,event.before().getValues());
        if(oldKey.contains(null)) { return; }
        List<Object> newKey=event.after()==null ? null : ForeignKeys.parentKey(parents,event.after().getValues());
        if(oldKey.equals(newKey)) { return; }
        String action=event.after()==null ? reference.onDelete() : reference.onUpdate();
        if(action.equals("NO ACTION")) { return; }
        State child=state(link.child());
        List<Column> sources=ForeignKeys.children(child.table,reference);
        for(Map.Entry<ByteBuffer,InsertRow> entry:new ArrayList<>(child.after.entrySet())) {
            InsertRow row=child.after.get(entry.getKey());
            if(row==null) { continue; }
            List<Object> childKey=ForeignKeys.childKey(parents,sources,row.getValues());
            if(childKey.contains(null) || !childKey.equals(oldKey)) { continue; }
            if(action.equals("RESTRICT")) { throw new IllegalArgumentException("FOREIGN KEY RESTRICT: "+child.table); }
            InsertRow replacement=null;
            if(!(action.equals("CASCADE") && event.after()==null)) {
                Object[] values=row.getValues();
                Long rowId=child.table.usesRowIds() ? RowIdentity.value(row.keyEntry()) : null;
                for(int i=0;i<sources.size();i++) {
                    Column column=sources.get(i);
                    Object value=action.equals("SET NULL") ? null : action.equals("SET DEFAULT") ? column.evaluateDefault()
                            : event.after().getColumnValue(parents.get(i).getIndex());
                    if(column.getIndex()==child.table.getRowIdColumn()) {
                        rowId=RowIdentity.integer(value); values[column.getIndex()]=rowId;
                    } else { values[column.getIndex()]=column.adjustValue(SqlValues.applyAffinity(column,value)); }
                }
                replacement=new InsertRow(child.table,values,rowId); replacement.marshall();
                ConstraintValidator.checkRows(child.table,List.of(replacement));
            }
            child.change(entry.getKey(),replacement);
            events.add(new Event(child.table,row,replacement,event.depth()+1));
        }
    }

    private State state(Table table) {
        State state=states.get(table.getName());
        if(state==null) { state=new State(table); states.put(table.getName(),state); }
        return state;
    }
    private static ByteBuffer key(InsertRow row) { return ByteBuffer.wrap(row.keyEntry().getData()); }

    private final class State {
        final Table table;
        final Map<ByteBuffer,InsertRow> before=new LinkedHashMap<>(),after=new LinkedHashMap<>();
        final Map<ByteBuffer,ByteBuffer> origins=new HashMap<>();
        State(Table table) {
            this.table=table;
            if(table.getDB()==null) { table.open(); }
            try(TableIterator iterator=new TableIterator(table)) {
                while(iterator.next()) {
                    Long id=table.usesRowIds() ? RowIdentity.value(new DatabaseEntry(iterator.getKey())) : null;
                    InsertRow row=new InsertRow(table,iterator.getRow(),id); row.marshall();
                    ByteBuffer key=ByteBuffer.wrap(iterator.getKey());
                    before.put(key,row); after.put(key,row); origins.put(key,key);
                }
            }
        }
        void change(ByteBuffer oldKey,InsertRow replacement) {
            ByteBuffer origin=origins.remove(oldKey); after.remove(oldKey);
            if(replacement!=null) {
                ByteBuffer key=key(replacement);
                if(after.putIfAbsent(key,replacement)!=null) { throw new IllegalArgumentException("Cascade would duplicate a row key"); }
                origins.put(key,origin);
            }
            List<Object[]> values=new ArrayList<>();
            for(InsertRow row:after.values()) { values.add(row.getValues()); }
            UniqueKeys.validate(table,values);
        }
        MutationBatch batch() {
            List<InsertRow> additions=new ArrayList<>();
            Map<ByteBuffer,InsertRow> byOrigin=new HashMap<>();
            for(Map.Entry<ByteBuffer,InsertRow> entry:after.entrySet()) {
                ByteBuffer origin=origins.get(entry.getKey());
                if(origin==null) { additions.add(entry.getValue()); } else { byOrigin.put(origin,entry.getValue()); }
            }
            Set<ByteBuffer> removed=new HashSet<>();
            Map<ByteBuffer,InsertRow> updates=new LinkedHashMap<>();
            for(Map.Entry<ByteBuffer,InsertRow> entry:before.entrySet()) {
                InsertRow row=byOrigin.get(entry.getKey());
                if(row!=entry.getValue()) {
                    removed.add(entry.getKey()); updates.put(entry.getKey(),row);
                    if(row!=null) { additions.add(row); }
                }
            }
            return new MutationBatch(table,additions,removed,updates,table.getName().equalsIgnoreCase(root.table.getName()) && root.dropping);
        }
    }
}
