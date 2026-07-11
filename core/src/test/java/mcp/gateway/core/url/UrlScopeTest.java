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

    @Test
    void rejectsAmbiguousEncodedTraversalAndBackslashes() {
        UrlScope scope = UrlScope.parse("https://target/app");

        assertFalse(scope.contains("https://target/app/%2e%2e%2fsecret"));
        assertFalse(scope.contains("https://target/app/%2f..%2fsecret"));
        assertFalse(scope.contains("https://target/app/%2e%2e%5csecret"));
        assertFalse(scope.contains("https://target/app/%252e%252e/secret"));
        assertFalse(scope.contains("https://target/app/%252e%252e/../secret"));
        assertFalse(scope.contains("https://target/app/%c0%ae%c0%ae/secret"));
        assertFalse(scope.contains("https://target/app/%c0%ae%c0%ae/../secret"));
        assertFalse(scope.contains("https://target/app/%2e%2e/../secret"));
        assertFalse(scope.contains("https://target/app/%5c/../secret"));
        assertFalse(scope.contains("https://target/app/%00/../secret"));
        assertFalse(scope.contains("https://target/app/%2f../../secret"));
        assertThrows(IllegalArgumentException.class,
                () -> UrlScope.parse("https://target/app/%252e%252e/../secret"));
        assertThrows(IllegalArgumentException.class,
                () -> UrlScope.parse("https://target/app/%c0%ae%c0%ae/../secret"));
        assertTrue(scope.contains("https://target/app/section/../child"));
        assertTrue(scope.contains("https://target/app/%2fchild"));

        UrlScope normalizedBase = UrlScope.parse("https://target/app/child/..");
        assertTrue(normalizedBase.contains("https://target/app/page"));
    }

    @Test
    void rejectsUserInfoInBaseAndCandidateUrls() {
        UrlScope scope = UrlScope.parse("https://target/app");

        assertFalse(scope.contains("https://user:password@target/app"));
        assertThrows(IllegalArgumentException.class,
                () -> UrlScope.parse("https://user:password@target/app"));
    }

    @Test
    void acceptsPunycodeButRejectsRawUnicodeHostAmbiguity() {
        UrlScope asciiScope = UrlScope.parse("https://xn--bcher-kva.example/app");

        assertTrue(asciiScope.contains("https://XN--BCHER-KVA.EXAMPLE./app"));
        assertFalse(asciiScope.contains("https://BÜCHER.EXAMPLE/app"));
        assertThrows(IllegalArgumentException.class,
                () -> UrlScope.parse("https://bücher.example/app"));
        assertFalse(UrlScope.parse("https://fass.de/app").contains("https://faß.de/app"));
    }

    @Test
    void rejectsAmbiguousOrOutOfRangePorts() {
        UrlScope scope = UrlScope.parse("https://target/app");

        assertFalse(scope.contains("https://target:+443/app"));
        assertFalse(scope.contains("https://target:65536/app"));
    }
}
