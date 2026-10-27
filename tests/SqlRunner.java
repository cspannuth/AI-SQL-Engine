import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Test-only line protocol: base64 SQL in, JSON results out. */
public class SqlRunner {
    public static void main(String[] args) throws Exception {
        DBMS.init(Paths.get(args[0]));
        try {
            BufferedReader input = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
            String line;
            while ((line = input.readLine()) != null) {
                String sql = new String(Base64.getDecoder().decode(line), StandardCharsets.UTF_8);
                try {
                    QueryResult result = DBMS.execute(sql);
                    System.out.println("{\"rows\":" + json(result.getRows())
                            + ",\"affected\":" + result.getAffectedRows() + "}");
                } catch (RuntimeException error) {
                    System.out.println("{\"error\":" + json(error.getMessage()) + "}");
                }
                System.out.flush();
            }
        } finally { DBMS.shutdown(); }
    }

    private static String json(Object value) {
        if (value == null) { return "null"; }
        if (value instanceof Number || value instanceof Boolean) { return value.toString(); }
        if (value instanceof List<?>) {
            List<String> values = new ArrayList<>();
            for (Object item : (List<?>) value) { values.add(json(item)); }
            return "[" + String.join(",", values) + "]";
        }
        StringBuilder text = new StringBuilder("\"");
        for (char c : value.toString().toCharArray()) {
            switch (c) {
                case '\\': text.append("\\\\"); break;
                case '"': text.append("\\\""); break;
                case '\n': text.append("\\n"); break;
                case '\r': text.append("\\r"); break;
                case '\t': text.append("\\t"); break;
                default:
                    if (c < 32) { text.append(String.format("\\u%04x", (int)c)); }
                    else { text.append(c); }
            }
        }
        return text.append('"').toString();
    }
}
