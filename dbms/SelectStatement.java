import java.util.*;

/** SELECT using nested-loop joins, grouping, projection, sorting, and limits. */
public class SelectStatement extends SQLStatement {
    public static final String STAR = "*";
    private final ArrayList<Object> selectList;
    private final FromClause from;
    private final ArrayList<CompareTerm> groupBy;
    private final ConditionalExpression having;
    private final ArrayList<OrderBy> orderBy;
    private final Limit limit;
    private final boolean distinct;
    private boolean havingAliasesResolved;

    public SelectStatement(ArrayList<Object> selected, ArrayList<Table> tables,
            ConditionalExpression where, Limit limit, Boolean distinct) {
        this(selected, FromClause.cross(tables), where, new ArrayList<>(), null,
                new ArrayList<>(), limit, distinct);
    }

    public SelectStatement(ArrayList<Object> selected, FromClause from,
            ConditionalExpression where, ArrayList<CompareTerm> groupBy,
            ConditionalExpression having, ArrayList<OrderBy> orderBy, Limit limit, Boolean distinct) {
        super(from.tables, new ArrayList<>(), where);
        this.selectList = selected;
        this.from = from;
        this.groupBy = groupBy;
        this.having = having;
        this.orderBy = orderBy;
        this.limit = limit;
        this.distinct = distinct;
    }

    public boolean distinctSpecified() { return distinct; }

    protected void executeInternal() {
        Evaluation evaluation = new Evaluation();
        try {
            evaluation.prepare();
            evaluation.scan(0);
            result = evaluation.finish();
        } finally {
            for (TableIterator iterator : evaluation.iterators) { iterator.close(); }
        }
    }

    private class Evaluation {
        final List<TableIterator> iterators = new ArrayList<>();
        final List<SelectItem> output = new ArrayList<>();
        final List<AggregateTerm> aggregates = new ArrayList<>();
        final Map<List<Object>, Group> groups = new LinkedHashMap<>();
        final List<ResultRow> rows = new ArrayList<>();
        boolean grouped;
        int representativeAggregate = -1;

        void prepare() {
            Set<String> names = new HashSet<>();
            for (Table table : from.tables) {
                table.open();
                if (!names.add(table.getReferenceName().toLowerCase(Locale.ROOT))) {
                    throw new IllegalArgumentException("Duplicate table reference: " + table.getReferenceName());
                }
                iterators.add(new TableIterator(SelectStatement.this, table, false));
            }
            for (Object raw : selectList) {
                SelectItem item = raw instanceof SelectItem ? (SelectItem) raw
                        : raw == STAR ? SelectItem.star(null)
                        : new SelectItem(raw instanceof CompareTerm ? (CompareTerm) raw : new CompareTerm(raw), null);
                if (!item.wildcard) { output.add(item); continue; }
                int initialSize = output.size();
                for (TableIterator iterator : iterators) {
                    Table table = iterator.getTable();
                    if (item.qualifier != null && !item.qualifier.equalsIgnoreCase(table.getReferenceName())) { continue; }
                    for (int i = 0; i < iterator.numColumns(); i++) {
                        Column column = new Column(iterator.getColumn(i).getName(), new Table(table.getReferenceName()));
                        output.add(new SelectItem(column, null));
                    }
                }
                if (output.size() == initialSize) {
                    throw new IllegalArgumentException("Wildcard has no matching table");
                }
            }
            for (SelectItem item : output) { register(item.expression); }
            for (int i = 0; i < groupBy.size(); i++) {
                CompareTerm expression = resolveOutputReference(groupBy.get(i), true);
                if (!expression.getAggregates().isEmpty()) {
                    throw new IllegalArgumentException("Aggregates are not allowed in GROUP BY");
                }
                groupBy.set(i, expression);
                addExpression(expression);
            }
            for (OrderBy order : orderBy) {
                order.expression = resolveOutputReference(order.expression);
                register(order.expression);
            }
            if (having != null) {
                if (!havingAliasesResolved) {
                    for (Column reference : having.getColumns()) {
                        CompareTerm expression = resolveOutputReference(reference, true);
                        if (expression != reference) { reference.useExpression(expression); }
                    }
                    havingAliasesResolved = true;
                }
                for (Column column : having.getColumns()) { addColumn(column); }
                aggregates.addAll(having.getAggregates());
            }
            if (getWhere() != null && !getWhere().getAggregates().isEmpty()) {
                throw new IllegalArgumentException("Aggregates are not allowed in WHERE");
            }
            for (ConditionalExpression condition : from.conditions) {
                if (condition == null) { continue; }
                if (!condition.getAggregates().isEmpty()) {
                    throw new IllegalArgumentException("Aggregates are not allowed in JOIN conditions");
                }
                for (Column column : condition.getColumns()) { addColumn(column); }
            }
            bindColumns(iterators);
            for (int i = 0; i < from.conditions.size(); i++) {
                ConditionalExpression condition = from.conditions.get(i);
                if (condition == null) { continue; }
                for (Column column : condition.getColumns()) {
                    if (iterators.indexOf(column.getTableIterator()) > i) {
                        throw new IllegalArgumentException("JOIN references a table to its right: " + column);
                    }
                }
            }
            // The same expression may be referenced by both its SELECT alias and ORDER BY.
            List<AggregateTerm> unique = new ArrayList<>(new LinkedHashSet<>(aggregates));
            aggregates.clear();
            aggregates.addAll(unique);
            for (int i = 0; i < aggregates.size(); i++) {
                if (aggregates.get(i).isExtremum()) { representativeAggregate = i; break; }
            }
            grouped = !groupBy.isEmpty() || !aggregates.isEmpty();
            if (having != null && !grouped) {
                throw new IllegalArgumentException("HAVING requires grouping or an aggregate");
            }
            if (grouped && groupBy.isEmpty()) { groups.put(List.of(), new Group()); }
        }

