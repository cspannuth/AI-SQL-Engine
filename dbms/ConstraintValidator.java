import java.nio.ByteBuffer;
import java.util.*;

/** Validate a statement's proposed rows before its first write. */
final class ConstraintValidator {
    private final Table changed;
    private final List<InsertRow> additions;
    private final Set<ByteBuffer> removed;
    private final boolean dropping;
    private final Map<String, List<Object[]>> rows = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);

    private ConstraintValidator(Table table, List<InsertRow> additions, Set<ByteBuffer> removed, boolean dropping) {
        changed = table;
        this.additions = additions;
        this.removed = removed;
        this.dropping = dropping;
    }

    static void validateDefinition(Table table) {
        for (String sql : table.getChecks()) { new CheckConstraint(sql, table, null); }
        for (ForeignKey reference : table.getForeignKeys()) { declaredColumn(table, reference.column()); }
        // Parent tables may be created later, as in SQLite. Resolve their keys at mutation time.
    }

    static void validateChange(Table table, List<InsertRow> additions, Set<ByteBuffer> removed) {
        new ConstraintValidator(table, additions, removed, false).validate();
    }

    static void validateDrop(Table table) {
        new ConstraintValidator(table, List.of(), Set.of(), true).validate();
    }

    private void validate() {
        if (!dropping) {
            validateUnique();
            validateChecks();
            for (ForeignKey reference : changed.getForeignKeys()) {
                List<Object[]> children = reference.parentTable().equalsIgnoreCase(changed.getName())
                        ? tableRows(changed) : proposedRows();
                validateReference(changed, reference, children);
            }
        }
        if (!removed.isEmpty() || dropping) {
            for (String name : Catalog.listTableNames()) {
                if (name.equalsIgnoreCase(changed.getName())) { continue; }
                Table child = new Table(name);
                Catalog.getMetadata(child);
                for (ForeignKey reference : child.getForeignKeys()) {
                    if (reference.parentTable().equalsIgnoreCase(changed.getName())) {
                        validateReference(child, reference, tableRows(child));
                    }
                }
            }
        }
    }

    private void validateUnique() {
        for (int i = 0; i < changed.numColumns(); i++) {
            Column column = changed.getColumn(i);
            boolean ordinaryPrimaryKey = changed.usesRowIds() && column.isPrimaryKey()
                    && i != changed.getRowIdColumn();
            if (!column.isUnique() && !ordinaryPrimaryKey) { continue; }
            Set<Object> seen = new HashSet<>();
            for (Object[] row : tableRows(changed)) {
                Object value = row[i];
                if (value != null && !seen.add(SqlValues.key(value))) {
                    throw new IllegalArgumentException("UNIQUE constraint failed: " + changed + "." + column.getName());
                }
            }
        }
    }

    private void validateChecks() {
        if (changed.getChecks().isEmpty() || additions.isEmpty()) { return; }
        try (TableIterator iterator = new TableIterator(changed)) {
            List<CheckConstraint> checks = new ArrayList<>();
            for (String sql : changed.getChecks()) { checks.add(new CheckConstraint(sql, changed, iterator)); }
            for (InsertRow row : additions) {
                Object[] snapshot = Arrays.copyOf(row.getValues(), changed.numColumns() + 1);
                if (changed.usesRowIds()) { snapshot[changed.numColumns()] = RowIdentity.value(row.keyEntry()); }
                iterator.useSnapshot(snapshot);
                for (CheckConstraint check : checks) { check.validate(); }
            }
        }
    }

    private void validateReference(Table child, ForeignKey reference, List<Object[]> childRows) {
        Column source = declaredColumn(child, reference.column());
        Table parent;
        if (reference.parentTable().equalsIgnoreCase(changed.getName())) { parent = changed; }
        else {
            parent = new Table(reference.parentTable());
            if (Catalog.getMetadata(parent) != com.sleepycat.je.OperationStatus.SUCCESS) {
                throw new IllegalArgumentException("Foreign-key parent table not found: " + reference.parentTable());
            }
        }
        Column target = reference.parentColumn() == null ? parent.primaryKeyColumn()
                : parent.resolveColumn(new Column(reference.parentColumn()));
        if (target == null || !(target.isPrimaryKey() || target.isUnique())) {
            // SQLite's implicit DELETE for DROP ignores malformed parent-key definitions.
            if (dropping) { return; }
            throw new IllegalArgumentException("Foreign-key parent must have a single-column PRIMARY KEY or UNIQUE constraint");
        }
        Set<Object> parentValues = new HashSet<>();
        for (Object[] row : tableRows(parent)) {
            if (row[target.getIndex()] != null) { parentValues.add(SqlValues.key(row[target.getIndex()])); }
        }
        for (Object[] row : childRows) {
            Object value = row[source.getIndex()];
            if (value != null && !parentValues.contains(SqlValues.key(SqlValues.applyAffinity(target, value)))) {
                throw new IllegalArgumentException("FOREIGN KEY constraint failed: " + child + "." + source.getName());
            }
        }
    }

    private List<Object[]> proposedRows() {
        List<Object[]> result = new ArrayList<>();
        for (InsertRow row : additions) { result.add(row.getValues()); }
        return result;
    }

    /** Only the changed table has an overlay; all other tables retain their stored rows. */
    private List<Object[]> tableRows(Table table) {
        List<Object[]> result = rows.get(table.getName());
        if (result != null) { return result; }
        result = new ArrayList<>();
        boolean isChanged = table.getName().equalsIgnoreCase(changed.getName());
        if (!(isChanged && dropping)) {
            table.open();
            try (TableIterator iterator = new TableIterator(table)) {
                while (iterator.next()) {
                    if (!isChanged || !removed.contains(ByteBuffer.wrap(iterator.getKey()))) {
                        result.add(iterator.getRow());
                    }
                }
            }
            if (isChanged) { result.addAll(proposedRows()); }
        }
        rows.put(table.getName(), result);
        return result;
    }

    private static Column declaredColumn(Table table, String name) {
        for (int i = 0; i < table.numColumns(); i++) {
            if (table.getColumn(i).getName().equalsIgnoreCase(name)) { return table.getColumn(i); }
        }
        throw new IllegalArgumentException("Unknown constraint column: " + table + "." + name);
    }
}
