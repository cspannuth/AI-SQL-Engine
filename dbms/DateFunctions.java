import java.time.*;
import java.time.format.*;
import java.util.*;
import java.util.regex.*;

/** UTC ISO date/time functions. Unsupported timezone/subsecond modifiers fail explicitly. */
final class DateFunctions {
    private static final Set<String> NAMES=Set.of("DATE","TIME","DATETIME","STRFTIME","JULIANDAY","UNIXEPOCH");
    private static final Pattern SHIFT=Pattern.compile("([+-]?[0-9]+(?:\\.[0-9]+)?)\\s+(days?|hours?|minutes?|seconds?|months?|years?)");
    private static final ThreadLocal<Boolean> SCHEMA=ThreadLocal.withInitial(()->false);
    static boolean supports(String name) { return NAMES.contains(name); }
    static QueryContext.Scope checkingSchema() {
        boolean before=SCHEMA.get(); SCHEMA.set(true); return ()->SCHEMA.set(before);
    }
    static Object evaluate(String name,List<Object> arguments) {
        if (arguments.contains(null)) { return null; }
        int offset=name.equals("STRFTIME") ? 1 : 0;
        Object source=arguments.size()>offset ? arguments.get(offset) : "now";
        List<Object> modifiers=arguments.size()>offset+1 ? arguments.subList(offset+1,arguments.size()) : List.of();
        boolean unix=!modifiers.isEmpty() && modifiers.get(0).toString().equalsIgnoreCase("unixepoch");
        try {
            LocalDateTime value=parse(source,unix);
            if (value==null) { return null; }
            for (int i=unix ? 1 : 0;i<modifiers.size();i++) {
                String modifier=modifiers.get(i).toString().toLowerCase(Locale.ROOT).trim();
                if (Set.of("localtime","utc","auto","subsec","subsecond","floor","ceiling").contains(modifier)) {
                    throw new IllegalArgumentException("Unsupported date modifier: "+modifier);
                }
                if (modifier.equals("start of day")) { value=value.toLocalDate().atStartOfDay(); continue; }
                if (modifier.equals("start of month")) { value=value.toLocalDate().withDayOfMonth(1).atStartOfDay(); continue; }
                if (modifier.equals("start of year")) { value=value.toLocalDate().withDayOfYear(1).atStartOfDay(); continue; }
                Matcher shift=SHIFT.matcher(modifier);
                if (!shift.matches()) { return null; }
                double amount=Double.parseDouble(shift.group(1));
                String unit=shift.group(2);
                if (unit.startsWith("month") || unit.startsWith("year")) {
                    if (amount!=Math.rint(amount)) { throw new IllegalArgumentException("Fractional month/year shifts are unsupported"); }
                    int day=value.getDayOfMonth();
                    value=value.withDayOfMonth(1);
                    value=unit.startsWith("month") ? value.plusMonths((long)amount) : value.plusYears((long)amount);
                    value=value.plusDays(day-1);
                } else {
                    double seconds=amount*(unit.startsWith("day") ? 86400 : unit.startsWith("hour") ? 3600 : unit.startsWith("minute") ? 60 : 1);
                    if (!Double.isFinite(seconds) || Math.abs(seconds)>315576000000.0) { return null; }
                    long whole=(long)Math.floor(seconds);
                    value=value.plusSeconds(whole).plusNanos(Math.round((seconds-whole)*1_000_000_000));
                }
            }
            if (value.getYear()<0 || value.getYear()>9999) { return null; }
            switch(name) {
                case "DATE": return value.toLocalDate().format(DateTimeFormatter.ISO_LOCAL_DATE);
                case "TIME": return value.format(DateTimeFormatter.ofPattern("HH:mm:ss",Locale.ROOT));
                case "DATETIME": return value.format(DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss",Locale.ROOT));
                case "UNIXEPOCH": return value.toEpochSecond(ZoneOffset.UTC);
                case "JULIANDAY": return value.toInstant(ZoneOffset.UTC).toEpochMilli()/86400000.0+2440587.5;
                case "STRFTIME": return format(arguments.get(0).toString(),value);
                default: throw new IllegalStateException("Unknown date function");
            }
        } catch (DateTimeException | NumberFormatException | ArithmeticException invalid) { return null; }
    }
    private static LocalDateTime parse(Object source,boolean unix) {
        String text=source.toString().trim();
        if (text.equalsIgnoreCase("now")) {
            if (SCHEMA.get()) { throw new IllegalArgumentException("Non-deterministic date function in CHECK"); }
            return LocalDateTime.ofInstant(QueryContext.now(),ZoneOffset.UTC);
        }
        if (unix) { return LocalDateTime.ofInstant(Instant.ofEpochMilli(Math.round(Double.parseDouble(text)*1000)),ZoneOffset.UTC); }
        if (source instanceof Number || text.matches("[+-]?[0-9]+(?:\\.[0-9]+)?")) {
            double julian=Double.parseDouble(text);
            if (julian<0 || julian>=5373484.5) { return null; }
            return LocalDateTime.ofInstant(Instant.ofEpochMilli(Math.round((julian-2440587.5)*86400000)),ZoneOffset.UTC);
        }
        if (text.matches("[0-9]{2}:[0-9]{2}.*")) { return LocalDate.of(2000,1,1).atTime(LocalTime.parse(text)); }
        if (text.length()==10) { return LocalDate.parse(text).atStartOfDay(); }
        if (text.length()<10) { return null; }
        text=text.replaceFirst(" +","T");
        if (text.endsWith("Z") || text.substring(10).matches(".*[+-][0-9]{2}:[0-9]{2}$")) {
            return OffsetDateTime.parse(text).withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime();
        }
        return LocalDateTime.parse(text);
    }
    private static String format(String pattern,LocalDateTime value) {
        StringBuilder result=new StringBuilder();
        for (int i=0;i<pattern.length();i++) {
            char c=pattern.charAt(i);
            if(c!='%') { result.append(c); continue; }
            if(++i==pattern.length()) { return null; }
            switch(pattern.charAt(i)) {
                case '%': result.append('%'); break;
                case 'Y': result.append(String.format(Locale.ROOT,"%04d",value.getYear())); break;
                case 'm': result.append(String.format(Locale.ROOT,"%02d",value.getMonthValue())); break;
                case 'd': result.append(String.format(Locale.ROOT,"%02d",value.getDayOfMonth())); break;
                case 'H': result.append(String.format(Locale.ROOT,"%02d",value.getHour())); break;
                case 'M': result.append(String.format(Locale.ROOT,"%02d",value.getMinute())); break;
                case 'S': result.append(String.format(Locale.ROOT,"%02d",value.getSecond())); break;
                case 'f': result.append(String.format(Locale.ROOT,"%06.3f",value.getSecond()+value.getNano()/1e9)); break;
                case 'j': result.append(String.format(Locale.ROOT,"%03d",value.getDayOfYear())); break;
                case 'w': result.append(value.getDayOfWeek().getValue()%7); break;
                case 'W': result.append(String.format(Locale.ROOT,"%02d",(value.getDayOfYear()+6-value.getDayOfWeek().getValue()+1)/7)); break;
                case 's': result.append(value.toEpochSecond(ZoneOffset.UTC)); break;
                case 'J': result.append(java.math.BigDecimal.valueOf(value.toInstant(ZoneOffset.UTC).toEpochMilli()/86400000.0+2440587.5)
                        .round(new java.math.MathContext(16)).stripTrailingZeros().toPlainString()); break;
                default: return null;
            }
        }
        return result.toString();
    }
}
