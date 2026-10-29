import java.io.*;
import java.nio.charset.*;
import java.util.*;
import com.sleepycat.je.*;

/** Persisted schemas. Row and catalog changes use the same transaction. */
public class Catalog {
    public static final String CATALOG_NAME = "catalog.db";
    private static Database catalogDB;

    public static void open() {
        DatabaseConfig config = new DatabaseConfig();
        config.setAllowCreate(true);
        config.setTransactional(true);
        catalogDB = DBMS.getEnv().openDatabase(DBMS.getTransaction(), CATALOG_NAME, config);
        IndexCatalog.open();
    }

    public static void close() {
        IndexCatalog.close();
        if (catalogDB != null) {
            Database closing = catalogDB;
            catalogDB = null;
            closing.close();
        }
    }

    /** Resolve identifiers without case sensitivity, including legacy names. */
    public static String findName(String requested) {
        for (String name : listTableNames()) {
            if (name.equalsIgnoreCase(requested)) { return name; }
        }
        return null;
    }

    public static java.util.List<String> listTableNames() {
        java.util.List<String> names = new ArrayList<>();
        DatabaseEntry key = new DatabaseEntry();
        DatabaseEntry value = new DatabaseEntry();
        try (Cursor cursor = catalogDB.openCursor(DBMS.getTransaction(), null)) {
            while (cursor.getNext(key, value, null) == OperationStatus.SUCCESS) {
                QueryBudget.check();
                String name = new String(key.getData(), key.getOffset(), key.getSize(), StandardCharsets.UTF_8);
                QueryBudget.reserveBytes(QueryBudget.payloadBytes(name));
                names.add(name);
            }
        }
        names.sort(String.CASE_INSENSITIVE_ORDER);
        return names;
    }

    public static OperationStatus putMetadata(Table table) {
        if (findName(table.getName()) != null) { return OperationStatus.KEYEXIST; }
        return catalogDB.putNoOverwrite(DBMS.getTransaction(), key(table.getName()),
                new DatabaseEntry(encodeMetadata(table)));
    }

    /** Encode first, so callers can validate all schemas before changing catalog entries. */
    static byte[] encodeMetadata(Table table) {
        if (table.numColumns() == 0 || table.numColumns() > 255) {
            throw new IllegalArgumentException("A table must contain between 1 and 255 columns");
        }
        try (RowOutput out = new RowOutput()) {
            // Row encoding is independent of the catalog's newer schema fields.
            out.writeByte(0);
            out.writeByte(table.getStorageVersion());
            out.writeByte(table.numColumns());
            if (table.getStorageVersion() >= 4) { out.writeByte(table.getRowFormat()); }
            if (table.usesRowIds()) { out.writeShort(table.getRowIdColumn()); }
            for (int i = 0; i < table.numColumns(); i++) {
                Column column = table.getColumn(i);
                byte[] name = column.getName().getBytes(StandardCharsets.UTF_8);
                if (name.length == 0 || name.length > 255) {
                    throw new IllegalArgumentException("Column names must contain 1 to 255 UTF-8 bytes");
                }
                out.writeByte(name.length);
                out.write(name);
                out.writeByte(column.getType());
                out.writeInt(column.getLength());
                out.writeBoolean(column.isNotNull());
                out.writeBoolean(column.isPrimaryKey());
                if (table.getStorageVersion() >= 3) {
                    out.writeBoolean(column.isUnique());
                    out.writeBoolean(column.hasDefault());
                    if (column.hasDefault()) { writeText(out, Json.stringify(column.getDefaultValue())); }
                }
                if (table.getStorageVersion() >= 6) { writeOptionalText(out, column.getDefaultSql()); }
            }
            if (table.getStorageVersion() >= 3) {
                out.writeInt(table.getChecks().size());
                for (String check : table.getChecks()) { writeText(out, check); }
                out.writeInt(table.getForeignKeys().size());
                for (ForeignKey reference : table.getForeignKeys()) {
                    if(table.getStorageVersion()>=5) {
                        writeNames(out,reference.columns()); writeText(out,reference.parentTable());
                        writeNames(out,reference.parentColumns()); writeText(out,reference.onDelete());
                        writeText(out,reference.onUpdate()); out.writeBoolean(reference.deferred());
                    } else {
                        writeText(out, reference.column()); writeText(out, reference.parentTable());
                        out.writeBoolean(reference.parentColumn() != null);
                        if (reference.parentColumn() != null) { writeText(out, reference.parentColumn()); }
                    }
                }
            }
            if(table.getStorageVersion()>=5) {
                writeNames(out,table.getPrimaryKeyColumns());
                out.writeInt(table.getUniqueKeys().size());
                for(List<String> key:table.getUniqueKeys()) { writeNames(out,key); }
            }
            if (table.getStorageVersion() >= 6) {
                out.writeInt(table.getConstraints().size());
                for (SchemaConstraint constraint : table.getConstraints()) {
                    writeOptionalText(out, constraint.name());
                    writeText(out, constraint.kind());
                    writeNames(out, constraint.columns());
                    writeOptionalText(out, constraint.expression());
                    writeOptionalText(out, constraint.ownerColumn());
                }
            }
            return out.getBufferBytes();
        } catch (IOException e) { throw new IllegalStateException("Could not encode schema", e); }
    }

