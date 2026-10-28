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
        catalogDB = DBMS.getEnv().openDatabase(null, CATALOG_NAME, config);
    }

    public static void close() {
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
        if (table.numColumns() == 0 || table.numColumns() > 255) {
            throw new IllegalArgumentException("A table must contain between 1 and 255 columns");
        }
        try (RowOutput out = new RowOutput()) {
            // Zero cannot be a legacy column count. Versions 2/3 add row IDs/constraints.
            out.writeByte(0);
            out.writeByte(table.getStorageVersion());
            out.writeByte(table.numColumns());
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
            }
            if (table.getStorageVersion() >= 3) {
                out.writeInt(table.getChecks().size());
                for (String check : table.getChecks()) { writeText(out, check); }
                out.writeInt(table.getForeignKeys().size());
                for (ForeignKey reference : table.getForeignKeys()) {
                    writeText(out, reference.column());
                    writeText(out, reference.parentTable());
                    out.writeBoolean(reference.parentColumn() != null);
                    if (reference.parentColumn() != null) { writeText(out, reference.parentColumn()); }
                }
            }
            return catalogDB.putNoOverwrite(DBMS.getTransaction(),
                    key(table.getName()), new DatabaseEntry(out.getBufferBytes()));
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
            if (version < 1 || version > 3) {
                throw new IllegalStateException("Unknown schema format: " + version);
            }
            count = Byte.toUnsignedInt(in.readNextByte());
            charset = StandardCharsets.UTF_8;
        }
        table.setStringCharset(charset);
        table.setStorageVersion(version);
        table.setRowIdColumn(version >= 2 ? in.readNextShort() : -1);
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
            table.addColumn(column);
        }
        List<String> checks = new ArrayList<>();
        List<ForeignKey> foreignKeys = new ArrayList<>();
        if (version >= 3) {
            int checkCount = in.readNextInt();
            for (int i = 0; i < checkCount; i++) { checks.add(readText(in)); }
            int referenceCount = in.readNextInt();
            for (int i = 0; i < referenceCount; i++) {
                String child = readText(in);
                String parent = readText(in);
                String parentColumn = in.readNextBoolean() ? readText(in) : null;
                foreignKeys.add(new ForeignKey(child, parent, parentColumn));
            }
        }
        table.setChecks(checks);
        table.setForeignKeys(foreignKeys);
        return OperationStatus.SUCCESS;
    }

    private static void writeText(RowOutput out, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        out.writeInt(bytes.length);
        out.write(bytes);
    }

    private static String readText(RowInput in) {
        return in.readNextString(in.readNextInt(), StandardCharsets.UTF_8);
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
