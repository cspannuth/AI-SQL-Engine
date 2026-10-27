/** LIMIT/OFFSET values are evaluated after positional parameters are bound. */
public class Limit {
    private final CompareTerm offset;
    private final CompareTerm max;

    public Limit(Number offset, Number max) {
        this(offset == null ? null : new CompareTerm(offset), max == null ? null : new CompareTerm(max));
    }

    public Limit(CompareTerm offset, CompareTerm max) {
        if (max == null) { throw new IllegalArgumentException("LIMIT requires a count"); }
        this.offset = offset;
        this.max = max;
    }

    public long getOffset() { return offset == null ? 0 : Math.max(0, integer(offset)); }
    public long getMax() {
        long count = integer(max);
        return count < 0 ? Long.MAX_VALUE : count;
    }

    private static long integer(CompareTerm expression) {
        Object value = expression.getValue();
        if (value instanceof Long || value instanceof Integer) { return ((Number) value).longValue(); }
        if (value instanceof Double) {
            double number = (Double) value;
            if (Double.isFinite(number) && number >= Long.MIN_VALUE && number < 0x1.0p63
                    && number == Math.rint(number)) { return (long) number; }
        }
        throw new IllegalArgumentException("LIMIT and OFFSET require integer values");
    }
}
