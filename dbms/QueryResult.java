import java.io.PrintStream;
import java.util.*;

/** Typed statement output. Only the CLI is responsible for printing it. */
public final class QueryResult {
    private final List<String> columns;
    private final List<List<Object>> rows;
    private final int affectedRows;
    private final String message;

    public QueryResult(List<String> columns, List<List<Object>> rows) {
        this.columns = List.copyOf(columns);
        List<List<Object>> copied = new ArrayList<>();
        for (List<Object> row : rows) {
            // List.copyOf rejects SQL nulls.
            copied.add(Collections.unmodifiableList(new ArrayList<>(row)));
        }
        this.rows = Collections.unmodifiableList(copied);
        this.affectedRows = 0;
        this.message = null;
    }

    private QueryResult(String message, int affectedRows) {
        this.columns = List.of();
        this.rows = List.of();
        this.affectedRows = affectedRows;
        this.message = message;
    }

    public static QueryResult changed(String message, int affectedRows) {
        return new QueryResult(message, affectedRows);
    }

    public List<String> getColumns() { return columns; }
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
