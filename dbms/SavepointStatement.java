/** SAVEPOINT/RELEASE/ROLLBACK TO are transaction controls, not implicit statements. */
public final class SavepointStatement extends SQLStatement {
    private final String action;
    private final String name;
    public SavepointStatement(String action,String name) { this.action=action; this.name=name; }
    protected void executeInternal() {
        switch(action) {
            case "SAVEPOINT": Savepoints.save(name); break;
            case "RELEASE": Savepoints.release(name); break;
            case "ROLLBACK": Savepoints.rollbackTo(name); break;
            default: throw new IllegalArgumentException("Unknown savepoint command");
        }
        result=QueryResult.changed(action+" "+name+".",0);
    }
}
