import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;

/** Persistent terminal or JSON Lines client for the local read-only adapter. */
public final class DatabaseClient {
    private static final int MAX_INPUT = 262_144;

    public static void main(String[] args) {
        // Windows redirected streams can otherwise use a legacy code page.
        System.setOut(new PrintStream(System.out, true, StandardCharsets.UTF_8));
        System.setErr(new PrintStream(System.err, true, StandardCharsets.UTF_8));
        int status = run(args);
        if (status != 0) { System.exit(status); }
    }

    private static int run(String[] args) {
        boolean json = Arrays.asList(args).contains("--json");
        try {
            Options options = Options.parse(args);
            json = options.json;
            if (options.help) { help(); return 0; }
            if (!existingDatabase(options.home)) {
                emit(AdapterProtocol.error(null, "NOT_FOUND",
                        "No existing database in '" + options.home + "'. Use the directory passed to DBMS.", null, null), json);
                return 1;
            }
            DatabaseAdapter.AccessPolicy policy = options.tables.isEmpty()
                    ? DatabaseAdapter.AccessPolicy.readAll(options.caller)
                    : DatabaseAdapter.AccessPolicy.readTables(options.caller, options.tables);
            try (DatabaseAdapter adapter = DatabaseAdapter.open(options.home, policy, options.limits(), options.retention())) {
                AdapterProtocol protocol = new AdapterProtocol(adapter, options.limits(), options.schemaOnly);
                if (options.request != null) { return emit(protocol.handle(options.request), json) ? 0 : 1; }
                BufferedReader input = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
                return json ? jsonSession(protocol, input) : terminalSession(protocol, input);
            }
        } catch (DatabaseAdapter.AdapterException e) {
            emit(AdapterProtocol.error(null, e.getCode().name(), e.getMessage(), e.getRequestId(), e.getLimit()), json);
            return 1;
        } catch (IllegalArgumentException e) {
            emit(AdapterProtocol.error(null, "INVALID_REQUEST", e.getMessage(), null, null), json);
            return 2;
        } catch (IOException e) {
            emit(AdapterProtocol.error(null, "IO_ERROR", "Could not read the database directory or client input", null, null), json);
            return 1;
        } catch (RuntimeException e) {
            emit(AdapterProtocol.error(null, "DATABASE_ERROR", "Could not complete the database client operation", null, null), json);
            return 1;
        }
    }

    private static int jsonSession(AdapterProtocol protocol, BufferedReader input) throws IOException {
        boolean failed = false;
        InputLine line;
        while (!protocol.isClosed() && (line = readLine(input)) != null) {
            if (!line.tooLong && line.text.isBlank()) { continue; }
            Map<String, Object> response;
            try {
                if (line.tooLong) { throw new IllegalArgumentException("JSON request line exceeds 256 KiB"); }
                response = protocol.handle(Json.parse(line.text));
            } catch (IllegalArgumentException e) {
                response = AdapterProtocol.error(null, "INVALID_REQUEST", e.getMessage(), null, null);
            }
            if (!emit(response, true)) { failed = true; }
        }
        return failed ? 1 : 0;
    }

