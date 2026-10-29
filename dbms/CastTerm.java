import java.math.BigInteger;
import java.util.*;
import java.util.regex.*;

/** Explicit conversion is separate from the engine's stored-column type checks. */
final class CastTerm extends CompositeTerm {
    private static final Pattern INTEGER_PREFIX = Pattern.compile("^[\\s]*([+-]?[0-9]+)");
    private final SqlType target;
    CastTerm(CompareTerm value, String type) {
        super(List.of(value));
        switch (type.toUpperCase(Locale.ROOT)) {
            case "INTEGER": case "INT": target = SqlType.INTEGER; break;
            case "REAL": target = SqlType.REAL; break;
            case "TEXT": case "CHAR": case "VARCHAR": target = SqlType.TEXT; break;
            case "NUMERIC": target = SqlType.NUMERIC; break;
            default: throw new IllegalArgumentException("Unsupported CAST type: " + type);
        }
    }
    public Object getValue() {
        Object value = arguments.get(0).getValue();
        if (value == null) { return null; }
        if (target == SqlType.TEXT) { return value.toString(); }
        if (target == SqlType.INTEGER) {
            if (value instanceof Number) { return ((Number)value).longValue(); }
            Matcher match = INTEGER_PREFIX.matcher(value.toString());
            if (!match.find()) { return 0L; }
            BigInteger integer = new BigInteger(match.group(1));
            return integer.max(BigInteger.valueOf(Long.MIN_VALUE)).min(BigInteger.valueOf(Long.MAX_VALUE)).longValue();
        }
        Number number = SqlValues.number(value);
        if (target == SqlType.REAL) { return number.doubleValue(); }
        if (value instanceof Number || !(number instanceof Double)) { return number; }
        double real = number.doubleValue();
        if (real == Math.rint(real) && real > -0x1.0p51 && real < 0x1.0p51) { return (long)real; }
        return real;
    }
    public SqlType getResultType() { return target; }
    public SqlType getAffinity() { return target; }
    boolean hasLiteralValue() { return arguments.get(0).hasLiteralValue(); }
    public String toString() { return "CAST(" + arguments.get(0) + " AS " + target + ")"; }
}
