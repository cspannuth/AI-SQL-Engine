import java.nio.ByteBuffer;
import java.util.*;
import com.sleepycat.je.*;

/** ALTER prepares replacement schemas and rows before making transactional changes. */
public final class AlterStatement extends SQLStatement {
    private final String oldColumn;
    private final String newName;
    private final SchemaDefinition addition;

    public AlterStatement(Table table, String oldColumn, String newName, SchemaDefinition addition) {
        super(table);
        this.oldColumn = oldColumn;
        this.newName = newName;
        this.addition = addition;
    }

    protected void executeInternal() {
        Table original = getTable(0);
        original.open();
        if (addition != null) { addColumn(original); return; }
        boolean renameTable = oldColumn == null;
        if (renameTable && (Catalog.findName(newName) != null || IndexCatalog.find(newName) != null)) {
            throw new IllegalArgumentException("Table already exists: " + newName);
        }
        if (!renameTable && !hasColumn(original, oldColumn)) {
            throw new IllegalArgumentException("Unknown column: " + oldColumn);
        }
        List<Table> replacements = new ArrayList<>();
        List<byte[]> metadata = new ArrayList<>();
        for (String name : Catalog.listTableNames()) {
            Table source = new Table(name);
            Catalog.getMetadata(source);
            boolean own = name.equalsIgnoreCase(original.getName());
            boolean dependent = source.getForeignKeys().stream().anyMatch(fk ->
                    fk.parentTable().equalsIgnoreCase(original.getName())
                    && (renameTable || fk.parentColumns().stream().anyMatch(column->column.equalsIgnoreCase(oldColumn))));
            if (!own && !dependent) { continue; }
            Table edited = copy(source, own && renameTable ? newName : name);
            if (own && !renameTable) {
                if (hasColumn(source, newName)) {
                    throw new IllegalArgumentException("Column already exists: " + newName);
                }
                edited = copyRenamedColumns(source, oldColumn, newName);
            }
            List<String> checks = new ArrayList<>();
            for (String check : source.getChecks()) {
                checks.add(own ? SqlNames.rename(check, renameTable ? source.getName() : oldColumn,
                        newName, renameTable) : check);
            }
            edited.setChecks(checks);
            List<SchemaConstraint> constraints = new ArrayList<>();
            for (SchemaConstraint constraint : source.getConstraints()) {
                constraints.add(own ? constraint.renamed(renameTable ? source.getName() : oldColumn,
                        newName, renameTable) : constraint);
            }
            edited.setConstraints(constraints);
            if(own && !renameTable) {
                edited.setPrimaryKeyColumns(renameNames(source.getPrimaryKeyColumns(),oldColumn,newName));
                List<List<String>> keys=new ArrayList<>();
                for(List<String> key:source.getUniqueKeys()) { keys.add(renameNames(key,oldColumn,newName)); }
                edited.setUniqueKeys(keys);
            }
            List<ForeignKey> references = new ArrayList<>();
            for (ForeignKey fk : source.getForeignKeys()) {
                boolean targets = fk.parentTable().equalsIgnoreCase(original.getName());
                references.add(fk.renamed(own && !renameTable ? renameNames(fk.columns(),oldColumn,newName) : fk.columns(),
                        targets && renameTable ? newName : fk.parentTable(),
                        targets && !renameTable ? renameNames(fk.parentColumns(),oldColumn,newName) : fk.parentColumns()));
            }
            edited.setForeignKeys(references);
            ConstraintValidator.validateDefinition(edited);
            replacements.add(edited);
            metadata.add(Catalog.encodeMetadata(edited));
        }
        if (renameTable) {
            Table.cacheClose();
            DBMS.getEnv().renameDatabase(DBMS.getTransaction(), original.dbName(), newName + ".tab.db");
            Catalog.removeMetadata(original);
        }
        for (int i = 0; i < replacements.size(); i++) {
            Catalog.replaceMetadata(replacements.get(i), metadata.get(i));
        }
        IndexCatalog.rename(original.getName(), renameTable ? newName : original.getName(), oldColumn, newName);
        result = QueryResult.changed("Altered table " + original + ".", 0);
    }

