import java.util.*;

/** SELECT using nested-loop joins, grouping, projection, sorting, and limits. */
public class SelectStatement extends QueryStatement {
    public static final String STAR = "*";
    private final ArrayList<Object> selectList;
    private final FromClause from;
    private final ArrayList<CompareTerm> groupBy;
    private final ConditionalExpression having;
    private final ArrayList<OrderBy> orderBy;
    private Limit limit;
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
    QueryStatement tail(List<OrderBy> order, Limit limit) {
        orderBy.clear(); orderBy.addAll(order); this.limit = limit; return this;
    }
    QueryResult describe() {
        Evaluation evaluation = new Evaluation();
        try (QueryContext.Scope mode = QueryContext.describing()) {
            evaluation.prepare();
            List<String> labels = new ArrayList<>();
            List<SqlType> types = new ArrayList<>(), affinities = new ArrayList<>();
            for (SelectItem item : evaluation.output) {
                labels.add(item.label()); types.add(item.expression.getResultType()); affinities.add(item.expression.getAffinity());
            }
            return new QueryResult(labels,types,List.of(),null,affinities);
        } finally { for (TableIterator iterator : evaluation.iterators) { iterator.close(); } }
    }
    void collectTables(Set<String> tables, Set<String> cteNames) {
        for (Table table : from.tables) {
            if (table instanceof QueryTable) { ((QueryTable)table).query.collectTables(tables,cteNames); }
            else if (!cteNames.contains(table.getName())) { tables.add(table.getName()); }
        }
        for (CompareTerm expression : expressions()) {
            for (QueryStatement query : expression.getSubqueries()) { query.collectTables(tables,cteNames); }
        }
    }
    private List<CompareTerm> expressions() {
        List<CompareTerm> result = new ArrayList<>();
        for (Object item : selectList) { if (item instanceof SelectItem && !((SelectItem)item).wildcard) { result.add(((SelectItem)item).expression); } }
        if (getWhere() != null) { result.add(getWhere()); }
        if (having != null) { result.add(having); }
        for (ConditionalExpression on : from.conditions) { if (on != null) { result.add(on); } }
        result.addAll(groupBy);
        for (OrderBy order : orderBy) { result.add(order.expression); }
        return result;
    }

    /** Used to compile a persisted CHECK expression without executing a query. */
    CompareTerm singleExpression() { return ((SelectItem) selectList.get(0)).expression; }

    QueryResult explain() {
        Evaluation evaluation = new Evaluation();
        try (QueryContext.Scope mode = QueryContext.describing()) {
            evaluation.prepare();
            List<List<Object>> rows = new ArrayList<>();
            for (int i = 0; i < evaluation.iterators.size(); i++) {
                rows.add(List.of((long)i,0L,0L,evaluation.iterators.get(i).accessDescription()));
            }
            return new QueryResult(List.of("id","parent","notused","detail"), rows);
        } finally { for (TableIterator iterator : evaluation.iterators) { iterator.close(); } }
    }

