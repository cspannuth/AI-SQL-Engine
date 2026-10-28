import java.util.*;

/** Resolve expressions against explicit row snapshots rather than executing SQL text. */
final class RowExpressions {
    static void bind(CompareTerm expression,List<TableIterator> sources,boolean allowQueries) {
        if(!expression.getAggregates().isEmpty() || !allowQueries && !expression.getSubqueries().isEmpty()) {
            throw new IllegalArgumentException("This row expression cannot contain aggregates or subqueries");
        }
        for(Column reference:expression.getColumns()) {
            Column match=null;
            TableIterator owner=null;
            for(TableIterator source:sources) {
                Column candidate=source.getTable().resolveColumn(reference);
                if(candidate!=null) {
                    if(match!=null) { throw new IllegalArgumentException("Ambiguous column: "+reference); }
                    match=candidate; owner=source;
                }
            }
            if(match==null) { throw new IllegalArgumentException("Unknown column: "+reference); }
            reference.useColInfo(match); reference.setTableIterator(owner);
        }
        try(QueryContext.Scope scope=QueryContext.sources(sources)) {
            for(QueryStatement query:expression.getSubqueries()) { query.describeChecked(); }
        }
    }
}
