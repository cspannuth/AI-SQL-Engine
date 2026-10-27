/** Negating UNKNOWN still produces UNKNOWN. */
public class NotExpression extends ConditionalExpression {
    public NotExpression(ConditionalExpression expression) { super(expression, null); }
    public String toString() { return "NOT (" + getLeft() + ")"; }
    public Boolean evaluate() {
        Boolean value = getLeft().evaluate();
        return value == null ? null : !value;
    }
}
