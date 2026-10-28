import java.util.*;

public record CteDefinition(String name, List<String> columns, QueryStatement query) {
    public CteDefinition { columns = List.copyOf(columns); }
}
