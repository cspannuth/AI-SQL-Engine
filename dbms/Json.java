import java.lang.reflect.RecordComponent;
import java.time.*;
import java.util.*;

/** Small strict JSON codec for the local client protocol; no external dependency. */
public final class Json {
    private static final int MAX_DEPTH = 32;
    private Json() { }

    public static Object parse(String text) {
        Reader reader = new Reader(Objects.requireNonNull(text));
        Object value = reader.value(0);
        reader.space();
        if (reader.position != text.length()) { throw reader.error("Unexpected trailing data"); }
        return value;
    }

    public static String stringify(Object value) {
        StringBuilder output = new StringBuilder();
        write(output, value, 0);
        return output.toString();
    }

    /** Preserve DTO component names while keeping transport code out of the engine. */
    public static Map<String, Object> record(Object value) {
        if (!value.getClass().isRecord()) { throw new IllegalArgumentException("Expected a record"); }
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            for (RecordComponent component : value.getClass().getRecordComponents()) {
                result.put(component.getName(), component.getAccessor().invoke(value));
            }
        } catch (ReflectiveOperationException e) {
            throw new IllegalArgumentException("Cannot serialize result metadata", e);
        }
        return result;
    }

    private static void write(StringBuilder output, Object value, int depth) {
        if (depth > MAX_DEPTH) { throw new IllegalArgumentException("JSON nesting is too deep"); }
        if (value == null) { output.append("null"); }
        else if (value instanceof String || value instanceof Enum<?> || value instanceof Instant || value instanceof Duration) {
            string(output, value instanceof Enum<?> ? ((Enum<?>) value).name() : value.toString());
        } else if (value instanceof Boolean) { output.append(value); }
        else if (value instanceof Number) {
            if ((value instanceof Double || value instanceof Float) && !Double.isFinite(((Number) value).doubleValue())) {
                throw new IllegalArgumentException("JSON cannot represent non-finite numeric results");
            }
            output.append(value);
        } else if (value instanceof Map<?, ?>) {
            output.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                if (!(entry.getKey() instanceof String)) { throw new IllegalArgumentException("JSON keys must be strings"); }
                if (!first) { output.append(','); }
                first = false;
                string(output, (String) entry.getKey());
                output.append(':');
                write(output, entry.getValue(), depth + 1);
            }
            output.append('}');
        } else if (value instanceof Iterable<?>) {
            output.append('[');
            boolean first = true;
            for (Object item : (Iterable<?>) value) {
                if (!first) { output.append(','); }
                first = false;
                write(output, item, depth + 1);
            }
            output.append(']');
        } else if (value.getClass().isRecord()) { write(output, record(value), depth + 1); }
        else { throw new IllegalArgumentException("Unsupported JSON value type"); }
    }

    private static void string(StringBuilder output, String value) {
        output.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"': output.append("\\\""); break;
                case '\\': output.append("\\\\"); break;
                case '\b': output.append("\\b"); break;
                case '\f': output.append("\\f"); break;
                case '\n': output.append("\\n"); break;
                case '\r': output.append("\\r"); break;
                case '\t': output.append("\\t"); break;
                default:
                    if (c < 32 || Character.isSurrogate(c)) { output.append(String.format("\\u%04x", (int) c)); }
                    else { output.append(c); }
            }
        }
        output.append('"');
    }

    private static class Reader {
        final String text;
        int position;
        Reader(String text) { this.text = text; }
        IllegalArgumentException error(String message) {
            return new IllegalArgumentException(message + " at character " + position);
        }
        void space() {
            while (position < text.length() && " \r\n\t".indexOf(text.charAt(position)) >= 0) { position++; }
        }
        boolean take(char c) {
            space();
            if (position < text.length() && text.charAt(position) == c) { position++; return true; }
            return false;
        }
        void require(char c) { if (!take(c)) { throw error("Expected '" + c + "'"); } }

        Object value(int depth) {
            space();
            if (depth > MAX_DEPTH || position == text.length()) { throw error("Missing value or excessive nesting"); }
            char c = text.charAt(position);
            if (c == '"') { return string(); }
            if (c == '{') {
                position++;
                Map<String, Object> object = new LinkedHashMap<>();
                if (take('}')) { return object; }
                do {
                    space();
                    if (position == text.length() || text.charAt(position) != '"') { throw error("Expected an object key"); }
                    String key = string();
                    if (object.containsKey(key)) { throw error("Duplicate object key"); }
                    require(':');
                    object.put(key, value(depth + 1));
                } while (take(','));
                require('}');
                return object;
            }
            if (c == '[') {
                position++;
                List<Object> array = new ArrayList<>();
                if (take(']')) { return array; }
                do { array.add(value(depth + 1)); } while (take(','));
                require(']');
                return array;
            }
            for (String literal : List.of("true", "false", "null")) {
                if (text.startsWith(literal, position)) {
                    position += literal.length();
                    return literal.equals("null") ? null : literal.equals("true");
                }
            }
            return number();
        }

        String string() {
            position++;
            StringBuilder value = new StringBuilder();
            while (position < text.length()) {
                char c = text.charAt(position++);
                if (c == '"') { return value.toString(); }
                if (c < 32) { throw error("Unescaped control character"); }
                if (c != '\\') { value.append(c); continue; }
                if (position == text.length()) { throw error("Incomplete string escape"); }
                char escape = text.charAt(position++);
                switch (escape) {
                    case '"': case '\\': case '/': value.append(escape); break;
                    case 'b': value.append('\b'); break;
                    case 'f': value.append('\f'); break;
                    case 'n': value.append('\n'); break;
                    case 'r': value.append('\r'); break;
                    case 't': value.append('\t'); break;
                    case 'u':
                        if (position + 4 > text.length()) { throw error("Incomplete Unicode escape"); }
                        for (int i = position; i < position + 4; i++) {
                            if ("0123456789abcdefABCDEF".indexOf(text.charAt(i)) < 0) { throw error("Invalid Unicode escape"); }
                        }
                        try { value.append((char) Integer.parseInt(text.substring(position, position + 4), 16)); }
                        catch (NumberFormatException e) { throw error("Invalid Unicode escape"); }
                        position += 4;
                        break;
                    default: throw error("Invalid string escape");
                }
            }
            throw error("Unterminated string");
        }

        Object number() {
            int start = position;
            if (position < text.length() && text.charAt(position) == '-') { position++; }
            if (position == text.length()) { throw error("Invalid number"); }
            if (text.charAt(position) == '0') { position++; }
            else { digits(); }
            boolean real = false;
            if (position < text.length() && text.charAt(position) == '.') { real = true; position++; digits(); }
            if (position < text.length() && "eE".indexOf(text.charAt(position)) >= 0) {
                real = true;
                position++;
                if (position < text.length() && "+-".indexOf(text.charAt(position)) >= 0) { position++; }
                digits();
            }
            try {
                String number = text.substring(start, position);
                if (!real) { return Long.valueOf(number); }
                double result = Double.parseDouble(number);
                if (Double.isFinite(result)) { return result; }
            } catch (NumberFormatException e) { throw error("Number is out of range"); }
            throw error("Number must be finite");
        }

        void digits() {
            int start = position;
            while (position < text.length() && text.charAt(position) >= '0' && text.charAt(position) <= '9') { position++; }
            if (position == start) { throw error("Expected a digit"); }
        }
    }
}
