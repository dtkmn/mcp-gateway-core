package mcp.gateway.core.url;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class UrlScopeTest {

    @Test
    void containsSameOriginAndPathBoundaryOnly() {
        UrlScope scope = UrlScope.parse("https://target/app");

        assertTrue(scope.contains("https://target/app"));
        assertTrue(scope.contains("https://target/app/page"));
        assertFalse(scope.contains("https://target/app2"));
        assertFalse(scope.contains("https://target.evil/app"));
        assertFalse(scope.contains("https://target/app/%2e%2e/secret"));
    }

    @Test
    void normalizesDefaultPortsAndTrailingSlash() {
        UrlScope scope = UrlScope.parse("https://target:443/app/");

        assertTrue(scope.contains("https://target/app"));
        assertTrue(scope.contains("https://target/app/deeper"));
        assertFalse(scope.contains("http://target:80/app"));
    }

    @Test
    void rejectsMalformedScopeAndCandidateUrls() {
        IllegalArgumentException thrown = assertThrows(
                IllegalArgumentException.class,
                () -> UrlScope.parse("not-a-url"));

        assertTrue(thrown.getMessage().contains("absolute URL"));
        assertFalse(UrlScope.parse("https://target").contains("not-a-url"));
    }
}
