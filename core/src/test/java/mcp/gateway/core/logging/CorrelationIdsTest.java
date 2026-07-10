package mcp.gateway.core.logging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class CorrelationIdsTest {

    @Test
    void sanitizesOnlyBoundedSafeCorrelationIds() {
        assertEquals("corr-123", CorrelationIds.sanitize(" corr-123 "));
        assertEquals("tenant/service:request_1.2", CorrelationIds.sanitize("tenant/service:request_1.2"));
        assertNull(CorrelationIds.sanitize(null));
        assertNull(CorrelationIds.sanitize("   "));
        assertNull(CorrelationIds.sanitize("bad value"));
        assertNull(CorrelationIds.sanitize("bad\nvalue"));
        assertNull(CorrelationIds.sanitize("a".repeat(129)));
    }

    @Test
    void resolvesPrimaryHeaderBeforeLegacyHeader() {
        assertEquals("primary", CorrelationIds.resolve("primary", "legacy"));
        assertEquals("legacy", CorrelationIds.resolve("  ", "legacy"));
    }

    @Test
    void generatesUuidWhenHeadersAreMissingOrUnsafe() {
        String generated = CorrelationIds.resolve("bad value", "");

        assertNotNull(generated);
        assertNotNull(CorrelationIds.sanitize(generated));
        UUID.fromString(generated);
    }
}
