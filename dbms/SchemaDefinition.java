import java.util.*;

/** Collect CREATE TABLE definitions without putting parser state in persisted columns. */
public final class SchemaDefinition {
    final ArrayList<Column> columns = new ArrayList<>();
    final List<String> checks = new ArrayList<>();
    final List<ForeignKey> foreignKeys = new ArrayList<>();
    private boolean columnsFinished;

    public SchemaDefinition addColumn(Column column, ColumnOptions options) {
        columns.add(column);
        checks.addAll(options.checks);
        for (ForeignKey reference : options.references) {
            foreignKeys.add(new ForeignKey(column.getName(), reference.parentTable(), reference.parentColumn()));
        }
        return this;
    }

    public SchemaDefinition append(SchemaDefinition other) {
        if (columnsFinished) { throw new IllegalArgumentException("Columns must precede table constraints"); }
        columns.addAll(other.columns);
        checks.addAll(other.checks);
        foreignKeys.addAll(other.foreignKeys);
        return this;
    }

    public void finishColumns() { columnsFinished = true; }

    public Column column(String name) {
        for (Column column : columns) {
            if (column.getName().equalsIgnoreCase(name)) { return column; }
        }
        throw new IllegalArgumentException("Unknown constraint column: " + name);
    }

    public void primaryKey(String name) {
        for (Column column : columns) {
            if (column.isPrimaryKey()) { throw new IllegalArgumentException("More than one primary key"); }
        }
        column(name).applyOpts(new ColumnOptions(false, true));
    }
}