    protected void executeInternal() {
        Evaluation evaluation = new Evaluation();
        try {
            evaluation.prepare();
            try (QueryContext.Scope scope = QueryContext.sources(evaluation.iterators)) {
                evaluation.scan(0);
                result = evaluation.finish();
            }
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
        final List<List<ConditionalExpression>> filters = new ArrayList<>();
        final List<ConditionalExpression> finalFilters = new ArrayList<>();
        boolean plannedInnerJoins;
        boolean grouped;
        int representativeAggregate = -1;

        void prepare() {
            clearColumnReferences();
            QueryBudget.check();
            Set<String> names = new HashSet<>();
            for (Table source : from.tables) {
                Table table = QueryContext.resolve(source);
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
            try (QueryContext.Scope scope = QueryContext.sources(iterators)) {
                for (CompareTerm expression : expressions()) {
                    for (QueryStatement query : expression.getSubqueries()) { query.describeChecked(); }
                }
            }
            for (int i = 0; i < from.conditions.size(); i++) {
                ConditionalExpression condition = from.conditions.get(i);
                if (condition == null) { continue; }
                for (Column column : condition.getColumns()) {
                    if (iterators.indexOf(column.getTableIterator()) > i) {
                        throw new IllegalArgumentException("JOIN references a table to its right: " + column);
                    }
                }
            }
            plan();
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
            if (grouped && groupBy.isEmpty()) {
                QueryBudget.reserveWorkingRow(8L * aggregates.size());
                groups.put(List.of(), new Group());
            }
        }

        private void plan() {
            List<ConditionalExpression> predicates = new ArrayList<>(QueryPlan.conjuncts(getWhere()));
            for (ConditionalExpression condition : from.conditions) { predicates.addAll(QueryPlan.conjuncts(condition)); }
            // Keep outer joins and predicates containing nested queries in their original scopes.
            plannedInnerJoins = !from.leftJoins.contains(true)
                    && predicates.stream().allMatch(predicate -> predicate.getSubqueries().isEmpty());
            if (plannedInnerJoins && from.reorderable) {
                List<TableIterator> order = QueryPlan.order(iterators, predicates);
                iterators.clear(); iterators.addAll(order);
            }
            for (int i = 0; i < iterators.size(); i++) { filters.add(new ArrayList<>()); }
            if (plannedInnerJoins) {
                for (ConditionalExpression predicate : predicates) {
                    int depth = 0;
                    for (Column column : predicate.getColumns()) {
                        depth = Math.max(depth, iterators.indexOf(column.getTableIterator()));
                    }
                    if (iterators.isEmpty()) { finalFilters.add(predicate); }
                    else { filters.get(depth).add(predicate); }
                }
            } else if (getWhere() != null) { finalFilters.add(getWhere()); }
            for (int i = 0; i < iterators.size(); i++) {
                List<ConditionalExpression> available = plannedInnerJoins ? predicates
                        : new ArrayList<>(QueryPlan.conjuncts(getWhere()));
                if (!plannedInnerJoins) { available.addAll(QueryPlan.conjuncts(from.conditions.get(i))); }
                QueryPlan.Lookup lookup = QueryPlan.lookup(iterators.get(i), available, iterators.subList(0, i), iterators);
                if (lookup != null) { iterators.get(i).lookup(lookup.path(), lookup.values()); }
            }
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
                        if (iterator.getTable().resolveColumn((Column) expression) != null) { return expression; }
                    }
                }
                for (SelectItem item : output) {
                    if (item.alias != null && item.alias.equalsIgnoreCase(name)) { return item.expression; }
                }
            }
            return expression;
        }

        void scan(int depth) {
            QueryBudget.check();
            if (enoughRows()) { return; }
            if (depth == iterators.size()) {
                if (finalFilters.stream().allMatch(ConditionalExpression::isTrue)) { consume(); }
                return;
            }
            TableIterator iterator = iterators.get(depth);
            iterator.rewind();
            boolean matched = false;
            ConditionalExpression on = plannedInnerJoins ? null : from.conditions.get(depth);
            while (!enoughRows() && iterator.next()) {
                if (on == null || on.isTrue()) {
                    matched = true;
                    if (filters.get(depth).stream().allMatch(ConditionalExpression::isTrue)) { scan(depth + 1); }
                }
            }
            if (!plannedInnerJoins && !matched && from.leftJoins.get(depth)) {
                iterator.useSnapshot(new Object[iterator.numColumns() + 1]);
                scan(depth + 1);
            }
        }

        boolean enoughRows() {
            if (limit != null && limit.getMax() == 0) { return true; }
            long rowsNeeded = QueryBudget.resultRowLimit();
            // One extra row proves whether an adapter preview is truncated.
            if (rowsNeeded < Long.MAX_VALUE) { rowsNeeded++; }
            if (limit != null) { rowsNeeded = Math.min(rowsNeeded, limit.getMax()); }
            long offset = limit == null ? 0 : limit.getOffset();
            // Sorting, grouping, and DISTINCT may still need every input row.
            return !grouped && !distinct && orderBy.isEmpty()
                    && rows.size() >= offset && rows.size() - offset >= rowsNeeded;
        }

