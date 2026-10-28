import java.util.*;

/** Both CASE forms evaluate only the selected result branch. */
final class CaseTerm extends CompositeTerm {
    private final CompareTerm base;
    private final List<CompareTerm> branches;
    private final CompareTerm otherwise;
    CaseTerm(CompareTerm base, List<CompareTerm> branches, CompareTerm otherwise) {
        super(children(base,branches,otherwise));
        this.base = base;
        this.branches = List.copyOf(branches);
        this.otherwise = otherwise;
    }
    private static List<CompareTerm> children(CompareTerm base,List<CompareTerm> branches,CompareTerm otherwise) {
        List<CompareTerm> result = new ArrayList<>(branches);
        if (base != null) { result.add(base); }
        result.add(otherwise);
        return result;
    }
    public Object getValue() {
        Object value = base == null ? null : base.getValue();
        for (int i = 0; i < branches.size(); i += 2) {
            Object test = branches.get(i).getValue();
            boolean match;
            if (base == null) { match = test != null && SqlValues.number(test).doubleValue() != 0; }
            else {
                CompareTerm cached = new CompareTerm(value) { public SqlType getAffinity() { return base.getAffinity(); } };
                CompareTerm branch=branches.get(i);
                CompareTerm candidate=new CompareTerm(test) { public SqlType getAffinity() { return branch.getAffinity(); } };
                Object[] compared = SqlValues.comparisonValues(cached,candidate);
                match = compared[0] != null && compared[1] != null && SqlValues.compare(compared[0],compared[1]) == 0;
            }
            if (match) { return branches.get(i+1).getValue(); }
        }
        return otherwise.getValue();
    }
    public SqlType getResultType() {
        List<CompareTerm> values = new ArrayList<>();
        for (int i = 1; i < branches.size(); i += 2) { values.add(branches.get(i)); }
        values.add(otherwise);
        return commonType(values);
    }
    public String toString() { return "CASE expression"; }
}
