import java.util.*;

/** A deliberately small planner: equality lookup or an ordinary table scan. */
final class QueryPlan {
    static void choose(TableIterator iterator, ConditionalExpression predicate,
            int depth, List<TableIterator> sources) {
        if (iterator.getTable() instanceof QueryTable) { return; }
        if (predicate instanceof AndExpression) {
            choose(iterator, predicate.getLeft(), depth, sources);
            choose(iterator, predicate.getRight(), depth, sources);
        }
        if (!(predicate instanceof Comparison)) { return; }
        Comparison comparison = (Comparison)predicate;
        if (comparison.getType() != Comparison.EQ) { return; }
        lookup(iterator, comparison.getLeftTerm(), comparison.getRightTerm(), depth, sources);
        lookup(iterator, comparison.getRightTerm(), comparison.getLeftTerm(), depth, sources);
    }

    private static void lookup(TableIterator iterator, CompareTerm key, CompareTerm value,
            int depth, List<TableIterator> sources) {
        if (!(key instanceof Column) || ((Column)key).getTableIterator() != iterator) { return; }
        if (((Column)key).getIndex() >= iterator.numColumns()) { return; }
        boolean literal = value.getClass() == CompareTerm.class || value instanceof IntegerLiteral || value instanceof ParameterTerm;
        boolean outer = value instanceof Column && sources.indexOf(((Column)value).getTableIterator()) >= 0
                && sources.indexOf(((Column)value).getTableIterator()) < depth;
        if (!literal && !outer) { return; }
        Column column = iterator.getTable().getColumn(((Column)key).getIndex());
        for (IndexDefinition index : IndexCatalog.forTable(iterator.getTable().getName())) {
            if (index.column().equalsIgnoreCase(column.getName())) {
                iterator.lookup(index, column, value);
                return;
            }
        }
    }
}
