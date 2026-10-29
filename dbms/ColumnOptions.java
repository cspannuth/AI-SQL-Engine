import java.util.*;

/** Options collected while parsing one column definition. */
public class ColumnOptions {
    private boolean notNull;
    private boolean primaryKey;
    private boolean unique;
    private boolean hasDefault;
    private Object defaultValue;
    private String defaultSql;
    final List<String> checks = new ArrayList<>();
    final List<ForeignKey> references = new ArrayList<>();
    final List<SchemaConstraint> constraints = new ArrayList<>();

    public ColumnOptions(boolean notNull, boolean primaryKey) {
        this.notNull = notNull;
        this.primaryKey = primaryKey;
    }

    public ColumnOptions notNull() { notNull = true; return record("NOT NULL", null); }
    public ColumnOptions primaryKey() {
        if (primaryKey) { throw new IllegalArgumentException("More than one primary key"); }
        primaryKey = true;
        return record("PRIMARY KEY", null);
    }
    public ColumnOptions unique() { unique = true; return record("UNIQUE", null); }
    public ColumnOptions defaultValue(Object value) {
        hasDefault = true;
        defaultValue = value;
        defaultSql = null;
        String sql = value == null ? "NULL" : value instanceof String
                ? "'" + value.toString().replace("'", "''") + "'" : value.toString();
        return record("DEFAULT", sql);
    }
    public ColumnOptions defaultExpression(String sql) {
        hasDefault = true;
        defaultValue = null;
        defaultSql = sql;
        return record("DEFAULT", sql);
    }
    public ColumnOptions check(String sql) { checks.add(sql); return record("CHECK", sql); }
    public ColumnOptions references(String table, String column) {
        references.add(new ForeignKey(null, table, column));
        return record("FOREIGN KEY", null);
    }
    public ColumnOptions references(ForeignKey reference) {
        references.add(reference);
        return record("FOREIGN KEY", null);
    }
    private ColumnOptions record(String kind, String sql) {
        constraints.add(SchemaConstraint.column(kind, sql));
        return this;
    }
    public ColumnOptions named(String name) {
        int last = constraints.size() - 1;
        constraints.set(last, constraints.get(last).named(name));
        return this;
    }
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
    public String getDefaultSql() { return defaultSql; }
}
