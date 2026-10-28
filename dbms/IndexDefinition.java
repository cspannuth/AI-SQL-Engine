/** A persisted single-column SQL index backed by a Berkeley DB secondary database. */
public record IndexDefinition(String name, String table, String column, boolean unique) {
    String dbName() { return name + ".idx.db"; }
}
