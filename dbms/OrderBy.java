/** An ORDER BY expression and its direction. */
public class OrderBy {
    public CompareTerm expression;
    public final boolean descending;
    public OrderBy(CompareTerm expression, boolean descending) {
        this.expression = expression;
        this.descending = descending;
    }
}