    private void addColumn(Table original) {
        Column definition = addition.columns.get(0);
        if (!definition.hasLiteralDefault()) {
            try (TableIterator iterator = new TableIterator(original)) {
                if (iterator.next()) { throw new IllegalArgumentException("Cannot add a non-constant default to existing rows"); }
            }
        }
        if (definition.getTable() != null) { throw new IllegalArgumentException("Added columns cannot be qualified"); }
        if (hasColumn(original, definition.getName())) {
            throw new IllegalArgumentException("Column already exists: " + definition.getName());
        }
        if (definition.isPrimaryKey() || definition.isUnique()) {
            throw new IllegalArgumentException("ADD COLUMN cannot add PRIMARY KEY or UNIQUE");
        }
        if (definition.hasLiteralDefault() && definition.isNotNull() && definition.evaluateDefault() == null) {
            throw new IllegalArgumentException("A NOT NULL added column needs a non-null default");
        }
        if (!addition.foreignKeys.isEmpty() && definition.evaluateDefault() != null) {
            throw new IllegalArgumentException("An added REFERENCES column must default to NULL");
        }
        Table edited = copy(original, original.getName());
        Column column = new Column(definition.getName(), edited);
        column.useColInfo(definition);
        if (column.getType() == Column.INTEGER) { column.setLength(8); }
        edited.addColumn(column);
        List<String> checks = new ArrayList<>(edited.getChecks());
        checks.addAll(addition.checks);
        edited.setChecks(checks);
        List<ForeignKey> references = new ArrayList<>(edited.getForeignKeys());
        references.addAll(addition.foreignKeys);
        edited.setForeignKeys(references);
        List<SchemaConstraint> constraints = new ArrayList<>(edited.getConstraints());
        constraints.addAll(addition.constraints);
        edited.setConstraints(constraints);
        edited.setDB(original.getDB());
        ConstraintValidator.validateDefinition(edited);
        byte[] metadata = Catalog.encodeMetadata(edited);
        List<InsertRow> records = new ArrayList<>();
        Set<ByteBuffer> keys = new HashSet<>();
        try (TableIterator iterator = new TableIterator(original)) {
            while (iterator.next()) {
                Object[] values = Arrays.copyOf(iterator.getRow(), edited.numColumns());
                values[column.getIndex()] = column.adjustValue(column.evaluateDefault());
                Long rowId = edited.usesRowIds() ? RowIdentity.value(new DatabaseEntry(iterator.getKey())) : null;
                InsertRow record = new InsertRow(edited, values, rowId);
                record.marshall();
                records.add(record);
                keys.add(ByteBuffer.wrap(iterator.getKey()));
            }
        }
        ConstraintValidator.validateChange(edited, records, keys);
        Catalog.replaceMetadata(edited, metadata);
        original.adoptColumns(edited);
        for (InsertRow record : records) {
            original.getDB().put(DBMS.getTransaction(), record.keyEntry(), record.valueEntry());
        }
        result = QueryResult.changed("Added column " + column.getName() + ".", 0);
    }

    private static Table copyRenamedColumns(Table source, String before, String after) {
        Table edited = copy(source, source.getName());
        edited.clearColumns();
        for (int i = 0; i < source.numColumns(); i++) {
            Column col = source.getColumn(i);
            Column copy = new Column(col.getName().equalsIgnoreCase(before) ? after : col.getName(), edited);
            copy.useColInfo(col);
            edited.addColumn(copy);
        }
        return edited;
    }

    static Table copy(Table source, String name) {
        Table copy = new Table(name);
        copy.setStorageVersion(6);
        copy.setRowFormat(source.getRowFormat());
        copy.setStringCharset(source.getStringCharset());
        copy.setRowIdColumn(source.getRowIdColumn());
        for (int i = 0; i < source.numColumns(); i++) {
            Column col = new Column(source.getColumn(i).getName(), copy);
            col.useColInfo(source.getColumn(i));
            copy.addColumn(col);
        }
        copy.setChecks(source.getChecks());
        copy.setForeignKeys(source.getForeignKeys());
        copy.setPrimaryKeyColumns(source.getPrimaryKeyColumns());
        copy.setUniqueKeys(source.getUniqueKeys());
        copy.setConstraints(source.getConstraints());
        return copy;
    }

    private static boolean hasColumn(Table table, String name) {
        for (int i = 0; i < table.numColumns(); i++) {
            if (table.getColumn(i).getName().equalsIgnoreCase(name)) { return true; }
        }
        return false;
    }
    private static List<String> renameNames(List<String> names,String oldName,String newName) {
        List<String> result=new ArrayList<>();
        for(String name:names) { result.add(name.equalsIgnoreCase(oldName) ? newName : name); }
        return result;
    }
}
