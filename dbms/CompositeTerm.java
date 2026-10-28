import java.util.*;

/** Shared reference/dependency traversal for scalar expressions with children. */
abstract class CompositeTerm extends CompareTerm {
    final List<CompareTerm> arguments;
    CompositeTerm(List<CompareTerm> arguments) { this.arguments = List.copyOf(arguments); }
    public List<Column> getColumns() {
        List<Column> result = new ArrayList<>();
        for (CompareTerm argument : arguments) { result.addAll(argument.getColumns()); }
        return result;
    }
    public List<AggregateTerm> getAggregates() {
        List<AggregateTerm> result = new ArrayList<>();
        for (CompareTerm argument : arguments) { result.addAll(argument.getAggregates()); }
        return result;
    }
    public List<QueryStatement> getSubqueries() { return CompareTerm.queries(arguments.toArray(new CompareTerm[0])); }
    static SqlType commonType(List<CompareTerm> expressions) {
        SqlType result = SqlType.NULL;
        for (CompareTerm expression : expressions) {
            SqlType type = expression.getResultType();
            result=SqlType.merge(result,type);
        }
        return result;
    }
}
