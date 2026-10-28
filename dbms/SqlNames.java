import java.io.StringReader;
import java.util.*;
import java_cup.runtime.Symbol;

/** Token-aware schema edits preserve quoted literals and comments. */
final class SqlNames {
    static String quote(String name) { return "\"" + name.replace("\"", "\"\"") + "\""; }

    static String rename(String sql, String before, String after, boolean qualifier) {
        try {
            Lexer lexer = new Lexer(new StringReader(sql));
            List<Symbol> tokens = new ArrayList<>();
            Symbol token;
            while ((token = lexer.next_token()).sym != sym.EOF) { tokens.add(token); }
            StringBuilder result = new StringBuilder(sql);
            for (int i = tokens.size() - 1; i >= 0; i--) {
                token = tokens.get(i);
                boolean dot = i + 1 < tokens.size() && tokens.get(i + 1).sym == sym.DOT;
                boolean function = i + 1 < tokens.size() && tokens.get(i + 1).sym == sym.LPAREN;
                if (token.sym == sym.ID && before.equalsIgnoreCase((String) token.value)
                        && dot == qualifier && !function) {
                    result.replace(token.left, token.right, quote(after));
                }
            }
            return result.toString();
        } catch (java.io.IOException error) { throw new IllegalStateException("Could not edit schema SQL", error); }
    }
}