    private static int terminalSession(AdapterProtocol protocol, BufferedReader input) throws IOException {
        boolean interactive = System.console() != null;
        boolean failed = false;
        StringBuilder pending = new StringBuilder();
        List<?> parameters = List.of();
        if (interactive) { System.out.println("Database client. Enter SELECT SQL or .help. Use .quit to close."); }
        while (!protocol.isClosed()) {
            if (interactive) { System.out.print(SqlScript.isBlank(pending.toString()) ? "db> " : " -> "); }
            InputLine line = readLine(input);
            if (line == null) { break; }
            try {
                if (line.tooLong) { throw new IllegalArgumentException("Input line exceeds 256 KiB"); }
                String command = line.text.trim();
                if (command.equals(".quit")) {
                    pending.setLength(0);
                    emit(protocol.handle(AdapterProtocol.map("operation", "close")), false);
                    break;
                }
                if (command.equals(".clear")) {
                    pending.setLength(0);
                    parameters = List.of();
                    continue;
                }
                if (SqlScript.isBlank(pending.toString()) && (command.startsWith(".") || command.equalsIgnoreCase("q"))) {
                    String[] parts = command.split("\\s+", 2);
                    String argument = parts.length == 2 ? parts[1] : "";
                    if (parts[0].equals(".help")) { help(); continue; }
                    if (parts[0].equals(".params")) {
                        Object value = Json.parse(argument);
                        if (!(value instanceof List<?>)) { throw new IllegalArgumentException(".params requires a JSON array"); }
                        parameters = (List<?>) value;
                        continue;
                    }
                    Map<String, Object> request;
                    switch (parts[0].toLowerCase(Locale.ROOT)) {
                        case ".tables": request = AdapterProtocol.map("operation", "list_tables"); break;
                        case ".schema": request = AdapterProtocol.map("operation", "describe_table", "table", argument); break;
                        case ".capabilities": request = AdapterProtocol.map("operation", "get_capabilities"); break;
                        case ".result": request = AdapterProtocol.map("operation", "get_result", "resultId", argument); break;
                        case ".quit": case "q": request = AdapterProtocol.map("operation", "close"); break;
                        default: throw new IllegalArgumentException("Unknown command. Enter .help for commands");
                    }
                    if (!emit(protocol.handle(request), false)) { failed = true; }
                    continue;
                }
                pending.append(line.text).append('\n');
                if (pending.toString().getBytes(StandardCharsets.UTF_8).length > DatabaseAdapter.MAX_REQUEST_BYTES) {
                    throw new IllegalArgumentException("SQL input exceeds 64 KiB; the pending statement was cleared");
                }
                for (String sql : SqlScript.takeStatements(pending)) {
                    Map<String, Object> request = AdapterProtocol.map("operation", "query_readonly", "sql", sql, "parameters", parameters);
                    parameters = List.of();
                    if (!emit(protocol.handle(request), false)) { failed = true; }
                }
            } catch (IllegalArgumentException e) {
                pending.setLength(0);
                parameters = List.of();
                emit(AdapterProtocol.error(null, "INVALID_REQUEST", e.getMessage(), null, null), false);
                failed = true;
            }
        }
        if (!SqlScript.isBlank(pending.toString())) {
            emit(AdapterProtocol.error(null, "SQL_ERROR", "Incomplete SQL statement; expected ';'", null, null), false);
            failed = true;
        }
        return failed ? 1 : 0;
    }

    /** Validate encoding before emitting anything so errors never leave partial JSON. */
    private static boolean emit(Map<String, Object> response, boolean json) {
        String encoded;
        try { encoded = Json.stringify(response); }
        catch (IllegalArgumentException e) {
            response = AdapterProtocol.error(response.get("id"), "DATABASE_ERROR", e.getMessage(), null, null);
            encoded = Json.stringify(response);
        }
        boolean success = Boolean.TRUE.equals(response.get("ok"));
        if (json) { System.out.println(encoded); }
        else if (!success) {
            Map<String, Object> error = AdapterProtocol.object(response.get("error"));
            System.err.println(error.get("code") + ": " + error.get("message"));
        } else {
            Object data = response.get("data");
            String operation = (String) response.get("operation");
            if (operation.equals("list_tables")) {
                List<?> tables = (List<?>) data;
                if (tables.isEmpty()) { System.out.println("No tables found in this database for this caller."); }
                for (Object table : tables) { System.out.println(Json.stringify(table)); }
            } else if (operation.equals("query_readonly") || operation.equals("get_result")) {
                Map<String, Object> result = AdapterProtocol.object(data);
                System.out.println("Columns: " + Json.stringify(result.get("columns")));
                for (Object row : (List<?>) result.get("rows")) { System.out.println(Json.stringify(row)); }
                System.out.println("Rows: " + result.get("returnedRows") + "; complete: " + result.get("complete")
                        + (Boolean.TRUE.equals(result.get("truncated")) ? "; truncated by " + result.get("truncationReason") : ""));
                System.out.println("Result ID: " + result.get("resultId"));
            } else if (operation.equals("close")) { System.out.println("Session closed."); }
            else { System.out.println(Json.stringify(data)); }
        }
        System.out.flush();
        return success && !System.out.checkError();
    }

