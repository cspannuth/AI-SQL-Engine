import java.util.*;

/** Lexical query scopes. Nested reads share the statement transaction and budgets. */
final class QueryContext {
    interface Scope extends AutoCloseable { void close(); }
    private static final ThreadLocal<Deque<List<TableIterator>>> SOURCES = ThreadLocal.withInitial(ArrayDeque::new);
    private static final ThreadLocal<Deque<List<CteDefinition>>> CTES = ThreadLocal.withInitial(ArrayDeque::new);
    private static final ThreadLocal<Set<QueryStatement>> ACTIVE = ThreadLocal.withInitial(
            () -> Collections.newSetFromMap(new IdentityHashMap<>()));
    private static final ThreadLocal<Boolean> DESCRIBING = ThreadLocal.withInitial(() -> false);
    private static final ThreadLocal<java.time.Instant> NOW = new ThreadLocal<>();
    static java.time.Instant now() {
        if (NOW.get()==null) { NOW.set(java.time.Instant.now()); }
        return NOW.get();
    }
    static void clearClock() { NOW.remove(); }

    static Iterable<List<TableIterator>> outerSources() { return SOURCES.get(); }
    static Scope sources(List<TableIterator> iterators) {
        SOURCES.get().push(iterators);
        return () -> SOURCES.get().pop();
    }
    static Scope withoutSources() {
        Deque<List<TableIterator>> before=SOURCES.get();
        SOURCES.set(new ArrayDeque<>());
        return ()->SOURCES.set(before);
    }
    static Scope ctes(List<CteDefinition> definitions) {
        CTES.get().push(definitions);
        return () -> CTES.get().pop();
    }
    static Scope enter(QueryStatement query) {
        if (!ACTIVE.get().add(query)) { throw new IllegalArgumentException("Recursive queries are not supported"); }
        return () -> ACTIVE.get().remove(query);
    }
    static Scope describing() {
        boolean before = DESCRIBING.get();
        DESCRIBING.set(true);
        return () -> DESCRIBING.set(before);
    }
    static boolean isDescribing() { return DESCRIBING.get(); }
    static Table resolve(Table source) {
        if (source instanceof QueryTable) { return source; }
        for (List<CteDefinition> scope : CTES.get()) {
            for (CteDefinition definition : scope) {
                if (definition.name().equalsIgnoreCase(source.getName())) {
                    Table table = new QueryTable(definition.query(), definition.columns(), source.getName());
                    table.setRangeVariable(source.getRangeVar());
                    return table;
                }
            }
        }
        return source;
    }
}
