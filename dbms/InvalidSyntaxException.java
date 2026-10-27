/** A parse or lexical error that callers can handle without terminating the DBMS. */
public class InvalidSyntaxException extends IllegalArgumentException {
    public InvalidSyntaxException() { super("Invalid SQL syntax"); }
    public InvalidSyntaxException(String message) { super(message); }
}
