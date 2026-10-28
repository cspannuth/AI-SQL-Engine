import java.math.BigDecimal;
import java.util.regex.Pattern;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** Shared SQL value comparison, ordering, and pattern semantics. */
public final class SqlValues {
    private static final Pattern NUMERIC_PREFIX = Pattern.compile(
            "^[\\s]*([+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?)");
    private SqlValues() { }

    public static int compare(Object left, Object right) {
        if (left == null) { return right == null ? 0 : -1; }
        if (right == null) { return 1; }
        if (left instanceof Number && right instanceof Number) {
            return new BigDecimal(left.toString()).compareTo(new BigDecimal(right.toString()));
        }
        if (left instanceof Number) { return -1; }
        if (right instanceof Number) { return 1; }
        return Arrays.compareUnsigned(left.toString().getBytes(StandardCharsets.UTF_8),
                right.toString().getBytes(StandardCharsets.UTF_8));
    }

    /** Normalize numeric keys so SQL DISTINCT and GROUP BY equate 1 and 1.0. */
    public static Object key(Object value) {
        return value instanceof Number ? new BigDecimal(value.toString()).stripTrailingZeros() : value;
    }

    /** SQLite arithmetic converts a leading numeric string, or uses zero. */
    public static Number number(Object value) {
        if (value instanceof Number) { return (Number) value; }
        java.util.regex.Matcher match = NUMERIC_PREFIX.matcher(value.toString());
        if (!match.find()) { return 0L; }
        String text = match.group(1);
        try { return Long.valueOf(text); }
        catch (NumberFormatException notInteger) { return Double.valueOf(text); }
    }

    /** Apply column affinity to comparison operands without changing stored values. */
    public static Object[] comparisonValues(CompareTerm left, CompareTerm right) {
        Object a = left.getValue();
        Object b = right.getValue();
        boolean leftNumeric = numericColumn(left);
        boolean rightNumeric = numericColumn(right);
        if (leftNumeric && !rightNumeric) { b = numericText(b); }
        else if (rightNumeric && !leftNumeric) { a = numericText(a); }
        else if (textColumn(left) && !(right instanceof Column) && b != null) { b = b.toString(); }
        else if (textColumn(right) && !(left instanceof Column) && a != null) { a = a.toString(); }
        return new Object[]{a, b};
    }

    private static boolean numericColumn(CompareTerm term) {
        if (term instanceof Column && ((Column) term).getExpression() != null) {
            return numericColumn(((Column) term).getExpression());
        }
        return term instanceof Column && ((Column) term).getType() <= Column.REAL;
    }

    private static boolean textColumn(CompareTerm term) {
        if (term instanceof Column && ((Column) term).getExpression() != null) {
            return textColumn(((Column) term).getExpression());
        }
        return term instanceof Column && ((Column) term).getType() >= Column.CHAR;
    }

    private static Object numericText(Object value) {
        if (!(value instanceof String)) { return value; }
        String text = ((String) value).trim();
        if (!NUMERIC_PREFIX.matcher(text).matches()) { return value; }
        Number number = number(text);
        return Double.isFinite(number.doubleValue()) ? number : value;
    }

    /** Foreign-key equality uses the parent's affinity, regardless of the child's type. */
    public static Object applyAffinity(Column parent, Object value) {
        return value == null ? null : parent.getType() <= Column.REAL ? numericText(value) : value.toString();
    }

    public static boolean like(String value, String pattern, boolean caseSensitive) {
        StringBuilder regex = new StringBuilder();
        for (int i = 0; i < pattern.length(); i++) {
            char c = pattern.charAt(i);
            if (c == '%') { regex.append(".*"); }
            else if (c == '_') { regex.append('.'); }
            else { regex.append(Pattern.quote(String.valueOf(c))); }
        }
        // SQLite's default LIKE folds ASCII letters only.
        int flags = Pattern.DOTALL | (caseSensitive ? 0 : Pattern.CASE_INSENSITIVE);
        return Pattern.compile(regex.toString(), flags).matcher(value).matches();
    }
}
