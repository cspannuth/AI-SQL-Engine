/** Logical result types. NUMERIC permits either integer or real values. */
public enum SqlType {
    INTEGER, REAL, NUMERIC, TEXT, NULL, UNKNOWN;

    public static SqlType of(Object value) {
        if (value == null) { return NULL; }
        if (value instanceof Float || value instanceof Double) { return REAL; }
        if (value instanceof Number) { return INTEGER; }
        if (value instanceof String) { return TEXT; }
        return UNKNOWN;
    }
}