        void register(CompareTerm expression) {
            addExpression(expression);
            aggregates.addAll(expression.getAggregates());
        }

        CompareTerm resolveOutputReference(CompareTerm expression) {
            return resolveOutputReference(expression, false);
        }

        CompareTerm resolveOutputReference(CompareTerm expression, boolean preferInput) {
            if ((expression.getClass() == CompareTerm.class || expression instanceof IntegerLiteral)
                    && expression.getValue() instanceof Long) {
                long ordinal = (Long) expression.getValue();
                if (ordinal < 1 || ordinal > output.size()) {
                    throw new IllegalArgumentException("Output column position is out of range: " + ordinal);
                }
                return output.get((int) ordinal - 1).expression;
            }
            if (expression instanceof Column && ((Column) expression).getTable() == null) {
                String name = ((Column) expression).getName();
                if (preferInput) {
                    for (TableIterator iterator : iterators) {
                        for (int i = 0; i < iterator.numColumns(); i++) {
                            if (iterator.getColumn(i).getName().equalsIgnoreCase(name)) { return expression; }
                        }
                    }
                }
                for (SelectItem item : output) {
                    if (item.alias != null && item.alias.equalsIgnoreCase(name)) { return item.expression; }
                }
            }
            return expression;
        }

        void scan(int depth) {
            if (enoughRows()) { return; }
            if (depth == iterators.size()) {
                if (getWhere() == null || getWhere().isTrue()) { consume(); }
                return;
            }
            TableIterator iterator = iterators.get(depth);
            iterator.rewind();
            boolean matched = false;
            ConditionalExpression on = from.conditions.get(depth);
            while (!enoughRows() && iterator.next()) {
                if (on == null || on.isTrue()) {
                    matched = true;
                    scan(depth + 1);
                }
            }
            if (!matched && from.leftJoins.get(depth)) {
                iterator.useSnapshot(new Object[iterator.numColumns()]);
                scan(depth + 1);
            }
        }

        boolean enoughRows() {
            if (limit == null) { return false; }
            if (limit.getMax() == 0) { return true; }
            // Sorting, grouping, and DISTINCT may still need every input row.
            return !grouped && !distinct && orderBy.isEmpty()
                    && rows.size() >= limit.getOffset()
                    && rows.size() - limit.getOffset() >= limit.getMax();
        }

        void consume() {
            if (!grouped) { emit(); return; }
            List<Object> key = new ArrayList<>();
            for (CompareTerm expression : groupBy) { key.add(SqlValues.key(expression.getValue())); }
            Group group = groups.computeIfAbsent(key, ignored -> new Group());
            for (AggregateTerm.State state : group.states) { state.addCurrentRow(); }
            if (group.representative == null || (representativeAggregate >= 0
                    && group.states.get(representativeAggregate).changedRepresentative())) {
                group.representative = new ArrayList<>();
                for (TableIterator iterator : iterators) { group.representative.add(iterator.getRow()); }
            }
        }

        void emit() {
            List<Object> values = new ArrayList<>();
            List<Object> sortKeys = new ArrayList<>();
            for (SelectItem item : output) { values.add(item.expression.getValue()); }
            for (OrderBy order : orderBy) { sortKeys.add(order.expression.getValue()); }
            rows.add(new ResultRow(values, sortKeys));
        }

        QueryResult finish() {
            if (grouped) {
                for (Group group : groups.values()) {
                    for (int i = 0; i < iterators.size(); i++) {
                        iterators.get(i).useSnapshot(group.representative == null
                                ? new Object[iterators.get(i).numColumns()] : group.representative.get(i));
                    }
                    for (int i = 0; i < aggregates.size(); i++) { aggregates.get(i).useState(group.states.get(i)); }
                    if (having == null || having.isTrue()) { emit(); }
                }
            }
            if (distinct) {
                Set<List<Object>> seen = new HashSet<>();
                rows.removeIf(row -> {
                    List<Object> key = new ArrayList<>();
                    for (Object value : row.values) { key.add(SqlValues.key(value)); }
                    return !seen.add(key);
                });
            }
            if (!orderBy.isEmpty()) {
                rows.sort((a, b) -> {
                    for (int i = 0; i < orderBy.size(); i++) {
                        int comparison = SqlValues.compare(a.sortKeys.get(i), b.sortKeys.get(i));
                        if (comparison != 0) { return orderBy.get(i).descending ? -comparison : comparison; }
                    }
                    return 0;
                });
            }
            int start = limit == null ? 0 : (int) Math.min(rows.size(), limit.getOffset());
            long count = limit == null ? rows.size() : Math.min(rows.size() - start, limit.getMax());
            int end = start + (int) count;
            List<List<Object>> values = new ArrayList<>();
            for (int i = start; i < end; i++) { values.add(rows.get(i).values); }
            List<String> labels = new ArrayList<>();
            for (SelectItem item : output) { labels.add(item.label()); }
            return new QueryResult(labels, values);
        }

        class Group {
            List<Object[]> representative;
            final List<AggregateTerm.State> states = new ArrayList<>();
            Group() {
                for (AggregateTerm aggregate : aggregates) { states.add(aggregate.newState()); }
            }
        }
    }

    private static class ResultRow {
        final List<Object> values;
        final List<Object> sortKeys;
        ResultRow(List<Object> values, List<Object> sortKeys) { this.values = values; this.sortKeys = sortKeys; }
    }
}
