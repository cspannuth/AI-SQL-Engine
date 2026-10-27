import java.util.*;

/** COUNT, SUM, AVG, TOTAL, MIN, and MAX with one independent state per group. */
public class AggregateTerm extends CompareTerm {
    private final String function;
    private final CompareTerm argument;
    private final boolean distinct;
    private Object value;

    public AggregateTerm(String function, CompareTerm argument, boolean distinct) {
        this.function = function.toUpperCase(Locale.ROOT);
        this.argument = argument;
        this.distinct = distinct;
        if (!Set.of("COUNT", "SUM", "AVG", "TOTAL", "MIN", "MAX").contains(this.function)) {
            throw new IllegalArgumentException("Unknown aggregate: " + function);
        }
        if (argument == null && (!this.function.equals("COUNT") || distinct)) {
            throw new IllegalArgumentException("Only COUNT(*) accepts '*'");
        }
        if (argument != null && !argument.getAggregates().isEmpty()) {
            throw new IllegalArgumentException("Aggregate functions cannot be nested");
        }
    }

    public Object getValue() { return value; }
    public SqlType getResultType() {
        if (function.equals("COUNT")) { return SqlType.INTEGER; }
        if (function.equals("AVG") || function.equals("TOTAL")) { return SqlType.REAL; }
        if (isExtremum()) { return argument.getResultType(); }
        return SqlType.NUMERIC;
    }
    public void useState(State state) { value = state.finish(); }
    public List<Column> getColumns() { return argument == null ? List.of() : argument.getColumns(); }
    public List<AggregateTerm> getAggregates() { return List.of(this); }
    public String toString() {
        return function + "(" + (distinct ? "DISTINCT " : "") + (argument == null ? "*" : argument) + ")";
    }

    public State newState() { return new State(); }
    public boolean isExtremum() { return function.equals("MIN") || function.equals("MAX"); }

    public class State {
        private final Set<Object> seen = distinct ? new HashSet<>() : null;
        private long count;
        private long integerSum;
        private double realSum;
        private boolean real;
        private Object extreme;
        private boolean extremeChanged;

        public boolean changedRepresentative() { return extremeChanged; }

        public void addCurrentRow() {
            extremeChanged = false;
            Object current = argument == null ? 1L : argument.getValue();
            if (current == null) { return; }
            if (seen != null) {
                if (!seen.add(SqlValues.key(current))) { return; }
                QueryBudget.reserveBytes(QueryBudget.payloadBytes(current));
            }
            count++;
            if (function.equals("COUNT")) { return; }
            if (function.equals("MIN") || function.equals("MAX")) {
                int order = extreme == null ? 0 : SqlValues.compare(current, extreme);
                if (extreme == null || (function.equals("MIN") ? order < 0 : order > 0)) {
                    extreme = current;
                    QueryBudget.reserveBytes(QueryBudget.payloadBytes(current));
                    extremeChanged = true;
                }
                return;
            }
            Number number = SqlValues.number(current);
            realSum += number.doubleValue();
            if (number instanceof Double) { real = true; }
            if (!real) {
                try { integerSum = Math.addExact(integerSum, number.longValue()); }
                catch (ArithmeticException overflow) {
                    if (function.equals("SUM")) { throw new IllegalArgumentException("Integer overflow in SUM"); }
                    real = true;
                }
            }
        }

        public Object finish() {
            switch (function) {
                case "COUNT": return count;
                case "MIN": case "MAX": return extreme;
                case "TOTAL": return realSum;
                case "AVG": return count == 0 ? null : realSum / count;
                case "SUM":
                    if (count == 0) { return null; }
                    if (real) { return realSum; }
                    return integerSum;
                default: throw new IllegalStateException("Unknown aggregate");
            }
        }
    }
}
