import java.util.*;

/** Options collected while parsing one column definition. */
public class ColumnOptions {
    private boolean notNull;
    private boolean primaryKey;
    private boolean unique;
    private boolean hasDefault;
    private Object defaultValue;
    final List<String> checks = new ArrayList<>();
    final List<ForeignKey> references = new ArrayList<>();

    public ColumnOptions(boolean notNull, boolean primaryKey) {
        this.notNull = notNull;
        this.primaryKey = primaryKey;
    }

    public ColumnOptions notNull() { notNull = true; return this; }
    public ColumnOptions primaryKey() {
        if (primaryKey) { throw new IllegalArgumentException("More than one primary key"); }
        primaryKey = true;
        return this;
    }
    public ColumnOptions unique() { unique = true; return this; }
    public ColumnOptions defaultValue(Object value) {
        hasDefault = true;
        defaultValue = value;
        return this;
    }
    public ColumnOptions check(String sql) { checks.add(sql); return this; }
    public ColumnOptions references(String table, String column) {
        references.add(new ForeignKey(null, table, column));
        return this;
    }
    public ColumnOptions references(ForeignKey reference) { references.add(reference); return this; }
    public ColumnOptions deferred(boolean value) {
        if(references.isEmpty()) { throw new IllegalArgumentException("DEFERRABLE requires REFERENCES"); }
        int last=references.size()-1;
        references.set(last,references.get(last).withDeferred(value));
        return this;
    }

    public boolean isNotNull() { return notNull; }
    public boolean isPrimaryKey() { return primaryKey; }
    public boolean isUnique() { return unique; }
    public boolean hasDefault() { return hasDefault; }
    public Object getDefaultValue() { return defaultValue; }
}
