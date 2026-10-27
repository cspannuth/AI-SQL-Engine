/** LIMIT count OFFSET offset and the compatible LIMIT offset,count form. */
public class Limit {
    private final long offset;
    private final long max;

    public Limit(Number offset, Number max) {
        if (max == null) { throw new IllegalArgumentException("LIMIT requires a count"); }
        // SQLite treats negative offsets as zero and negative counts as unbounded.
        this.offset = offset == null ? 0 : Math.max(0, offset.longValue());
        this.max = max.longValue() < 0 ? Long.MAX_VALUE : max.longValue();
    }

    public long getOffset() { return offset; }
    public long getMax() { return max; }
}
