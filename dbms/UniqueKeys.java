import java.util.*;

/** Shared uniqueness rules for validation and conflict-target matching. */
final class UniqueKeys {
    static List<List<Integer>> keys(Table table) {
        List<List<Integer>> result = new ArrayList<>();
        List<Integer> primary = indexes(table,table.getPrimaryKeyColumns());
        for (int i=0;i<table.numColumns();i++) {
            Column column=table.getColumn(i);
            if (column.isUnique()) { result.add(List.of(i)); }
        }
        if (!primary.isEmpty()) { result.add(primary); }
        for (List<String> columns : table.getUniqueKeys()) { result.add(indexes(table,columns)); }
        for (IndexDefinition index : IndexCatalog.forTable(table.getName())) {
            if(index.unique()) { result.add(indexes(table,index.columns())); }
        }
        return result;
    }
    static List<Integer> indexes(Table table,List<String> names) {
        List<Integer> result=new ArrayList<>();
        for (String name:names) {
            Column column=table.resolveColumn(new Column(name));
            if(column==null) { throw new IllegalArgumentException("Unknown key column: "+name); }
            result.add(column.getIndex());
        }
        return result;
    }
    static boolean matches(List<Integer> a,List<Integer> b) {
        return a.size()==b.size() && new HashSet<>(a).equals(new HashSet<>(b));
    }
    static void validate(Table table,Iterable<Object[]> rows) {
        for (List<Integer> key:keys(table)) {
            Set<List<Object>> seen=new HashSet<>();
            for (Object[] row:rows) {
                List<Object> values=new ArrayList<>();
                for (int column:key) { values.add(SqlValues.key(row[column])); }
                if (!values.contains(null) && !seen.add(values)) {
                    throw new IllegalArgumentException("UNIQUE constraint failed: "+table+" "+key);
                }
            }
        }
    }
}
