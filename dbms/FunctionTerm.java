import java.math.*;
import java.util.*;

/** Small scalar-function library with explicit arities and null handling. */
final class FunctionTerm extends CompositeTerm {
    private final String name;
    FunctionTerm(String name,List<CompareTerm> values) {
        super(values);
        this.name = name;
        int min = 1, max = 1;
        switch (name) {
            case "COALESCE": case "MIN": case "MAX": min = 2; max = Integer.MAX_VALUE; break;
            case "IFNULL": case "NULLIF": case "INSTR": min = 2; max = 2; break;
            case "IIF": case "REPLACE": min = 3; max = 3; break;
            case "SUBSTR": case "SUBSTRING": min = 2; max = 3; break;
            case "ROUND": case "TRIM": case "LTRIM": case "RTRIM": max = 2; break;
            case "DATE": case "TIME": case "DATETIME": case "JULIANDAY": case "UNIXEPOCH": min = 0; max = Integer.MAX_VALUE; break;
            case "STRFTIME": min = 1; max = Integer.MAX_VALUE; break;
            case "ABS": case "LENGTH": case "LOWER": case "UPPER": case "TYPEOF": case "QUOTE": break;
            default: throw new IllegalArgumentException("Unknown function: " + name);
        }
        if (values.size() < min || values.size() > max) { throw new IllegalArgumentException("Wrong number of arguments: " + name); }
    }
    public Object getValue() {
        if (name.equals("COALESCE") || name.equals("IFNULL")) {
            for (CompareTerm expression : arguments) { Object value = expression.getValue(); if (value != null) { return value; } }
            return null;
        }
        if (name.equals("IIF")) {
            Object test = arguments.get(0).getValue();
            return arguments.get(test != null && SqlValues.number(test).doubleValue()!=0 ? 1 : 2).getValue();
        }
        List<Object> values = new ArrayList<>();
        for (CompareTerm argument : arguments) { values.add(argument.getValue()); }
        if (DateFunctions.supports(name)) { return DateFunctions.evaluate(name,values); }
        Object first = values.get(0);
        if (name.equals("TYPEOF")) { return first == null ? "null" : first instanceof Double ? "real" : first instanceof Number ? "integer" : "text"; }
        if (name.equals("QUOTE")) { return first == null ? "NULL" : first instanceof String ? "'"+first.toString().split("\u0000",-1)[0].replace("'","''")+"'" : first.toString(); }
        if (name.equals("NULLIF")) { return first != null && values.get(1)!=null && SqlValues.compare(first,values.get(1))==0 ? null : first; }
        if (values.contains(null)) { return null; }
        String text = first.toString();
        switch (name) {
            case "ABS":
                Number number = SqlValues.number(first);
                if (first instanceof Integer || first instanceof Long) {
                    if (number.longValue()==Long.MIN_VALUE) { throw new IllegalArgumentException("Integer overflow in ABS"); }
                    return Math.abs(number.longValue());
                }
                return Math.abs(number.doubleValue());
            case "LENGTH":
                int nul = text.indexOf('\0');
                return (long)text.codePointCount(0,nul < 0 ? text.length() : nul);
            case "LOWER": case "UPPER":
                StringBuilder folded = new StringBuilder(text);
                for (int i=0;i<folded.length();i++) {
                    char c=folded.charAt(i);
                    if (name.equals("LOWER") && c>='A' && c<='Z') { folded.setCharAt(i,(char)(c+32)); }
                    if (name.equals("UPPER") && c>='a' && c<='z') { folded.setCharAt(i,(char)(c-32)); }
                }
                return folded.toString();
            case "ROUND":
                int places = values.size()==1 ? 0 : (int)Math.max(0,Math.min(30,SqlValues.number(values.get(1)).longValue()));
                return BigDecimal.valueOf(SqlValues.number(first).doubleValue()).setScale(places,RoundingMode.HALF_UP).doubleValue();
            case "SUBSTR": case "SUBSTRING": return substring(text,values);
            case "TRIM": case "LTRIM": case "RTRIM":
                Set<Integer> chars = new HashSet<>();
                (values.size()==1 ? " " : values.get(1).toString()).codePoints().forEach(chars::add);
                int[] points=text.codePoints().toArray();
                int start=0,end=points.length;
                if (!name.equals("RTRIM")) { while(start<end && chars.contains(points[start])) { start++; } }
                if (!name.equals("LTRIM")) { while(end>start && chars.contains(points[end-1])) { end--; } }
                return new String(points,start,end-start);
            case "INSTR":
                int position = text.indexOf(values.get(1).toString());
                return position<0 ? 0L : (long)text.codePointCount(0,position)+1;
            case "REPLACE":
                String needle=values.get(1).toString(), replacement=values.get(2).toString();
                long occurrences=0;
                if (!needle.isEmpty()) { for(int at=0;(at=text.indexOf(needle,at))>=0;at+=needle.length()) { occurrences++; } }
                QueryBudget.checkValueSize(QueryBudget.payloadBytes(text)+occurrences*
                        (QueryBudget.payloadBytes(replacement)-QueryBudget.payloadBytes(needle)));
                return needle.isEmpty() ? text : text.replace(needle,replacement);
            case "MIN": case "MAX":
                Object extreme=first;
                for (Object value : values) { if (name.equals("MIN") ? SqlValues.compare(value,extreme)<0 : SqlValues.compare(value,extreme)>0) { extreme=value; } }
                return extreme;
            default: throw new IllegalStateException("Unimplemented function: "+name);
        }
    }
    private static String substring(String text,List<Object> values) {
        int[] points=text.codePoints().toArray();
        long position=SqlValues.number(values.get(1)).longValue();
        long start=position>0 ? position-1 : position<0 ? points.length+position : 0;
        long length=values.size()==3 ? SqlValues.number(values.get(2)).longValue() : points.length;
        if (position==0 && length>0) { length--; }
        if (length<0) { start = start < Long.MIN_VALUE-length ? Long.MIN_VALUE : start+length; length = length==Long.MIN_VALUE ? Long.MAX_VALUE : -length; }
        long end=length>Long.MAX_VALUE-Math.max(0,start) ? Long.MAX_VALUE : start+length;
        int from=(int)Math.max(0,Math.min(points.length,start)), to=(int)Math.max(0,Math.min(points.length,end));
        return new String(points,from,Math.max(0,to-from));
    }
    public SqlType getResultType() {
        switch(name) {
            case "LENGTH": case "INSTR": case "UNIXEPOCH": return SqlType.INTEGER;
            case "ROUND": case "JULIANDAY": return SqlType.REAL;
            case "ABS": return SqlType.NUMERIC;
            case "MIN": case "MAX": return commonType(arguments);
            case "NULLIF": return arguments.get(0).getResultType();
            case "IFNULL": case "COALESCE": return commonType(arguments);
            case "IIF": return commonType(arguments.subList(1,3));
            default: return SqlType.TEXT;
        }
    }
    public String toString() {
        StringJoiner result = new StringJoiner(", ",name+"(",")");
        for (CompareTerm argument : arguments) { result.add(argument.toString()); }
        return result.toString();
    }
}
