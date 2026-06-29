package mcp.gateway.spring.webflux;

import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;

/**
 * Extracts granted OAuth-style scopes from a Spring authentication object.
 */
@FunctionalInterface
public interface McpGrantedScopesExtractor {
    /**
     * Extracts granted scopes.
     *
     * @param authentication Spring authentication, or {@code null}
     * @return non-null normalized scopes; return an empty list when no scopes apply
     */
    List<String> extract(Authentication authentication);

    /**
     * Extracts authorities with the {@code SCOPE_} prefix.
     *
     * @return default Spring Security scope extractor
     */
    static McpGrantedScopesExtractor springSecurityScopes() {
        return authentication -> {
            if (authentication == null || authentication.getAuthorities() == null) {
                return List.of();
            }
            return authentication.getAuthorities().stream()
                    .map(GrantedAuthority::getAuthority)
                    .filter(authority -> authority != null && authority.startsWith("SCOPE_"))
                    .map(authority -> authority.substring("SCOPE_".length()))
                    .map(scope -> scope.toLowerCase(Locale.ROOT))
                    .distinct()
                    .collect(Collectors.toList());
        };
    }
}
