import java.util.*;

/** Resolve and compare composite references with the parent's affinities. */
final class ForeignKeys {
    static List<Column> children(Table table,ForeignKey reference) { return declared(table,reference.columns()); }
    static List<Column> parents(Table table,ForeignKey reference) {
        List<String> names=reference.parentColumns().isEmpty() ? table.getPrimaryKeyColumns() : reference.parentColumns();
        if(names.isEmpty() || names.size()!=reference.columns().size()) { throw new IllegalArgumentException("Foreign-key arity mismatch"); }
        List<Column> columns=declared(table,names);
        List<Integer> indexes=new ArrayList<>();
        for(Column column:columns) { indexes.add(column.getIndex()); }
        if(UniqueKeys.keys(table).stream().noneMatch(key->UniqueKeys.matches(key,indexes))) {
            throw new IllegalArgumentException("Foreign-key parent is not a PRIMARY KEY or UNIQUE key");
        }
        return columns;
    }
    static List<Column> declared(Table table,List<String> names) {
        List<Column> result=new ArrayList<>();
        for(String name:names) {
            Column found=null;
            for(int i=0;i<table.numColumns();i++) {
                if(table.getColumn(i).getName().equalsIgnoreCase(name)) { found=table.getColumn(i); break; }
            }
            if(found==null) { throw new IllegalArgumentException("Unknown key column: "+table+"."+name); }
            result.add(found);
        }
        return result;
    }
    static List<Object> parentKey(List<Column> columns,Object[] row) {
        List<Object> result=new ArrayList<>();
        for(Column column:columns) { result.add(SqlValues.key(row[column.getIndex()])); }
        return result;
    }
    static List<Object> childKey(List<Column> parents,List<Column> children,Object[] row) {
        List<Object> result=new ArrayList<>();
        for(int i=0;i<parents.size();i++) { result.add(SqlValues.key(SqlValues.applyAffinity(parents.get(i),row[children.get(i).getIndex()]))); }
        return result;
    }
}
