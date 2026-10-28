import java.util.*;

/** Collect CREATE TABLE definitions without putting parser state in persisted columns. */
public final class SchemaDefinition {
    final ArrayList<Column> columns = new ArrayList<>();
    final List<String> checks = new ArrayList<>();
    final List<ForeignKey> foreignKeys = new ArrayList<>();
    private boolean columnsFinished;
    final List<String> primaryColumns=new ArrayList<>();
    final List<List<String>> uniqueKeys=new ArrayList<>();

    public SchemaDefinition addColumn(Column column, ColumnOptions options) {
        columns.add(column);
        checks.addAll(options.checks);
        for (ForeignKey reference : options.references) {
            foreignKeys.add(reference.withColumns(List.of(column.getName())));
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
        primaryKey(List.of(name));
    }
    public void primaryKey(List<String> names) {
        for (Column column : columns) {
            if (column.isPrimaryKey()) { throw new IllegalArgumentException("More than one primary key"); }
        }
        if(new TreeSet<>(names).size()!=names.size()) { throw new IllegalArgumentException("Repeated primary-key column"); }
        for(String name:names) { column(name).applyOpts(new ColumnOptions(false,true)); }
        primaryColumns.addAll(names);
    }
    public void uniqueKey(List<String> names) {
        for(String name:names) { column(name); }
        if(names.size()==1) { column(names.get(0)).setUnique(true); } else { uniqueKeys.add(List.copyOf(names)); }
    }
}
