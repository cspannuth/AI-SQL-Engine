import java.util.*;

/** Build INSERT values once, keeping omitted defaults distinct from explicit NULLs. */
final class InsertValues {
    record Input(Object[] values,Object requestedRowId) { }
    static Input evaluate(Table table,List<Column> targets,List<Object> input) {
        if(input.size()!=targets.size()) { throw new IllegalArgumentException("Value count does not match INSERT columns"); }
        Object[] values=new Object[table.numColumns()];
        Set<Integer> supplied = new HashSet<>();
        for (Column target : targets) { supplied.add(target.getIndex()); }
        for(int i=0;i<values.length;i++) {
            if(i!=table.getRowIdColumn() && !supplied.contains(i)) { values[i]=table.getColumn(i).evaluateDefault(); }
        }
        Object rowId=null;
        for(int i=0;i<input.size();i++) {
            Object value=input.get(i);
            if(value instanceof CompareTerm) {
                CompareTerm expression=(CompareTerm)value;
                if(!expression.getColumns().isEmpty() || !expression.getAggregates().isEmpty()) {
                    throw new IllegalArgumentException("VALUES expressions must be scalar");
                }
                value=expression.getValue();
            }
            int index=targets.get(i).getIndex();
            if(index==values.length) { rowId=value; } else { values[index]=value; }
        }
        return new Input(values,rowId);
    }
    static InsertRow encode(Table table,Input input,Long rowId) {
        Object[] values=input.values();
        if(table.getRowIdColumn()>=0) { values[table.getRowIdColumn()]=rowId; }
        for(int i=0;i<values.length;i++) { values[i]=table.getColumn(i).adjustValue(values[i]); }
        InsertRow row=new InsertRow(table,values,rowId);
        row.marshall();
        return row;
    }
    static Object[] snapshot(Table table,InsertRow row) {
        Object[] values=Arrays.copyOf(row.getValues(),table.numColumns()+1);
        if(table.usesRowIds()) { values[table.numColumns()]=RowIdentity.value(row.keyEntry()); }
        return values;
    }
}
