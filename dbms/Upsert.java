import java.util.*;

/** One supported ON CONFLICT clause. Empty target matches any uniqueness conflict. */
final class Upsert {
    final List<String> target;
    final List<Column> assignments;
    final ConditionalExpression where;
    Upsert(List<Column> target,List<Column> assignments,ConditionalExpression where) {
        List<String> names=new ArrayList<>();
        for(Column column:target) {
            if(column.getTable()!=null) { throw new IllegalArgumentException("Conflict targets must be unqualified columns"); }
            names.add(column.getName());
        }
        this.target=List.copyOf(names);
        this.assignments=assignments==null ? null : List.copyOf(assignments);
        this.where=where;
    }
}
