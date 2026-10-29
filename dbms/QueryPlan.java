import java.util.*;

/** Equality access paths chosen from predicates whose inputs are already available. */
final class QueryPlan {
    record Lookup(KeyLookup.Path path, List<CompareTerm> values) { }

    static List<ConditionalExpression> conjuncts(ConditionalExpression predicate) {
        List<ConditionalExpression> terms = new ArrayList<>();
        if (predicate instanceof AndExpression) {
            terms.addAll(conjuncts(predicate.getLeft()));
            terms.addAll(conjuncts(predicate.getRight()));
        } else if (predicate != null) { terms.add(predicate); }
        return terms;
    }

    static Lookup lookup(TableIterator iterator, List<ConditionalExpression> predicates,
            Collection<TableIterator> bound, List<TableIterator> sources) {
        Map<Integer, CompareTerm> equalities = new LinkedHashMap<>();
        for (ConditionalExpression predicate : predicates) {
            if (!(predicate instanceof Comparison) || ((Comparison) predicate).getType() != Comparison.EQ) { continue; }
            Comparison comparison = (Comparison) predicate;
            equality(iterator, comparison.getLeftTerm(), comparison.getRightTerm(), bound, sources, equalities);
            equality(iterator, comparison.getRightTerm(), comparison.getLeftTerm(), bound, sources, equalities);
        }
        KeyLookup.Path path = KeyLookup.choose(iterator.getTable(), equalities.keySet());
        return path == null ? null : new Lookup(path, path.columns().stream().map(equalities::get).toList());
    }

    private static void equality(TableIterator iterator, CompareTerm key, CompareTerm value,
            Collection<TableIterator> bound, List<TableIterator> sources, Map<Integer, CompareTerm> equalities) {
        if (!(key instanceof Column) || ((Column) key).getTableIterator() != iterator
                || !value.getSubqueries().isEmpty()) { return; }
        for (Column column : value.getColumns()) {
            if (sources.contains(column.getTableIterator()) && !bound.contains(column.getTableIterator())) { return; }
        }
        // Numeric affinity can equate differently spelled text, which a text index cannot find.
        if (key.getAffinity() == SqlType.TEXT && (value.getAffinity() == SqlType.INTEGER
                || value.getAffinity() == SqlType.REAL || value.getAffinity() == SqlType.NUMERIC)) { return; }
        equalities.putIfAbsent(((Column) key).getIndex(), value);
    }

    static void choose(TableIterator iterator, ConditionalExpression predicate,
            int depth, List<TableIterator> sources) {
        Lookup lookup = lookup(iterator, conjuncts(predicate), sources.subList(0, depth), sources);
        if (lookup != null) { iterator.lookup(lookup.path(), lookup.values()); }
    }

    /** A small deterministic heuristic: key probes, local filters, then connected scans. */
    static List<TableIterator> order(List<TableIterator> sources, List<ConditionalExpression> predicates) {
        List<TableIterator> remaining = new ArrayList<>(sources);
        List<TableIterator> ordered = new ArrayList<>();
        while (!remaining.isEmpty()) {
            TableIterator best = remaining.get(0);
            int bestScore = Integer.MAX_VALUE;
            for (TableIterator iterator : remaining) {
                Lookup lookup = lookup(iterator, predicates, ordered, sources);
                int score = lookup == null ? 1000 : lookup.path().physical() || lookup.path().index().unique() ? 1 : 10;
                for (ConditionalExpression predicate : predicates) {
                    List<Column> columns = predicate.getColumns();
                    if (columns.isEmpty() || !predicate.getSubqueries().isEmpty()
                            || columns.stream().noneMatch(column -> column.getTableIterator() == iterator)) { continue; }
                    boolean local = columns.stream().allMatch(column -> column.getTableIterator() == iterator);
                    boolean ready = columns.stream().allMatch(column -> column.getTableIterator() == iterator
                            || ordered.contains(column.getTableIterator()) || !sources.contains(column.getTableIterator()));
                    if (local) { score = Math.min(score, 100); }
                    else if (ready) { score = Math.min(score, 200); }
                }
                if (score < bestScore) { best = iterator; bestScore = score; }
            }
            ordered.add(best);
            remaining.remove(best);
        }
        return ordered;
    }
}