    /** Discard an oversized line through its newline, allowing the next request to recover. */
    private static InputLine readLine(BufferedReader input) throws IOException {
        StringBuilder text = new StringBuilder();
        boolean tooLong = false;
        int c;
        while ((c = input.read()) != -1 && c != '\n') {
            if (text.length() < MAX_INPUT) { text.append((char) c); }
            else { tooLong = true; }
        }
        if (c == -1 && text.length() == 0 && !tooLong) { return null; }
        String line = text.toString();
        if (line.endsWith("\r")) { line = line.substring(0, line.length() - 1); }
        return new InputLine(line, tooLong || line.getBytes(StandardCharsets.UTF_8).length > MAX_INPUT);
    }

    private record InputLine(String text, boolean tooLong) { }

    private static boolean existingDatabase(Path home) throws IOException {
        if (!Files.isDirectory(home)) { return false; }
        try (DirectoryStream<Path> files = Files.newDirectoryStream(home, "*.jdb")) {
            for (Path file : files) { if (Files.isRegularFile(file)) { return true; } }
        }
        return false;
    }

    private static void help() {
        System.out.println("""
                Usage: DatabaseClient <database-directory> [options]
                  --tables | --schema <table> | --capabilities | --query <SQL>
                  --params <JSON-array>        Bound values for --query
                  --json                       JSON output; JSON Lines session without a one-shot operation
                  --schema-only                Allow schema discovery, excluding queries and stored rows
                  --allow-table <table>         Repeat to restrict tables; default is local owner access
                  --caller <id>                Fixed caller identity (default: local-client)
                  --max-rows <n>               Returned row ceiling (default: 1000)
                  --max-bytes <n>              Returned payload ceiling (default: 1048576)
                  --max-scanned-rows <n>        Table scan ceiling (default: 100000)
                  --max-working-rows <n>        Intermediate allocation ceiling (default: 10000)
                  --max-working-bytes <n>       Intermediate payload ceiling (default: 16777216)
                  --timeout-ms <n>             Cooperative deadline (default: 5000)
                  --max-results <n>            Retained result count (default: 20)
                  --max-retained-bytes <n>      Retained payload ceiling (default: 33554432)
                  --result-ttl-seconds <n>      Result lifetime (default: 600)
                  --help                       Show this help

                Terminal commands: .tables, .schema <table>, .capabilities,
                  .params <JSON-array>, .result <id>, .clear, .quit
                Enter semicolon-terminated SELECT SQL across one or more lines.
                Results remain available by ID until expiry, eviction, or session closure.
                """);
    }

    private static class Options {
        Path home;
        boolean json, schemaOnly, help;
        String caller = "local-client";
        Set<String> tables = new HashSet<>();
        Map<String, Object> request;
        int maxRows = 1_000, maxWorkingRows = 10_000, maxResults = 20;
        long maxBytes = 1_048_576, maxScannedRows = 100_000, maxWorkingBytes = 16_777_216;
        long timeoutMillis = 5_000, maxRetainedBytes = 33_554_432, ttlSeconds = 600;

        QueryLimits limits() { return new QueryLimits(maxRows, maxBytes, maxScannedRows, maxWorkingRows, maxWorkingBytes, timeoutMillis); }
        DatabaseAdapter.Retention retention() { return new DatabaseAdapter.Retention(maxResults, maxRetainedBytes, Duration.ofSeconds(ttlSeconds)); }