        void consume() {
            if (!grouped) { emit(); return; }
            List<Object> key = new ArrayList<>();
            for (CompareTerm expression : groupBy) { key.add(SqlValues.key(expression.getValue())); }
            Group group = groups.get(key);
            if (group == null) {
                QueryBudget.reserveWorkingRow(QueryBudget.payloadBytes(key) + 8L * aggregates.size());
                group = new Group();
                groups.put(key, group);
            }
            for (AggregateTerm.State state : group.states) { state.addCurrentRow(); }
            if (group.representative == null || (representativeAggregate >= 0
                    && group.states.get(representativeAggregate).changedRepresentative())) {
                group.representative = new ArrayList<>();
                for (TableIterator iterator : iterators) {
                    Object[] row = iterator.getSnapshot();
                    QueryBudget.reserveBytes(QueryBudget.payloadBytes(Arrays.asList(row)));
                    group.representative.add(row);
                }
            }
        }

        void emit() {
            QueryBudget.check();
            List<Object> values = new ArrayList<>();
            List<Object> sortKeys = new ArrayList<>();
            for (SelectItem item : output) { values.add(item.expression.getValue()); }
            for (OrderBy order : orderBy) { sortKeys.add(order.expression.getValue()); }
            QueryBudget.reserveWorkingRow(QueryBudget.payloadBytes(values) + QueryBudget.payloadBytes(sortKeys));
            rows.add(new ResultRow(values, sortKeys));
        }

        QueryResult finish() {
            if (grouped) {
                for (Group group : groups.values()) {
                    for (int i = 0; i < iterators.size(); i++) {
                        iterators.get(i).useSnapshot(group.representative == null
                                ? new Object[iterators.get(i).numColumns() + 1] : group.representative.get(i));
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
                    boolean duplicate = !seen.add(key);
                    if (!duplicate) { QueryBudget.reserveBytes(QueryBudget.payloadBytes(key)); }
                    return duplicate;
                });
            }
            if (!orderBy.isEmpty()) {
                rows.sort((a, b) -> {
                    QueryBudget.check();
                    for (int i = 0; i < orderBy.size(); i++) {
                        int comparison = SqlValues.compare(a.sortKeys.get(i), b.sortKeys.get(i));
                        if (comparison != 0) { return orderBy.get(i).descending ? -comparison : comparison; }
                    }
                    return 0;
                });
            }
            int start = limit == null ? 0 : (int) Math.min(rows.size(), limit.getOffset());
            long count = limit == null ? rows.size() : Math.min(rows.size() - start, limit.getMax());
            List<String> labels = new ArrayList<>();
            List<SqlType> types = new ArrayList<>();
            List<SqlType> affinities = new ArrayList<>();
            for (SelectItem item : output) {
                labels.add(item.label());
                types.add(item.expression.getResultType());
                affinities.add(item.expression.getAffinity());
            }
            int end = start + (int) Math.min(count, QueryBudget.resultRowLimit());
            String truncated = count > QueryBudget.resultRowLimit() ? "ROWS" : null;
            long resultBytes = 8 + QueryBudget.payloadBytes(labels) + QueryBudget.payloadBytes(types);
            if (resultBytes > QueryBudget.resultByteLimit()) { throw new QueryBudget.Exceeded("result header bytes"); }
            List<List<Object>> values = new ArrayList<>();
            for (int i = start; i < end; i++) {
                QueryBudget.check();
                List<Object> row = rows.get(i).values;
                long bytes = QueryBudget.payloadBytes(row);
                if (bytes > QueryBudget.resultByteLimit() - resultBytes) { truncated = "BYTES"; break; }
                resultBytes += bytes;
                values.add(row);
            }
            return new QueryResult(labels, types, values, truncated, affinities);
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
