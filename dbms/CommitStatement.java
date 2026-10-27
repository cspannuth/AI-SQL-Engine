/*
 * CommitStatement.java
 *
 * DBMS Implementation
 */

import com.sleepycat.je.*;

/**
 * A class that represents a COMMIT statement in SQL, which commits
 * the current transaction.
 */
public class CommitStatement extends SQLStatement {
    /** Constructs a CommitStatement object */
    public CommitStatement() {
        super();
    }
    
    protected void executeInternal() {
        DBMS.commitTransaction();
        result = QueryResult.changed("Transaction committed.", 0);
    }
}