    public static OperationStatus getMetadata(Table table) {
        String name = findName(table.getName());
        if (name == null) { return OperationStatus.NOTFOUND; }
        table.setName(name);
        DatabaseEntry data = new DatabaseEntry();
        OperationStatus status = catalogDB.get(DBMS.getTransaction(), key(name), data, null);
        if (status != OperationStatus.SUCCESS) { return status; }
        RowInput in = new RowInput(Arrays.copyOfRange(data.getData(),
                data.getOffset(), data.getOffset() + data.getSize()));
        int count = Byte.toUnsignedInt(in.readNextByte());
        int version = 0;
        Charset charset = StandardCharsets.ISO_8859_1;
        if (count == 0) {
            version = Byte.toUnsignedInt(in.readNextByte());
            if (version < 1 || version > 6) {
                throw new IllegalStateException("Unknown schema format: " + version);
            }
            count = Byte.toUnsignedInt(in.readNextByte());
            charset = StandardCharsets.UTF_8;
        }
        table.setStringCharset(charset);
        table.setStorageVersion(version);
        if (version >= 4) { table.setRowFormat(Byte.toUnsignedInt(in.readNextByte())); }
        table.setRowIdColumn(table.usesRowIds() ? in.readNextShort() : -1);
        for (int i = 0; i < count; i++) {
            int length = Byte.toUnsignedInt(in.readNextByte());
            String columnName = in.readNextString(length, charset);
            Column column = new Column(columnName, table);
            column.setType(in.readNextByte());
            column.setLength(in.readNextInt());
            column.applyOpts(new ColumnOptions(in.readNextBoolean(), in.readNextBoolean()));
            if (version >= 3) {
                column.setUnique(in.readNextBoolean());
                if (in.readNextBoolean()) { column.setDefaultValue(Json.parse(readText(in))); }
            }
            if (version >= 6) {
                String sql = readOptionalText(in);
                if (sql != null) { column.setDefaultExpression(sql); }
            }
            table.addColumn(column);
        }
        List<String> checks = new ArrayList<>();
        List<ForeignKey> foreignKeys = new ArrayList<>();
        if (version >= 3) {
            int checkCount = in.readNextInt();
            for (int i = 0; i < checkCount; i++) { checks.add(readText(in)); }
            int referenceCount = in.readNextInt();
            for (int i = 0; i < referenceCount; i++) {
                if(version>=5) {
                    List<String> children=readNames(in); String parent=readText(in);
                    List<String> parents=readNames(in); String onDelete=readText(in),onUpdate=readText(in);
                    foreignKeys.add(new ForeignKey(children.get(0),parent,parents.isEmpty() ? null : parents.get(0),
                            children,parents,onDelete,onUpdate,in.readNextBoolean()));
                } else {
                    String child = readText(in); String parent = readText(in);
                    String parentColumn = in.readNextBoolean() ? readText(in) : null;
                    foreignKeys.add(new ForeignKey(child, parent, parentColumn));
                }
            }
        }
        table.setPrimaryKeyColumns(version>=5 ? readNames(in) : List.of());
        List<List<String>> uniqueKeys=new ArrayList<>();
        if(version>=5) {
            int keyCount=in.readNextInt();
            for(int i=0;i<keyCount;i++) { uniqueKeys.add(readNames(in)); }
        }
        table.setUniqueKeys(uniqueKeys);
        List<SchemaConstraint> constraints = new ArrayList<>();
        if (version >= 6) {
            int size = in.readNextInt();
            for (int i = 0; i < size; i++) {
                constraints.add(new SchemaConstraint(readOptionalText(in), readText(in), readNames(in),
                        readOptionalText(in), readOptionalText(in)));
            }
        }
        table.setConstraints(constraints);
        table.setChecks(checks);
        table.setForeignKeys(foreignKeys);
        table.setStringCharset(table.getRowFormat() == 0 ? StandardCharsets.ISO_8859_1 : StandardCharsets.UTF_8);
        return OperationStatus.SUCCESS;
    }

    static void replaceMetadata(Table table, byte[] encoded) {
        catalogDB.put(DBMS.getTransaction(), key(table.getName()), new DatabaseEntry(encoded));
    }

    private static void writeText(RowOutput out, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        out.writeInt(bytes.length);
        out.write(bytes);
    }

    private static String readText(RowInput in) {
        return in.readNextString(in.readNextInt(), StandardCharsets.UTF_8);
    }
    private static void writeOptionalText(RowOutput out, String value) throws IOException {
        out.writeBoolean(value != null);
        if (value != null) { writeText(out, value); }
    }
    private static String readOptionalText(RowInput in) { return in.readNextBoolean() ? readText(in) : null; }
    private static void writeNames(RowOutput out,List<String> names) throws IOException {
        out.writeInt(names.size());
        for(String name:names) { writeText(out,name); }
    }
    private static List<String> readNames(RowInput in) {
        int size=in.readNextInt(); List<String> names=new ArrayList<>();
        for(int i=0;i<size;i++) { names.add(readText(in)); }
        return names;
    }

    public static OperationStatus removeMetadata(Table table) {
        String name = findName(table.getName());
        if (name == null) { return OperationStatus.NOTFOUND; }
        table.setName(name);
        return catalogDB.delete(DBMS.getTransaction(), key(name));
    }

    private static DatabaseEntry key(String name) {
        return new DatabaseEntry(name.getBytes(StandardCharsets.UTF_8));
    }
}
