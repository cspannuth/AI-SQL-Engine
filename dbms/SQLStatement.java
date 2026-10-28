import java.util.*;

/** Shared statement metadata, column resolution, and transaction boundary. */
public abstract class SQLStatement {
    private final ArrayList<Table> tables;
    private final ArrayList<Column> columns;
    private final ArrayList<Object> columnVals;
    private final ConditionalExpression where;
    private final ArrayList<Column> whereColumns;
    protected QueryResult result;

    public SQLStatement() { this(new ArrayList<>(), new ArrayList<>(), (ConditionalExpression) null); }
    public SQLStatement(Table table) { this(table, new ArrayList<Column>()); }
    public SQLStatement(Table table, ArrayList<Column> columns) {
        this(new ArrayList<>(List.of(table)), columns, (ConditionalExpression) null);
    }
    public SQLStatement(ArrayList<Table> tables, ArrayList<Column> columns, ConditionalExpression where) {
        this.tables = new ArrayList<>(tables);
        this.columns = columns == null ? new ArrayList<>() : new ArrayList<>(columns);
        this.columnVals = new ArrayList<>();
        this.where = where;
        this.whereColumns = where == null ? new ArrayList<>() : new ArrayList<>(where.getColumns());
    }
    public SQLStatement(Table table, ArrayList<Column> columns, ConditionalExpression where) {
        this(new ArrayList<>(List.of(table)), columns, where);
    }
    public SQLStatement(Table table, ArrayList<Column> columns, ArrayList<Object> values) {
        this(table, columns);
        columnVals.addAll(values);
    }

    public final void execute() {
        result = null;
        if (this instanceof BeginStatement || this instanceof CommitStatement || this instanceof RollbackStatement
                || this instanceof SavepointStatement) {
            executeInternal();
        } else { DBMS.executeStatement(this); }
    }

    protected abstract void executeInternal();

    /** Resolve every reference once, before row evaluation or mutation. */
    protected void bindColumns(List<TableIterator> iterators) {
        List<Column> references = new ArrayList<>(columns);
        references.addAll(whereColumns);
        for (Column reference : references) {
            Column match = null;
            TableIterator source = null;
            for (TableIterator iterator : iterators) {
                Column candidate = iterator.getTable().resolveColumn(reference);
                if (candidate != null) {
                    if (match != null) { throw new IllegalArgumentException("Ambiguous column: " + reference); }
                    match = candidate;
                    source = iterator;
                }
            }
            if (match == null) {
                for (List<TableIterator> scope : QueryContext.outerSources()) {
                    for (TableIterator iterator : scope) {
                        Column candidate = iterator.getTable().resolveColumn(reference);
                        if (candidate != null) {
                            if (match != null) { throw new IllegalArgumentException("Ambiguous outer column: " + reference); }
                            match = candidate;
                            source = iterator;
                        }
                    }
                    if (match != null) { break; }
                }
            }
            if (match == null) { throw new IllegalArgumentException("Unknown column: " + reference); }
            reference.useColInfo(match);
            reference.setTableIterator(source);
        }
    }

    protected void addExpression(CompareTerm expression) { columns.addAll(expression.getColumns()); }
    protected void clearColumnReferences() { columns.clear(); }
    public QueryResult getResult() { return result; }
    public void addTable(Table table) { tables.add(table); }
    public void addColumn(Column column) { columns.add(column); }
    public Table getTable(int index) { return tables.get(index); }
    public Column getColumn(int index) { return columns.get(index); }
    public Object getColumnVal(int index) { return columnVals.get(index); }
    public Column getWhereColumn(int index) { return whereColumns.get(index); }
    public ConditionalExpression getWhere() { return where; }
    public int numTables() { return tables.size(); }
    public int numColumns() { return columns.size(); }
    public int numColumnVals() { return columnVals.size(); }
    public int numWhereColumns() { return whereColumns.size(); }
}
