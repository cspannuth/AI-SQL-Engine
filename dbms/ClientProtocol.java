import java.util.Map;

/** Common lifecycle for the read-only tools and the explicit local workspace. */
interface ClientProtocol {
    Map<String, Object> handle(Object input);
    boolean isClosed();
}
