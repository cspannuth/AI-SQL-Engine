import java.io.*;
import java.nio.file.*;
import java.util.*;
import com.sleepycat.je.*;

/** Database lifecycle, a serialized SQL session, and the command-line client. */
public class DBMS {
    public static final boolean DEBUG = Boolean.getBoolean("dbms.debug");
    public static final String DB_HOME = "db";
    private static Environment env;
    private static Transaction transaction;

    public static void main(String[] args) throws IOException {
        boolean interactive = System.console() != null;
        try {
            init(Paths.get(args.length == 0 ? DB_HOME : args[0]));
            Runtime.getRuntime().addShutdownHook(new Thread(DBMS::shutdown));
            BufferedReader console = new BufferedReader(new InputStreamReader(System.in));
            StringBuilder pending = new StringBuilder();
            while (true) {
                if (interactive) { System.out.print(SqlScript.isBlank(pending.toString()) ? "sql> " : "  -> "); }
                String line = console.readLine();
                if (line == null || (SqlScript.isBlank(pending.toString()) && line.trim().equalsIgnoreCase("q"))) { break; }
                pending.append(line).append('\n');
                for (String sql : SqlScript.takeStatements(pending)) {
                    try { execute(sql).print(System.out); }
                    catch (RuntimeException e) {
                        System.err.println("Error: " + e.getMessage());
                        if (DEBUG) { e.printStackTrace(); }
                    }
                }
            }
            if (!SqlScript.isBlank(pending.toString())) {
                System.err.println("Error: incomplete SQL statement (expected ';').");
            }
        } finally { shutdown(); }
    }

    public static synchronized void init() { init(Paths.get(DB_HOME)); }

    /** Tests and applications choose a database without changing directory. */
    public static synchronized void init(Path home) {
        if (env != null) { throw new IllegalStateException("The database is already open"); }
        try {
            Files.createDirectories(home);
            EnvironmentConfig config = new EnvironmentConfig();
            config.setAllowCreate(true);
            config.setTransactional(true);
            env = new Environment(home.toFile(), config);
            Catalog.open();
            Table.cacheInit();
        } catch (IOException | RuntimeException e) {
            shutdown();
            throw new IllegalStateException("Could not open database: " + home, e);
        }
    }

    /** Parse and execute one SQL statement. */
    public static synchronized QueryResult execute(String sql) {
        if (env == null) { throw new IllegalStateException("The database is not open"); }
        try {
            SQLStatement statement = (SQLStatement) new Parser(new Lexer(new StringReader(sql))).parse().value;
            statement.execute();
            return statement.getResult();
        } catch (RuntimeException e) { throw e; }
        catch (Exception e) {
            throw new IllegalArgumentException("Could not parse SQL: " + e.getMessage(), e);
        }
    }

    /** Each statement is atomic, including catalog changes. */
    static synchronized void executeStatement(SQLStatement statement) {
        boolean implicit = transaction == null;
        if (implicit) { transaction = env.beginTransaction(null, null); }
        try {
            statement.executeInternal();
            Table.cacheClose();
            if (implicit) {
                Transaction completed = transaction;
                transaction = null;
                completed.commit();
            }
        } catch (RuntimeException e) {
            try {
                Table.cacheClose();
                // Logical errors are checked before mutation. Storage failures
                // may invalidate a transaction and require a full rollback.
                if (transaction != null && (implicit || !(e instanceof IllegalArgumentException)
                        || !transaction.isValid())) { rollbackTransaction(); }
            } catch (RuntimeException cleanup) { e.addSuppressed(cleanup); }
            throw e;
        } finally { Table.cacheInit(); }
    }

    public static synchronized void beginTransaction() {
        if (transaction != null) { throw new IllegalArgumentException("A transaction is already active"); }
        transaction = env.beginTransaction(null, null);
    }

    public static synchronized void commitTransaction() {
        if (transaction == null) { throw new IllegalArgumentException("No transaction is active"); }
        Table.cacheClose();
        Transaction completed = transaction;
        transaction = null;
        try { completed.commit(); }
        finally { Table.cacheInit(); }
    }

    public static synchronized void rollbackTransaction() {
        if (transaction == null) { throw new IllegalArgumentException("No transaction is active"); }
        Table.cacheClose();
        Transaction aborted = transaction;
        transaction = null;
        try { aborted.abort(); }
        finally { Table.cacheInit(); }
    }

    /** Never commit an unfinished user transaction during shutdown. */
    public static synchronized void shutdown() {
        try {
            if (transaction != null) { rollbackTransaction(); }
        } finally {
            try { Table.cacheClose(); }
            finally {
                try { Catalog.close(); }
                finally {
                    if (env != null) {
                        Environment closing = env;
                        env = null;
                        closing.close();
                    }
                }
            }
        }
    }

    public static Environment getEnv() { return env; }
    public static Transaction getTransaction() { return transaction; }
}
