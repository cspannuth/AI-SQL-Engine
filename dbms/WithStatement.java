import java.util.*;

/** WITH for writes stays a mutation AST and cannot pass read-only authorization. */
final class WithStatement extends SQLStatement {
    private final List<CteDefinition> definitions;
    private final SQLStatement body;
    WithStatement(List<CteDefinition> definitions,SQLStatement body) {
        this.definitions=List.copyOf(definitions); this.body=body;
        Set<String> names=new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        for(CteDefinition definition:definitions) {
            if(!names.add(definition.name())) { throw new IllegalArgumentException("Duplicate CTE: "+definition.name()); }
        }
    }
    protected void executeInternal() {
        try(QueryContext.Scope scope=QueryContext.ctes(definitions)) { body.executeInternal(); result=body.getResult(); }
    }
}
