import java.io.PrintStream;
import java.util.*;

/** Typed statement output. Only the CLI is responsible for printing it. */
public final class QueryResult {
    private final List<String> columns;
    private final List<SqlType> columnTypes;
    private final List<SqlType> affinities;
    private final List<List<Object>> rows;
    private final int affectedRows;
    private final String message;
    private final String truncationReason;

    public QueryResult(List<String> columns, List<List<Object>> rows) {
        this(columns, Collections.nCopies(columns.size(), SqlType.UNKNOWN), rows, null);
    }

    public QueryResult(List<String> columns, List<SqlType> types, List<List<Object>> rows,
            String truncationReason) {
        this(columns, types, rows, truncationReason, Collections.nCopies(columns.size(), SqlType.UNKNOWN));
    }
    QueryResult(List<String> columns, List<SqlType> types, List<List<Object>> rows,
            String truncationReason, List<SqlType> affinities) {
        this.columns = List.copyOf(columns);
        this.columnTypes = List.copyOf(types);
        this.affinities = List.copyOf(affinities);
        if (columns.size() != types.size()) { throw new IllegalArgumentException("Column type count mismatch"); }
        List<List<Object>> copied = new ArrayList<>();
        for (List<Object> row : rows) {
            if (row.size() != columns.size()) { throw new IllegalArgumentException("Result row width mismatch"); }
            // List.copyOf rejects SQL nulls.
            copied.add(Collections.unmodifiableList(new ArrayList<>(row)));
        }
        this.rows = Collections.unmodifiableList(copied);
        this.affectedRows = 0;
        this.message = null;
        this.truncationReason = truncationReason;
    }

    private QueryResult(String message, int affectedRows) {
        this.columns = List.of();
        this.columnTypes = List.of();
        this.affinities = List.of();
        this.rows = List.of();
        this.affectedRows = affectedRows;
        this.message = message;
        this.truncationReason = null;
    }

    public static QueryResult changed(String message, int affectedRows) {
        return new QueryResult(message, affectedRows);
    }
    private QueryResult(QueryResult source,int affected) {
        columns=source.columns; columnTypes=source.columnTypes; affinities=source.affinities;
        rows=source.rows; affectedRows=affected; message=null; truncationReason=source.truncationReason;
    }
    QueryResult withAffectedRows(int count) { return new QueryResult(this,count); }

    public List<String> getColumns() { return columns; }
    public List<SqlType> getColumnTypes() { return columnTypes; }
    List<SqlType> getAffinities() { return affinities; }
    public boolean isTruncated() { return truncationReason != null; }
    public String getTruncationReason() { return truncationReason; }
    public List<List<Object>> getRows() { return rows; }
    public int getAffectedRows() { return affectedRows; }

    public void print(PrintStream out) {
        if (message != null) { out.println(message); return; }
        int[] widths = new int[columns.size()];
        for (int i = 0; i < widths.length; i++) {
            widths[i] = columns.get(i).length();
            for (List<Object> row : rows) {
                widths[i] = Math.max(widths[i], String.valueOf(row.get(i)).length());
            }
        }
        printRow(out, new ArrayList<Object>(columns), widths);
        for (List<Object> row : rows) { printRow(out, row, widths); }
        out.println("Selected " + rows.size() + (rows.size() == 1 ? " tuple." : " tuples."));
    }

    private static void printRow(PrintStream out, List<Object> row, int[] widths) {
        for (int i = 0; i < row.size(); i++) {
            out.printf(" | %-" + Math.max(1, widths[i]) + "s", String.valueOf(row.get(i)));
        }
        out.println(" | ");
    }
}
