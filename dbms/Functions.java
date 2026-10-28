import java.util.*;

/** Dispatch and validate function calls at parse time, including on empty tables. */
final class Functions {
    static CompareTerm call(String name,List<CompareTerm> values,boolean distinct) {
        String function = name.toUpperCase(Locale.ROOT);
        boolean aggregate = Set.of("COUNT","SUM","AVG","TOTAL").contains(function)
                || Set.of("MIN","MAX").contains(function) && values.size() == 1;
        if (aggregate) {
            if (values.size() > 1 || values.isEmpty() && !function.equals("COUNT")) {
                throw new IllegalArgumentException("Invalid aggregate arity: " + name);
            }
            return new AggregateTerm(function,values.isEmpty() ? null : values.get(0),distinct);
        }
        if (distinct) { throw new IllegalArgumentException("DISTINCT requires an aggregate"); }
        return new FunctionTerm(function,values);
    }
}
