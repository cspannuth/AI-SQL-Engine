/** One SELECT expression, alias, or qualified/unqualified wildcard. */
public class SelectItem {
    public final CompareTerm expression;
    public final String alias;
    public final boolean wildcard;
    public final String qualifier;

    public SelectItem(CompareTerm expression, String alias) {
        this.expression = expression;
        this.alias = alias;
        this.wildcard = false;
        this.qualifier = null;
    }

    private SelectItem(String qualifier) {
        expression = null;
        alias = null;
        wildcard = true;
        this.qualifier = qualifier;
    }

    public static SelectItem star(String qualifier) { return new SelectItem(qualifier); }
    public String label() {
        if (alias != null) { return alias; }
        if (expression instanceof Column) { return ((Column) expression).getName(); }
        return expression.toString();
    }
}
