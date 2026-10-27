import java.io.*;
import java.util.Arrays;

/** A DataOutputStream whose encoded bytes can be inspected. */
public class RowOutput extends DataOutputStream {
    private final ByteArrayOutputStream bytes;

    public RowOutput() { this(new ByteArrayOutputStream()); }

    private RowOutput(ByteArrayOutputStream bytes) {
        super(bytes);
        this.bytes = bytes;
    }

    public byte[] getBufferBytes() { return bytes.toByteArray(); }
    public int getBufferLength() { return bytes.size(); }
    public String toString() { return Arrays.toString(getBufferBytes()); }
}
