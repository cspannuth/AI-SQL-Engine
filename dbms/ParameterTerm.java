/** A positional value bound to an AST node; values never become SQL text. */
public class ParameterTerm extends CompareTerm {
    private final int index;
    private Object value;
    private boolean bound;

    public ParameterTerm(int index) { this.index = index; }
    public int getIndex() { return index; }

    public void bind(Object value) {
        this.value = normalize(value);
        bound = true;
    }

    public Object getValue() {
        if (!bound) { throw new IllegalArgumentException("Unbound parameter: " + (index + 1)); }
        return value;
    }

    public static Object normalize(Object value) {
        if (value == null || value instanceof String) { return value; }
        if (value instanceof Boolean) { return (Boolean) value ? 1L : 0L; }
        if (value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long) {
            return ((Number) value).longValue();
        }
        if (value instanceof Float || value instanceof Double) {
            double number = ((Number) value).doubleValue();
            if (Double.isFinite(number)) { return number; }
        }
        throw new IllegalArgumentException("Parameters must be null, strings, booleans, or finite numbers");
    }

    public String toString() { return "?"; }
}