        static Options parse(String[] args) {
            Options options = new Options();
            List<?> parameters = null;
            Set<String> seen = new HashSet<>();
            for (int i = 0; i < args.length; i++) {
                String flag = args[i];
                if (!flag.startsWith("--")) {
                    if (options.home != null || flag.isBlank()) { throw new IllegalArgumentException("Expected one database directory"); }
                    options.home = Paths.get(flag);
                    continue;
                }
                if (!flag.equals("--allow-table") && !seen.add(flag)) { throw new IllegalArgumentException("Repeated option: " + flag); }
                if (Set.of("--json", "--schema-only", "--help", "--tables", "--list-tables", "--capabilities").contains(flag)) {
                    switch (flag) {
                        case "--json": options.json = true; break;
                        case "--schema-only": options.schemaOnly = true; break;
                        case "--help": options.help = true; break;
                        case "--tables": case "--list-tables": options.action("list_tables"); break;
                        case "--capabilities": options.action("get_capabilities"); break;
                    }
                    continue;
                }
                if (++i == args.length) { throw new IllegalArgumentException("Missing value for " + flag); }
                String value = args[i];
                switch (flag) {
                    case "--query": options.action("query_readonly").put("sql", value); break;
                    case "--schema": options.action("describe_table").put("table", value); break;
                    case "--params":
                        Object parsed = Json.parse(value);
                        if (!(parsed instanceof List<?>)) { throw new IllegalArgumentException("--params requires a JSON array"); }
                        parameters = (List<?>) parsed;
                        break;
                    case "--caller": options.caller = value; break;
                    case "--allow-table":
                        if (value.isEmpty()) { throw new IllegalArgumentException("Allowed table name is required"); }
                        options.tables.add(value);
                        break;
                    case "--max-rows": options.maxRows = positiveInt(flag, value); break;
                    case "--max-bytes": options.maxBytes = positive(flag, value); break;
                    case "--max-scanned-rows": options.maxScannedRows = positive(flag, value); break;
                    case "--max-working-rows": options.maxWorkingRows = positiveInt(flag, value); break;
                    case "--max-working-bytes": options.maxWorkingBytes = positive(flag, value); break;
                    case "--timeout-ms": options.timeoutMillis = positive(flag, value); break;
                    case "--max-results": options.maxResults = positiveInt(flag, value); break;
                    case "--max-retained-bytes": options.maxRetainedBytes = positive(flag, value); break;
                    case "--result-ttl-seconds": options.ttlSeconds = positive(flag, value); break;
                    default: throw new IllegalArgumentException("Unknown option: " + flag);
                }
            }
            if (options.help) { return options; }
            if (options.home == null) { throw new IllegalArgumentException("Database directory is required; use --help for usage"); }
            if (options.caller.isBlank()) { throw new IllegalArgumentException("Caller identity is required"); }
            if (parameters != null) {
                if (options.request == null || !options.request.get("operation").equals("query_readonly")) {
                    throw new IllegalArgumentException("--params requires --query; use .params in a terminal session");
                }
                options.request.put("parameters", parameters);
            }
            options.limits();
            options.retention();
            return options;
        }

        private Map<String, Object> action(String name) {
            if (request != null) { throw new IllegalArgumentException("Choose only one one-shot operation"); }
            request = AdapterProtocol.map("operation", name);
            return request;
        }
        private static long positive(String name, String text) {
            try {
                long number = Long.parseLong(text);
                if (number > 0) { return number; }
            } catch (NumberFormatException e) { /* Report the option rather than a Java stack trace. */ }
            throw new IllegalArgumentException(name + " requires a positive integer");
        }
        private static int positiveInt(String name, String text) {
            long value = positive(name, text);
            if (value > Integer.MAX_VALUE) { throw new IllegalArgumentException(name + " is out of range"); }
            return (int) value;
        }
    }
}
