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
        assertTrue(readme.contains("docs/GETTING_STARTED.md"));
        assertTrue(readme.contains("docs/CONTRACT_REFERENCE.md"));
        assertTrue(readme.contains("https://danieltse.org/mcp-gateway-core/"));
        assertTrue(readme.contains("docs-site/"));
        assertTrue(readme.contains("core/"));
        assertTrue(readme.contains("adapters/spring-webflux/"));
    }

    @Test
    void astroDocsSiteIsConfiguredForGithubPages() throws IOException {
        String packageJson = Files.readString(Path.of("docs-site/package.json"));
        String astroConfig = Files.readString(Path.of("docs-site/astro.config.mjs"));
        String pagesWorkflow = Files.readString(Path.of(".github/workflows/pages.yml"));
        String syncScript = Files.readString(Path.of("docs-site/scripts/sync-docs.mjs"));

        assertTrue(packageJson.contains("\"@astrojs/starlight\""));
        assertTrue(packageJson.contains("\"prebuild\": \"node ./scripts/sync-docs.mjs\""));
        assertTrue(astroConfig.contains("base: '/mcp-gateway-core'"));
        assertTrue(astroConfig.contains("site: 'https://danieltse.org'"));
        assertTrue(!astroConfig.contains("https://dtkmn.github.io"));
        assertTrue(astroConfig.contains("{ label: 'Overview', link: '/' }"));
        assertTrue(!astroConfig.contains("link: '/mcp-gateway-core/'"));
        assertTrue(pagesWorkflow.contains("withastro/action@v6"));
        assertTrue(pagesWorkflow.contains("actions/deploy-pages@v5"));
        assertTrue(pagesWorkflow.contains("node-version: 24"));
        assertTrue(syncScript.contains("docs/GETTING_STARTED.md"));
        assertTrue(syncScript.contains("docs/CONTRACT_REFERENCE.md"));
        assertTrue(syncScript.contains("editUrl"));
    }

    @Test
    void contractReferenceDocumentsFieldAndValueSemantics() throws IOException {
        String reference = Files.readString(Path.of("docs/CONTRACT_REFERENCE.md"));

        for (String required : new String[] {
                "McpToolInvocation",
                "GatewayToolExecutionContext",
                "McpToolAccessRule",
                "ToolAuthorizationDecision",
                "ToolPolicyDecision",
                "PolicyBundleRuleset",
                "GatewayAuditEvent",
                "McpAbuseProtectionDecision",
                "TokenBucketRateLimiter.Policy",
                "McpGatewayWebFluxProperties",
                "McpGatewayAuthorizationMode",
                "Runtime Responsibility"
        }) {
            assertTrue(reference.contains(required), () -> "Contract reference missing " + required);
        }
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
        String snyk = Files.readString(Path.of(".github/workflows/snyk.yml"));
        String security = Files.readString(Path.of("SECURITY.md"));
        String readme = Files.readString(Path.of("README.md"));
        String releasePolicy = Files.readString(Path.of("docs/RELEASE_POLICY.md"));
        List<Path> workflows;
        try (var files = Files.list(Path.of(".github/workflows"))) {
            workflows = files
                    .filter(path -> path.toString().endsWith(".yml"))
                    .sorted()
                    .toList();
        }

        assertTrue(dependabot.contains("package-ecosystem: \"github-actions\""));
        assertTrue(dependabot.contains("package-ecosystem: \"gradle\""));
        assertTrue(dependabot.contains("package-ecosystem: \"npm\""));
        assertTrue(dependabot.contains("directory: \"/docs-site\""));
        assertTrue(codeql.contains("github/codeql-action/init@v4"));
        assertTrue(codeql.contains("build-mode: manual"));
        assertTrue(codeql.contains("./gradlew clean test --no-daemon --stacktrace"));
        assertTrue(security.contains("Dependabot version updates"));
        assertTrue(security.contains("CodeQL Java analysis"));
        assertTrue(snyk.contains("snyk/actions/setup@v1.0.0"));
        assertTrue(snyk.contains("SNYK_TOKEN"));
        assertTrue(snyk.contains("SNYK_ORG"));
        assertTrue(snyk.contains("secrets.SNYK_ORG || vars.SNYK_ORG"));
        assertTrue(snyk.contains("SNYK_ORG secret or variable is not set"));
        assertTrue(snyk.contains("snyk_args=(--all-sub-projects"));
        assertTrue(snyk.contains("snyk_args+=(\"--org=${SNYK_ORG}\")"));
        assertTrue(snyk.contains("snyk test \"${snyk_args[@]}\""));
        assertTrue(snyk.contains("--sarif-file-output=snyk-open-source.sarif"));
        assertTrue(snyk.contains("github/codeql-action/upload-sarif@v4"));
        assertTrue(snyk.contains("actions/upload-artifact@v7"));
        assertTrue(snyk.contains("steps.snyk.outputs.exit_code != ''"));
        assertTrue(snyk.contains("exit \"${{ steps.snyk.outputs.exit_code }}\""));
        assertTrue(!snyk.contains("continue-on-error: true"));
        assertTrue(security.contains("If `SNYK_TOKEN` is missing, the workflow fails."));
        assertTrue(readme.contains("fails visibly when the token is absent"));
        assertTrue(releasePolicy.contains("does not upload artifacts to Central"));

        for (Path workflow : workflows) {
            String content = Files.readString(workflow);
            assertTrue(content.contains("actions/checkout@v6"), () -> workflow + " should use current checkout action");
            assertTrue(!content.contains("actions/checkout@v5"), () -> workflow + " should not use stale checkout action");
        }
    }
}
