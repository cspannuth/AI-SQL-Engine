import java.util.List;

/** Declaration metadata; enforcement remains in columns, checks, and foreign keys. */
public record SchemaConstraint(String name, String kind, List<String> columns,
        String expression, String ownerColumn) {
    public SchemaConstraint { columns = List.copyOf(columns); }

    static SchemaConstraint column(String kind, String expression) {
        return new SchemaConstraint(null, kind, List.of(), expression, null);
    }
    SchemaConstraint named(String value) { return new SchemaConstraint(value, kind, columns, expression, ownerColumn); }
    SchemaConstraint ownedBy(String column) {
        return new SchemaConstraint(name, kind, List.of(column), expression, column);
    }

    /** Constraint names are labels: renames affect identifiers in their definitions only. */
    SchemaConstraint renamed(String before, String after, boolean table) {
        List<String> edited = columns.stream()
                .map(column -> !table && column.equalsIgnoreCase(before) ? after : column).toList();
        String owner = !table && ownerColumn != null && ownerColumn.equalsIgnoreCase(before) ? after : ownerColumn;
        String sql = expression != null && kind.equals("CHECK")
                ? SqlNames.rename(expression, before, after, table) : expression;
        return new SchemaConstraint(name, kind, edited, sql, owner);
    }
}
