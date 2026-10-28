import java.util.*;

/** Non-recursive WITH scope; CTE bodies remain read queries. */
final class WithQuery extends QueryStatement {
    private final List<CteDefinition> definitions;
    private final QueryStatement body;
    WithQuery(List<CteDefinition> definitions, QueryStatement body) {
        this.definitions = List.copyOf(definitions);
        this.body = body;
        Set<String> names = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        for (CteDefinition definition : definitions) {
            if (!names.add(definition.name())) { throw new IllegalArgumentException("Duplicate CTE: " + definition.name()); }
        }
    }
    protected void executeInternal() {
        try (QueryContext.Scope scope = QueryContext.ctes(definitions)) { body.executeInternal(); result = body.getResult(); }
    }
    QueryResult describe() {
        try (QueryContext.Scope scope = QueryContext.ctes(definitions)) { return body.describe(); }
    }
    QueryStatement tail(List<OrderBy> order, Limit limit) { body.tail(order, limit); return this; }
    void collectTables(Set<String> tables, Set<String> inherited) {
        Set<String> names = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        names.addAll(inherited);
        for (CteDefinition definition : definitions) { names.add(definition.name()); }
        for (CteDefinition definition : definitions) { definition.query().collectTables(tables, names); }
        body.collectTables(tables, names);
    }
    QueryResult explain() {
        try (QueryContext.Scope scope = QueryContext.ctes(definitions)) { return body.explain(); }
    }
}
