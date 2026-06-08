package mcp.gateway.core;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class ProjectDocumentationAndSecurityToolingTest {

    @Test
    void readmeLinksModuleRoadmapSecurityAndReleaseDocs() throws IOException {
        String readme = Files.readString(Path.of("README.md"));

        assertTrue(readme.contains("docs/MODULES.md"));
        assertTrue(readme.contains("docs/ROADMAP.md"));
        assertTrue(readme.contains("SECURITY.md"));
        assertTrue(readme.contains("docs/RELEASE_POLICY.md"));
        assertTrue(readme.contains("docs/COMPATIBILITY.md"));
        assertTrue(readme.contains("core/"));
        assertTrue(readme.contains("adapters/spring-webflux/"));
    }

    @Test
    void moduleMapDocumentsEveryPublicContractFamily() throws IOException {
        String moduleMap = Files.readString(Path.of("docs/MODULES.md"));

        for (String packageName : new String[] {
                "mcp.gateway.core.invocation",
                "mcp.gateway.core.tool",
                "mcp.gateway.core.context",
                "mcp.gateway.core.authz",
                "mcp.gateway.core.policy",
                "mcp.gateway.core.policybundle",
                "mcp.gateway.core.audit",
                "mcp.gateway.core.protection",
                "mcp.gateway.core.rate",
                "mcp.gateway.core.logging",
                "mcp.gateway.core.url",
                "mcp.gateway.spring.webflux"
        }) {
            assertTrue(moduleMap.contains(packageName), () -> "Missing module-map entry for " + packageName);
        }
        assertTrue(moduleMap.contains("The `mcp-gateway-core` artifact must remain JDK-only."));
        assertTrue(moduleMap.contains("separate adapter artifacts"));
    }

    @Test
    void roadmapKeepsStableApiClaimsBehindProofGates() throws IOException {
        String roadmap = Files.readString(Path.of("docs/ROADMAP.md"));

        assertTrue(roadmap.contains("Public Preview"));
        assertTrue(roadmap.contains("Graduation Criteria For Stable API"));
        assertTrue(roadmap.contains("at least two downstream consumers"));
        assertTrue(roadmap.contains("This repository is not trying to become"));
    }

    @Test
    void githubNativeSecurityToolingIsConfigured() throws IOException {
        String dependabot = Files.readString(Path.of(".github/dependabot.yml"));
        String codeql = Files.readString(Path.of(".github/workflows/codeql.yml"));
        String security = Files.readString(Path.of("SECURITY.md"));
        List<Path> workflows;
        try (var files = Files.list(Path.of(".github/workflows"))) {
            workflows = files
                    .filter(path -> path.toString().endsWith(".yml"))
                    .sorted()
                    .toList();
        }

        assertTrue(dependabot.contains("package-ecosystem: \"github-actions\""));
        assertTrue(dependabot.contains("package-ecosystem: \"gradle\""));
        assertTrue(codeql.contains("github/codeql-action/init@v4"));
        assertTrue(codeql.contains("build-mode: manual"));
        assertTrue(codeql.contains("./gradlew clean test --no-daemon --stacktrace"));
        assertTrue(security.contains("Dependabot version updates"));
        assertTrue(security.contains("CodeQL Java analysis"));
        assertTrue(security.contains("Snyk or another external scanner can be added later"));

        for (Path workflow : workflows) {
            String content = Files.readString(workflow);
            assertTrue(content.contains("actions/checkout@v6"), () -> workflow + " should use current checkout action");
            assertTrue(!content.contains("actions/checkout@v5"), () -> workflow + " should not use stale checkout action");
        }
    }
}
