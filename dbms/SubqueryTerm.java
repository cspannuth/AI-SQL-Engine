import java.util.*;

/** Scalar SELECT returns its first value; EXISTS returns whether any row exists. */
final class SubqueryTerm extends CompareTerm {
    private final QueryStatement query;
    private final boolean exists;
    private SqlType type = SqlType.UNKNOWN;
    private SqlType affinity = SqlType.UNKNOWN;
    SubqueryTerm(QueryStatement query, boolean exists) {
        this.query = exists ? query : query.expectColumns(1);
        this.exists = exists;
    }
    public Object getValue() {
        QueryResult result = query.nested();
        if (exists) { return result.getRows().isEmpty() ? 0L : 1L; }
        if (result.getColumns().size() != 1) { throw new IllegalArgumentException("Scalar subquery must return one column"); }
        type = result.getColumnTypes().get(0);
        affinity = result.getAffinities().get(0);
        return result.getRows().isEmpty() ? null : result.getRows().get(0).get(0);
    }
    public List<QueryStatement> getSubqueries() { return List.of(query); }
    public SqlType getResultType() {
        return exists ? SqlType.INTEGER : query.description() != null ? query.description().getColumnTypes().get(0) : type;
    }
    public SqlType getAffinity() {
        return exists ? SqlType.UNKNOWN : query.description() != null ? query.description().getAffinities().get(0) : affinity;
    }
    public String toString() { return exists ? "EXISTS(subquery)" : "(subquery)"; }
}
