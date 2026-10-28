import java.util.*;

/** Prepare all RETURNING values before mutations, so projection errors remain atomic. */
final class Returning {
    static QueryResult evaluate(Table table,List<Object> requested,List<Object[]> snapshots,int affected,String message) {
        if(requested==null) { return QueryResult.changed(message,affected); }
        try(TableIterator iterator=new TableIterator(table)) {
            List<SelectItem> items=new ArrayList<>();
            for(Object raw:requested) {
                SelectItem item=(SelectItem)raw;
                if(!item.wildcard) { items.add(item); continue; }
                if(item.qualifier!=null) { throw new IllegalArgumentException("RETURNING does not support qualified wildcards"); }
                for(int i=0;i<table.numColumns();i++) { items.add(new SelectItem(new Column(table.getColumn(i).getName()),null)); }
            }
            List<String> labels=new ArrayList<>();
            List<SqlType> types=new ArrayList<>(), affinities=new ArrayList<>();
            for(SelectItem item:items) {
                RowExpressions.bind(item.expression,List.of(iterator),false);
                labels.add(item.label()); types.add(item.expression.getResultType()); affinities.add(item.expression.getAffinity());
            }
            List<List<Object>> rows=new ArrayList<>();
            for(Object[] snapshot:snapshots) {
                iterator.useSnapshot(snapshot);
                List<Object> row=new ArrayList<>();
                for(SelectItem item:items) { row.add(item.expression.getValue()); }
                QueryBudget.reserveWorkingRow(QueryBudget.payloadBytes(row));
                rows.add(row);
            }
            QueryResult header=new QueryResult(labels,types,List.of(),null,affinities);
            return QueryResults.finish(header,rows,List.of(),null).withAffectedRows(affected);
        }
    }
}
