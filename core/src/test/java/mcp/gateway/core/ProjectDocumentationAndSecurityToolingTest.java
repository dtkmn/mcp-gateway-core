package mcp.gateway.core;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class ProjectDocumentationAndSecurityToolingTest {
    private static final Pattern CHECKOUT_ACTION = Pattern.compile("actions/checkout@([^\\s\"']+)");

    @Test
    void readmeLinksModuleRoadmapSecurityAndReleaseDocs() throws IOException {
        String readme = Files.readString(Path.of("README.md"));

        assertTrue(readme.contains("docs/MODULES.md"));
        assertTrue(readme.contains("docs/ROADMAP.md"));
        assertTrue(readme.contains("SECURITY.md"));
        assertTrue(readme.contains("docs/RELEASE_POLICY.md"));
        assertTrue(readme.contains("docs/RELEASE_NOTES.md"));
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
        String indexPage = Files.readString(Path.of("docs-site/src/content/docs/index.md"));
        String pagesWorkflow = Files.readString(Path.of(".github/workflows/pages.yml"));
        String syncScript = Files.readString(Path.of("docs-site/scripts/sync-docs.mjs"));
        String verifyScript = Files.readString(Path.of("docs-site/scripts/verify-build-output.mjs"));

        assertTrue(packageJson.contains("\"@astrojs/starlight\""));
        assertTrue(packageJson.contains("\"prebuild\": \"node ./scripts/sync-docs.mjs\""));
        assertTrue(packageJson.contains("\"postbuild\": \"node ./scripts/verify-build-output.mjs\""));
        assertTrue(astroConfig.contains("base: '/mcp-gateway-core'"));
        assertTrue(astroConfig.contains("site: 'https://danieltse.org'"));
        assertTrue(!astroConfig.contains("https://dtkmn.github.io"));
        assertTrue(astroConfig.contains("baseUrl: 'https://github.com/dtkmn/mcp-gateway-core/edit/main/docs-site/'"));
        assertTrue(!astroConfig.contains("edit/main/docs-site/src/content/docs/"));
        assertTrue(astroConfig.contains("{ label: 'Overview', link: '/' }"));
        assertTrue(!astroConfig.contains("link: '/mcp-gateway-core/'"));
        assertTrue(indexPage.contains("link: guides/getting-started/"));
        assertTrue(indexPage.contains("[Getting started](guides/getting-started/)"));
        assertTrue(!indexPage.contains("link: /guides/"));
        assertTrue(!indexPage.contains("](/guides/"));
        assertTrue(!indexPage.contains("link: /mcp-gateway-core/"));
        assertTrue(!indexPage.contains("](/mcp-gateway-core/"));
        assertTrue(pagesWorkflow.contains("withastro/action@v6"));
        assertTrue(pagesWorkflow.contains("actions/deploy-pages@v5"));
        assertTrue(pagesWorkflow.contains("node-version: 24"));
        assertTrue(syncScript.contains("docs/GETTING_STARTED.md"));
        assertTrue(syncScript.contains("docs/CONTRACT_REFERENCE.md"));
        assertTrue(syncScript.contains("docs/RELEASE_NOTES.md"));
        assertTrue(astroConfig.contains("maintainers/release-notes"));
        assertTrue(indexPage.contains("[Release notes](maintainers/release-notes/)"));
        assertTrue(syncScript.contains("editUrl"));
        assertTrue(verifyScript.contains("https://danieltse.org"));
        assertTrue(verifyScript.contains("/mcp-gateway-core"));
        assertTrue(verifyScript.contains("home canonical"));
        assertTrue(verifyScript.contains("root-relative docs links"));
        assertTrue(verifyScript.contains("extractLocs"));
        assertTrue(verifyScript.contains("assertAllLocsUnderBase"));
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
                "batch_not_supported",
                "pass downstream unchanged",
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
                "mcp.gateway.core.governance",
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
        String ci = Files.readString(Path.of(".github/workflows/ci.yml"));
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
        assertTrue(security.contains("latest published `0.7.x` artifact line"));
        assertTrue(!security.contains("latest published `0.6.x` artifact"));
        assertTrue(!security.contains("latest published `0.5.x` artifact"));
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
        assertTrue(readme.contains("./gradlew verifyGatewayPublicPreviewPublication --no-daemon --stacktrace --warning-mode fail"));
        assertTrue(releasePolicy.contains("./gradlew verifyGatewayPublicPreviewPublication --no-daemon --stacktrace --warning-mode fail"));
        assertTrue(ci.contains("./gradlew verifyGatewayDevelopment --no-daemon --stacktrace --warning-mode fail"));
        assertTrue(ci.contains("./bin/java17-source-compat-0.6-consumer.sh"));
        assertTrue(ci.contains("./bin/java17-consumer-smoke.sh"));
        assertTrue(releasePolicy.contains("bin/java17-source-compat-0.6-consumer.sh"));
        assertTrue(releasePolicy.contains("separate clean downstream consumers"));
        assertTrue(releasePolicy.contains("temporary external Gradle project"));
        assertTrue(releasePolicy.contains("accepted API/binary deltas"));
        assertTrue(releasePolicy.contains("mcp.gateway.spring.webflux.*"));
        String releaseNotes = Files.readString(Path.of("docs/RELEASE_NOTES.md"));
        assertTrue(releaseNotes.contains("./bin/java17-source-compat-0.6-consumer.sh"));
        assertTrue(releaseNotes.contains("batch_not_supported"));
        assertTrue(releaseNotes.contains("passes batch bodies downstream unchanged"));

        List<String> workflowsThatNeedCheckout = List.of(
                "central-validation-upload.yml",
                "ci.yml",
                "codeql.yml",
                "pages.yml",
                "snyk.yml");
        for (Path workflow : workflows) {
            String content = Files.readString(workflow);
            var checkout = CHECKOUT_ACTION.matcher(content);
            boolean foundCheckout = false;
            while (checkout.find()) {
                foundCheckout = true;
                assertTrue("v7".equals(checkout.group(1)), () -> workflow + " should use actions/checkout@v7");
            }
            if (workflowsThatNeedCheckout.contains(workflow.getFileName().toString())) {
                assertTrue(foundCheckout, () -> workflow + " should check out repository contents");
            }
        }
    }

    @Test
    void developmentAndReleaseVerificationStaySeparatedAndSupplyChainPinned() throws IOException {
        String rootBuild = Files.readString(Path.of("build.gradle"));
        String coreBuild = Files.readString(Path.of("core/build.gradle"));
        String adapterBuild = Files.readString(Path.of("adapters/spring-webflux/build.gradle"));
        String gradleProperties = Files.readString(Path.of("gradle.properties"));
        String wrapperProperties = Files.readString(Path.of("gradle/wrapper/gradle-wrapper.properties"));
        String ci = Files.readString(Path.of(".github/workflows/ci.yml"));
        String centralWorkflow = Files.readString(Path.of(".github/workflows/central-validation-upload.yml"));
        String codeqlWorkflow = Files.readString(Path.of(".github/workflows/codeql.yml"));
        String snykWorkflow = Files.readString(Path.of(".github/workflows/snyk.yml"));
        String centralUpload = Files.readString(Path.of("bin/gateway-public-preview-central-validation-upload.sh"));
        String snykPolicy = Files.readString(Path.of(".snyk"));
        String docsPackage = Files.readString(Path.of("docs-site/package.json"));

        assertTrue(gradleProperties.contains("gatewayCoreVersion="));
        assertTrue(!gradleProperties.contains("gatewayCoreVersion=0.7.0"));
        assertTrue(wrapperProperties.contains(
                "distributionSha256Sum=9c0f7faeeb306cb14e4279a3e084ca6b596894089a0638e68a07c945a32c9e14"));

        String developmentTask = section(
                rootBuild,
                "tasks.register('verifyGatewayDevelopment')",
                "tasks.register('verifyAcceptedApiDeltas')");
        assertTrue(developmentTask.contains("dependsOn tasks.named('check')"));
        assertTrue(developmentTask.contains("verifyGatewayCorePublication"));
        assertTrue(developmentTask.contains("verifyGatewaySpringWebFluxPublication"));
        assertTrue(!developmentTask.contains("CentralPortal"));
        assertTrue(!developmentTask.contains("SignedCentralPortal"));
        assertTrue(coreBuild.contains("tasks.register('verifyGatewayCorePublication')"));
        assertTrue(coreBuild.contains("gatewayCorePublicationArtifact"));
        assertTrue(coreBuild.contains("new File(publicationDir, 'maven-metadata.xml')"));
        assertTrue(adapterBuild.contains("springWebFluxPublicationArtifact"));
        assertTrue(adapterBuild.contains("new File(publicationDir, 'maven-metadata.xml')"));

        String coreReleaseTask = section(
                coreBuild,
                "tasks.register('verifyGatewayCorePublicPreviewPublication')",
                "tasks.named('test')");
        assertTrue(coreReleaseTask.contains("verifyGatewayCoreCentralPortalBundle"));
        assertTrue(coreReleaseTask.contains("verifyGatewayCoreSignedCentralPortalDryRun"));
        assertTrue(coreReleaseTask.contains("verifyGatewayCorePublication"));

        String coreCheckTask = coreBuild.substring(coreBuild.indexOf("tasks.named('check')"));
        assertTrue(!coreCheckTask.contains("verifyGatewayCoreCentralPortalBundle"));
        assertTrue(!coreCheckTask.contains("verifyGatewayCoreSignedCentralPortalDryRun"));

        assertTrue(ci.contains("./gradlew verifyGatewayDevelopment"));
        assertTrue(!ci.contains("./gradlew verifyGatewayPublicPreviewPublication"));
        assertTrue(centralUpload.contains("./gradlew verifyGatewayPublicPreviewPublication"));
        String unpublishedCheck = "ensure_version_is_unpublished \"$version\"";
        assertTrue(centralUpload.indexOf(unpublishedCheck) != centralUpload.lastIndexOf(unpublishedCheck));
        assertTrue(centralUpload.contains(
                unpublishedCheck + "\n  upload_user_managed_deployment \"$version\" \"$bundle\" \"$work_root\""));
        assertTrue(centralUpload.contains("https://repo1.maven.org/maven2/"));
        assertTrue(centralUpload.contains("refusing to reuse immutable Maven Central coordinate"));
        assertTrue(centralUpload.contains("--warning-mode fail"));
        assertTrue(centralUpload.contains("-PgatewayCoreVersion=\"$version\""));
        assertTrue(centralUpload.contains("full primary-key fingerprint"));
        assertTrue(centralUpload.contains("GATEWAY_CORE_JAVA17_HOME"));
        assertTrue(centralUpload.contains("$ROOT_DIR/bin/java17-consumer-smoke.sh"));
        assertTrue(centralUpload.contains("$ROOT_DIR/bin/java17-source-compat-0.6-consumer.sh"));
        assertTrue(centralUpload.contains("copy_publication_for_release_signing \"$version\""));
        assertTrue(centralWorkflow.contains("Set up JDK 17 for release consumer checks"));
        assertTrue(centralWorkflow.contains("GATEWAY_CORE_JAVA17_HOME=${JAVA_HOME}"));
        assertTrue(centralWorkflow.contains("group: central-validation-upload"));
        assertTrue(centralWorkflow.contains("cancel-in-progress: false"));
        assertTrue(centralUpload.indexOf("./gradlew verifyGatewayPublicPreviewPublication")
                < centralUpload.indexOf("$ROOT_DIR/bin/java17-consumer-smoke.sh"));
        assertTrue(centralUpload.indexOf("$ROOT_DIR/bin/java17-consumer-smoke.sh")
                < centralUpload.indexOf("$ROOT_DIR/bin/java17-source-compat-0.6-consumer.sh"));
        assertTrue(centralUpload.indexOf("$ROOT_DIR/bin/java17-source-compat-0.6-consumer.sh")
                < centralUpload.indexOf("copy_publication_for_release_signing \"$version\""));
        assertTrue(centralWorkflow.indexOf("Set up JDK 17 for release consumer checks")
                < centralWorkflow.indexOf("Set up JDK 25"));

        for (String workflow : List.of(ci, centralWorkflow, codeqlWorkflow, snykWorkflow)) {
            assertTrue(workflow.contains("gradle/actions/wrapper-validation@v6"));
        }

        assertTrue(snykPolicy.contains("ignore: {}"));
        assertTrue(!snykPolicy.contains("SNYK-JAVA-COMFASTERXMLJACKSONCORE-17457695"));
        assertTrue(docsPackage.contains("\"node\": \">=22.12.0\""));
    }

    private static String section(String content, String startMarker, String endMarker) {
        int start = content.indexOf(startMarker);
        int end = content.indexOf(endMarker, start + startMarker.length());
        assertTrue(start >= 0, () -> "Missing section start: " + startMarker);
        assertTrue(end > start, () -> "Missing section end: " + endMarker);
        return content.substring(start, end);
    }
}
