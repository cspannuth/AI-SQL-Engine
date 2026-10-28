import java.util.*;

/** Interpret a scalar in WHERE, ON, HAVING, AND, OR, or NOT. */
public class TruthExpression extends ConditionalExpression {
    public List<QueryStatement> getSubqueries() { return expression.getSubqueries(); }
    private final CompareTerm expression;

    public TruthExpression(CompareTerm expression) { this.expression = expression; }
    public String toString() { return expression.toString(); }

    public Boolean evaluate() {
        Object value = expression.getValue();
        if (value == null) { return null; }
        return SqlValues.number(value).doubleValue() != 0;
    }

    public List<Column> getColumns() { return expression.getColumns(); }
    public List<AggregateTerm> getAggregates() { return expression.getAggregates(); }
}
