import java.math.BigInteger;

/** Preserve the full signed integer range, including the unary-minus boundary. */
public class IntegerLiteral extends CompareTerm {
    private final BigInteger integer;

    public IntegerLiteral(BigInteger integer) { this.integer = integer; }
    public IntegerLiteral negate() { return new IntegerLiteral(integer.negate()); }

    public Object getValue() {
        try { return integer.longValueExact(); }
        catch (ArithmeticException outsideIntegerRange) { return integer.doubleValue(); }
    }

    public String toString() { return integer.toString(); }
}
