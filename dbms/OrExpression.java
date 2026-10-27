/** SQL OR preserves UNKNOWN unless either operand is true. */
public class OrExpression extends ConditionalExpression {
    public OrExpression(ConditionalExpression left, ConditionalExpression right) { super(left, right); }
    public String toString() { return "(" + getLeft() + " OR " + getRight() + ")"; }

    public Boolean evaluate() {
        Boolean left = getLeft().evaluate();
        if (Boolean.TRUE.equals(left)) { return true; }
        Boolean right = getRight().evaluate();
        if (Boolean.TRUE.equals(right)) { return true; }
        return left == null || right == null ? null : false;
    }
}
