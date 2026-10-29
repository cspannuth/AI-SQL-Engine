import java.nio.ByteBuffer;
import java.util.*;

/** Validate a statement's proposed rows before its first write. */
final class ConstraintValidator {
    private final Table changed;
    private final List<InsertRow> additions;
    private final Set<ByteBuffer> removed;
    private final boolean dropping;
    private final Map<String, List<Object[]>> rows = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    private boolean forceDeferred;
    private boolean rewriting;
    private static final ThreadLocal<Map<String,List<Object[]>>> OVERLAY=new ThreadLocal<>();
    static QueryContext.Scope overlay(Map<String,List<Object[]>> rows) {
        Map<String,List<Object[]>> before=OVERLAY.get(); OVERLAY.set(rows); return ()->OVERLAY.set(before);
    }

    private ConstraintValidator(Table table, List<InsertRow> additions, Set<ByteBuffer> removed, boolean dropping) {
        changed = table;
        this.additions = additions;
        this.removed = removed;
        this.dropping = dropping;
    }

    static void validateDefinition(Table table) {
        for (String sql : table.getChecks()) { new CheckConstraint(sql, table, null); }
        for (ForeignKey reference : table.getForeignKeys()) {
            ForeignKeys.children(table,reference);
            if(!reference.parentColumns().isEmpty() && reference.columns().size()!=reference.parentColumns().size()) {
                throw new IllegalArgumentException("Foreign-key column counts differ");
            }
        }
        for(List<String> key:table.getUniqueKeys()) { ForeignKeys.declared(table,key); }
        // Parent tables may be created later, as in SQLite. Resolve their keys at mutation time.
    }

    static void validateChange(Table table, List<InsertRow> additions, Set<ByteBuffer> removed) {
        new ConstraintValidator(table, additions, removed, false).validate();
    }
    static void validateRewrite(Table table, List<InsertRow> additions, Set<ByteBuffer> removed) {
        ConstraintValidator validator = new ConstraintValidator(table, additions, removed, false);
        validator.rewriting = true;
        validator.validate();
    }
    static void checkRows(Table table,List<InsertRow> rows) {
        new ConstraintValidator(table,rows,Set.of(),false).validateChecks();
    }
    static void validateDeferred() {
        for(String name:Catalog.listTableNames()) {
            Table table=new Table(name); Catalog.getMetadata(table);
            if(table.getForeignKeys().stream().noneMatch(ForeignKey::deferred)) { continue; }
            ConstraintValidator validator=new ConstraintValidator(table,List.of(),Set.of(),false);
            validator.forceDeferred=true;
            List<Object[]> rows=validator.tableRows(table);
            for(ForeignKey reference:table.getForeignKeys()) {
                if(!reference.deferred()) { continue; }
                List<Column> columns=ForeignKeys.children(table,reference);
                boolean populated=rows.stream().anyMatch(row->columns.stream().allMatch(column->row[column.getIndex()]!=null));
                if(populated) { validator.validateReference(table,reference,rows); }
            }
        }
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
        if (additions.isEmpty()) { return; }
        if (rewriting || OVERLAY.get() != null && OVERLAY.get().containsKey(changed.getName())) {
            UniqueKeys.validate(changed, tableRows(changed));
            return;
        }
        Set<Set<Integer>> checked = new HashSet<>();
        for (List<Integer> positions : UniqueKeys.keys(changed)) {
            if (!checked.add(new HashSet<>(positions))
                    || UniqueKeys.matches(positions, KeyLookup.physicalColumns(changed))) { continue; }
            if (KeyLookup.choose(changed, positions) == null) {
                UniqueKeys.validate(changed, tableRows(changed));
                return;
            }
            List<Column> columns = positions.stream().map(changed::getColumn).toList();
            Set<List<Object>> pending = new HashSet<>();
            for (InsertRow row : additions) {
                List<Object> key = ForeignKeys.parentKey(columns, row.getValues());
                if (key.contains(null)) { continue; }
                if (!pending.add(key) || KeyLookup.exists(changed, columns, key, removed)) {
                    throw new IllegalArgumentException("UNIQUE constraint failed: " + changed + " " + positions);
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
        List<Column> sources = ForeignKeys.children(child,reference);
        Table parent;
        if (reference.parentTable().equalsIgnoreCase(changed.getName())) { parent = changed; }
        else {
            parent = new Table(reference.parentTable());
            if (Catalog.getMetadata(parent) != com.sleepycat.je.OperationStatus.SUCCESS) {
                throw new IllegalArgumentException("Foreign-key parent table not found: " + reference.parentTable());
            }
        }
        List<Column> targets;
        try { targets=ForeignKeys.parents(parent,reference); }
        catch(IllegalArgumentException mismatch) { if(dropping) { return; } throw mismatch; }
        if(reference.deferred() && !forceDeferred) { return; }
        boolean overlaid = parent.getName().equalsIgnoreCase(changed.getName())
                || OVERLAY.get() != null && OVERLAY.get().containsKey(parent.getName());
        if (!overlaid && KeyLookup.choose(parent, targets.stream().map(Column::getIndex).toList()) != null) {
            Set<List<Object>> verified = new HashSet<>();
            for (Object[] row : childRows) {
                List<Object> key = ForeignKeys.childKey(targets, sources, row);
                if (!key.contains(null) && verified.add(key) && !KeyLookup.exists(parent, targets, key, Set.of())) {
                    throw new IllegalArgumentException("FOREIGN KEY constraint failed: " + child + " " + reference.columns());
                }
            }
            return;
        }
        Set<List<Object>> parentValues = new HashSet<>();
        for (Object[] row : tableRows(parent)) {
            List<Object> key=ForeignKeys.parentKey(targets,row);
            if(!key.contains(null)) { parentValues.add(key); }
        }
        for (Object[] row : childRows) {
            List<Object> key=ForeignKeys.childKey(targets,sources,row);
            if (!key.contains(null) && !parentValues.contains(key)) {
                throw new IllegalArgumentException("FOREIGN KEY constraint failed: " + child + " " + reference.columns());
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
        if(OVERLAY.get()!=null && OVERLAY.get().containsKey(table.getName())) { return OVERLAY.get().get(table.getName()); }
        List<Object[]> result = rows.get(table.getName());
        if (result != null) { return result; }
        result = new ArrayList<>();
        boolean isChanged = table.getName().equalsIgnoreCase(changed.getName());
        if (!(isChanged && dropping)) {
            if (table.getDB() == null) { table.open(); }
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

}
