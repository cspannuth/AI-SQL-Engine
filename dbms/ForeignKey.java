/** A single-column reference. A null parent column means the parent's primary key. */
public record ForeignKey(String column, String parentTable, String parentColumn) { }
