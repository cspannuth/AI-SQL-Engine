import java.util.*;

/** Split CLI input at semicolons outside quotes and SQL comments. */
public final class SqlScript {
    private SqlScript() { }

    public static List<String> takeStatements(StringBuilder input) {
        List<String> statements = new ArrayList<>();
        char quote = 0;
        boolean lineComment = false;
        boolean blockComment = false;
        int start = 0;
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            char next = i + 1 < input.length() ? input.charAt(i + 1) : 0;
            if (lineComment) {
                if (c == '\n') { lineComment = false; }
            } else if (blockComment) {
                if (c == '*' && next == '/') { blockComment = false; i++; }
            } else if (quote != 0) {
                if (c == quote && next == quote) { i++; }
                else if (c == quote) { quote = 0; }
            } else if (c == '-' && next == '-') {
                lineComment = true; i++;
            } else if (c == '/' && next == '*') {
                blockComment = true; i++;
            } else if (c == '\'' || c == '"') {
                quote = c;
            } else if (c == ';') {
                String sql = input.substring(start, i + 1);
                if (!isBlank(sql.substring(0, sql.length() - 1))) { statements.add(sql); }
                start = i + 1;
            }
        }
        input.delete(0, start);
        return statements;
    }

    public static boolean isBlank(String sql) {
        return sql.replaceAll("(?s)/\\*.*?\\*/", "")
                  .replaceAll("(?m)--[^\\r\\n]*", "").trim().isEmpty();
    }
}
