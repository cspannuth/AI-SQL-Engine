/** Predicate used when WHERE is absent. */
public class TrueExpression extends ConditionalExpression {
    public Boolean evaluate() { return true; }
}
