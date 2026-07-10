package mcp.gateway.spring.webflux;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

class McpGrantedScopesExtractorTest {

    @Test
    void ignoresAuthoritiesUntilAuthenticationIsTrusted() {
        TestingAuthenticationToken untrusted = new TestingAuthenticationToken(
                "client",
                "credentials",
                "SCOPE_demo:run"
        );
        untrusted.setAuthenticated(false);

        assertEquals(List.of(), McpGrantedScopesExtractor.springSecurityScopes().extract(untrusted));
    }

    @Test
    void ignoresScopesAttachedToAnonymousAuthentication() {
        AnonymousAuthenticationToken anonymous = new AnonymousAuthenticationToken(
                "key",
                "anonymous",
                List.of(new SimpleGrantedAuthority("SCOPE_demo:run"))
        );

        assertEquals(List.of(), McpGrantedScopesExtractor.springSecurityScopes().extract(anonymous));
    }

    @Test
    void extractsNormalizedDistinctNonBlankScopesFromTrustedAuthentication() {
        Authentication authentication = new UsernamePasswordAuthenticationToken(
                "client",
                "credentials",
                List.of(
                        new SimpleGrantedAuthority("SCOPE_ Demo:Run "),
                        new SimpleGrantedAuthority("SCOPE_demo:run"),
                        new SimpleGrantedAuthority("SCOPE_"),
                        new SimpleGrantedAuthority("ROLE_USER")
                )
        );

        assertEquals(
                List.of("demo:run"),
                McpGrantedScopesExtractor.springSecurityScopes().extract(authentication)
        );
    }
}
