import java.util.*;

/** Composite reference metadata; singular fields preserve the original schema DTO. */
public record ForeignKey(String column,String parentTable,String parentColumn,List<String> columns,
        List<String> parentColumns,String onDelete,String onUpdate,boolean deferred) {
    public ForeignKey {
        columns=List.copyOf(columns); parentColumns=List.copyOf(parentColumns);
    }
    public ForeignKey(String column,String table,String parentColumn) {
        this(column,table,parentColumn,column==null ? List.of() : List.of(column),
                parentColumn==null ? List.of() : List.of(parentColumn),"NO ACTION","NO ACTION",false);
    }
    static ForeignKey reference(String table,List<String> parents,ReferenceOptions options) {
        return new ForeignKey(null,table,parents.isEmpty() ? null : parents.get(0),List.of(),parents,
                options.onDelete,options.onUpdate,false);
    }
    ForeignKey withColumns(List<String> columns) { return renamed(columns,parentTable,parentColumns); }
    ForeignKey renamed(List<String> children,String table,List<String> parents) {
        return new ForeignKey(children.isEmpty() ? null : children.get(0),table,parents.isEmpty() ? null : parents.get(0),
                children,parents,onDelete,onUpdate,deferred);
    }
    ForeignKey withDeferred(boolean value) {
        return new ForeignKey(column,parentTable,parentColumn,columns,parentColumns,onDelete,onUpdate,value);
    }
}
