/** SQL AND preserves UNKNOWN unless either operand is false. */
public class AndExpression extends ConditionalExpression {
    public AndExpression(ConditionalExpression left, ConditionalExpression right) { super(left, right); }
    public String toString() { return "(" + getLeft() + " AND " + getRight() + ")"; }

    public Boolean evaluate() {
        Boolean left = getLeft().evaluate();
        if (Boolean.FALSE.equals(left)) { return false; }
        Boolean right = getRight().evaluate();
        if (Boolean.FALSE.equals(right)) { return false; }
        return left == null || right == null ? null : true;
    }
}
