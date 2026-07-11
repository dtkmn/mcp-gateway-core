package mcp.gateway.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.snakeyaml.engine.v2.api.Load;
import org.snakeyaml.engine.v2.api.LoadSettings;
import org.snakeyaml.engine.v2.schema.CoreSchema;

import static org.junit.jupiter.api.Assertions.*;

class ProjectDocumentationAndSecurityToolingTest {
    private static final Pattern FULL_COMMIT_SHA = Pattern.compile("[0-9a-f]{40}");
    private static final Map<String, String> REVIEWED_ACTION_SHAS = Map.ofEntries(
            Map.entry("actions/checkout", "9c091bb21b7c1c1d1991bb908d89e4e9dddfe3e0"),
            Map.entry("actions/deploy-pages", "cd2ce8fcbc39b97be8ca5fce6e763baed58fa128"),
            Map.entry("actions/setup-java", "0f481fcb613427c0f801b606911222b5b6f3083a"),
            Map.entry("actions/setup-node", "48b55a011bda9f5d6aeb4c2d9c7362e8dae4041e"),
            Map.entry("actions/upload-artifact", "043fb46d1a93c77aae656e7c1c64a875d1fc6a0a"),
            Map.entry("github/codeql-action/analyze", "99df26d4f13ea111d4ec1a7dddef6063f76b97e9"),
            Map.entry("github/codeql-action/init", "99df26d4f13ea111d4ec1a7dddef6063f76b97e9"),
            Map.entry("github/codeql-action/upload-sarif", "99df26d4f13ea111d4ec1a7dddef6063f76b97e9"),
            Map.entry("gradle/actions/wrapper-validation", "3f131e8634966bd73d06cc69884922b02e6faf92"),
            Map.entry("snyk/actions/setup", "9adf32b1121593767fc3c057af55b55db032dc04"),
            Map.entry("withastro/action", "b7d53628f8b666036b0238aadb0b984a2a489f26"));

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
        assertPinnedAction(pagesWorkflow, "withastro/action");
        assertPinnedAction(pagesWorkflow, "actions/deploy-pages");
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
                    .filter(path -> path.toString().endsWith(".yml") || path.toString().endsWith(".yaml"))
                    .sorted()
                    .toList();
        }

        assertTrue(dependabot.contains("package-ecosystem: \"github-actions\""));
        assertTrue(dependabot.contains("package-ecosystem: \"gradle\""));
        assertTrue(dependabot.contains("package-ecosystem: \"npm\""));
        assertTrue(dependabot.contains("directory: \"/docs-site\""));
        assertPinnedAction(codeql, "github/codeql-action/init");
        assertTrue(codeql.contains("build-mode: manual"));
        assertTrue(codeql.contains("./gradlew clean test --no-daemon --stacktrace"));
        assertTrue(security.contains("Dependabot version updates"));
        assertTrue(security.contains("CodeQL Java analysis"));
        assertTrue(security.contains("latest published `0.7.x` artifact line"));
        assertTrue(!security.contains("latest published `0.6.x` artifact"));
        assertTrue(!security.contains("latest published `0.5.x` artifact"));
        assertPinnedAction(snyk, "snyk/actions/setup");
        assertTrue(snyk.contains("SNYK_TOKEN"));
        assertTrue(snyk.contains("SNYK_ORG"));
        assertTrue(snyk.contains("secrets.SNYK_ORG || vars.SNYK_ORG"));
        assertTrue(snyk.contains("SNYK_ORG secret or variable is not set"));
        assertTrue(snyk.contains("snyk_args=(--all-sub-projects"));
        assertTrue(snyk.contains("snyk_args+=(\"--org=${SNYK_ORG}\")"));
        assertTrue(snyk.contains("snyk test \"${snyk_args[@]}\""));
        assertTrue(snyk.contains("--sarif-file-output=snyk-open-source.sarif"));
        assertPinnedAction(snyk, "github/codeql-action/upload-sarif");
        assertPinnedAction(snyk, "actions/upload-artifact");
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
        Set<String> observedActions = new HashSet<>();
        for (Path workflow : workflows) {
            String content = Files.readString(workflow);
            Set<String> workflowActions = assertActionsPinnedToReviewedCommits(workflow, content);
            observedActions.addAll(workflowActions);
            if (workflowsThatNeedCheckout.contains(workflow.getFileName().toString())) {
                assertTrue(workflowActions.contains("actions/checkout"),
                        () -> workflow + " should check out repository contents");
            }
        }
        assertEquals(REVIEWED_ACTION_SHAS.keySet(), observedActions,
                "Workflow action allowlist should cover every reviewed action and no others");
    }

    @Test
    void workflowActionPinningUsesParsedStructureAndProhibitsLocalUses() {
        String checkout = "actions/checkout@" + REVIEWED_ACTION_SHAS.get("actions/checkout");

        assertEquals(Set.of("actions/checkout"), assertActionsPinnedToReviewedCommits(
                Path.of("noncanonical-pinned-workflow.yml"),
                """
                jobs:
                  build: &pinned_job
                    runs-on: ubuntu-latest
                    steps:
                      - { uses: %1$s }
                      - uses : %1$s
                      - "u\\u0073es": %1$s
                  mirrored: *pinned_job
                """.formatted(checkout)));
        AssertionError escapedKeyFailure = assertThrows(
                AssertionError.class,
                () -> assertActionsPinnedToReviewedCommits(
                        Path.of("escaped-key-workflow.yml"),
                        """
                        jobs:
                          build:
                            runs-on: ubuntu-latest
                            steps:
                              - uses: %s
                              - "u\\u0073es": attacker/action@v1
                        """.formatted(checkout)));
        assertTrue(escapedKeyFailure.getMessage().contains("full lowercase commit SHA"));
        assertThrows(AssertionError.class, () -> assertActionsPinnedToReviewedCommits(
                Path.of("job-level-workflow.yml"),
                """
                jobs:
                  reusable:
                    "u\\u0073es": attacker/repository/.github/workflows/build.yml@v1
                """));
        AssertionError localActionFailure = assertThrows(
                AssertionError.class,
                () -> assertActionsPinnedToReviewedCommits(
                        Path.of("local-action-workflow.yml"),
                        """
                        jobs:
                          build:
                            runs-on: ubuntu-latest
                            steps:
                              - uses: ./.github/actions/release
                        """));
        assertTrue(localActionFailure.getMessage().contains("prohibited local action or reusable workflow"));
        assertThrows(AssertionError.class, () -> assertActionsPinnedToReviewedCommits(
                Path.of("merged-action-workflow.yml"),
                """
                jobs:
                  build:
                    runs-on: ubuntu-latest
                    env: &hidden_action
                      "u\\u0073es": attacker/action@v1
                    steps:
                      - <<: *hidden_action
                """));
        assertThrows(AssertionError.class, () -> assertActionsPinnedToReviewedCommits(
                Path.of("duplicate-key-workflow.yml"),
                """
                jobs:
                  build:
                    runs-on: ubuntu-latest
                    steps:
                      - uses: %s
                        "u\\u0073es": attacker/action@v1
                """.formatted(checkout)));
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
        String centralRunbook = Files.readString(Path.of("docs/CENTRAL_VALIDATION_UPLOAD.md"));
        String snykPolicy = Files.readString(Path.of(".snyk"));
        String docsPackage = Files.readString(Path.of("docs-site/package.json"));

        assertTrue(gradleProperties.contains("gatewayCoreVersion="));
        assertTrue(gradleProperties.contains("gatewayCorePublishedVersion="));
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
        assertNotEquals(centralUpload.indexOf(unpublishedCheck), centralUpload.lastIndexOf(unpublishedCheck));
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
        assertTrue(centralWorkflow.contains("    environment: central-validation-upload"));
        assertTrue(centralRunbook.contains("**environment secrets** on `central-validation-upload`"));
        assertTrue(centralRunbook.contains("At least one required reviewer"));
        assertTrue(centralRunbook.contains("Self-review is prevented"));
        assertTrue(centralRunbook.contains("full-length commit SHA"));
        assertTrue(centralUpload.indexOf("./gradlew verifyGatewayPublicPreviewPublication")
                < centralUpload.indexOf("$ROOT_DIR/bin/java17-consumer-smoke.sh"));
        assertTrue(centralUpload.indexOf("$ROOT_DIR/bin/java17-consumer-smoke.sh")
                < centralUpload.indexOf("$ROOT_DIR/bin/java17-source-compat-0.6-consumer.sh"));
        assertTrue(centralUpload.indexOf("$ROOT_DIR/bin/java17-source-compat-0.6-consumer.sh")
                < centralUpload.indexOf("copy_publication_for_release_signing \"$version\""));
        assertTrue(centralWorkflow.indexOf("Set up JDK 17 for release consumer checks")
                < centralWorkflow.indexOf("Set up JDK 25"));

        for (String workflow : List.of(ci, centralWorkflow, codeqlWorkflow, snykWorkflow)) {
            assertPinnedAction(workflow, "gradle/actions/wrapper-validation");
        }

        assertTrue(snykPolicy.contains("ignore: {}"));
        assertTrue(!snykPolicy.contains("SNYK-JAVA-COMFASTERXMLJACKSONCORE-17457695"));
        assertTrue(docsPackage.contains("\"node\": \">=22.12.0\""));
    }

    @Test
    void releaseDocumentationTracksBuildStatePublishedCoordinatesAndReleaseProtections() throws IOException {
        String gradleProperties = Files.readString(Path.of("gradle.properties"));
        String releaseNotes = Files.readString(Path.of("docs/RELEASE_NOTES.md"));
        String readme = Files.readString(Path.of("README.md"));
        String gettingStarted = Files.readString(Path.of("docs/GETTING_STARTED.md"));
        String docsIndex = Files.readString(Path.of("docs-site/src/content/docs/index.md"));
        String centralRunbook = Files.readString(Path.of("docs/CENTRAL_VALIDATION_UPLOAD.md"));
        String releasePolicy = Files.readString(Path.of("docs/RELEASE_POLICY.md"));
        String roadmap = Files.readString(Path.of("docs/ROADMAP.md"));
        String security = Files.readString(Path.of("SECURITY.md"));

        var versionMatcher = Pattern.compile(
                        "(?m)^\\s*gatewayCoreVersion\\s*=\\s*([^\\s#]+)\\s*$")
                .matcher(gradleProperties);
        assertTrue(versionMatcher.find(), "gradle.properties must define gatewayCoreVersion exactly once");
        String configuredVersion = versionMatcher.group(1);
        assertFalse(versionMatcher.find(), "gradle.properties must not define gatewayCoreVersion more than once");
        var publishedVersionMatcher = Pattern.compile(
                        "(?m)^\\s*gatewayCorePublishedVersion\\s*=\\s*([^\\s#]+)\\s*$")
                .matcher(gradleProperties);
        assertTrue(publishedVersionMatcher.find(),
                "gradle.properties must define gatewayCorePublishedVersion exactly once");
        String declaredPublishedVersion = publishedVersionMatcher.group(1);
        assertFalse(publishedVersionMatcher.find(),
                "gradle.properties must not define gatewayCorePublishedVersion more than once");
        assertFalse(declaredPublishedVersion.endsWith("-SNAPSHOT"),
                "gatewayCorePublishedVersion must identify a Maven Central release");
        boolean snapshot = configuredVersion.endsWith("-SNAPSHOT");
        String releaseVersion = snapshot
                ? configuredVersion.substring(0, configuredVersion.length() - "-SNAPSHOT".length())
                : configuredVersion;

        var releaseHeadingMatcher = Pattern.compile(
                        "(?m)^##\\s+" + Pattern.quote(releaseVersion)
                                + "\\s+(\\(Unreleased\\)|Public Preview)\\s*$")
                .matcher(releaseNotes);
        assertTrue(releaseHeadingMatcher.find(),
                () -> "Release notes must have an Unreleased or Public Preview heading for " + releaseVersion);
        boolean unreleased = "(Unreleased)".equals(releaseHeadingMatcher.group(1));
        assertFalse(releaseHeadingMatcher.find(),
                () -> "Release notes must have exactly one release heading for " + releaseVersion);
        if (snapshot) {
            assertTrue(unreleased, "A snapshot development version must have an Unreleased release-notes heading");
        } else {
            assertFalse(releaseNotes.contains(releaseVersion + "-SNAPSHOT"),
                    () -> "Release notes must not describe non-snapshot " + releaseVersion + " as a snapshot");
            if (unreleased) {
                assertTrue(releaseNotes.contains(
                                "`" + releaseVersion + "` is the current public-preview release candidate"),
                        () -> "A non-snapshot Unreleased version must be identified as the current release candidate");
            }
        }

        String publishedVersion = assertPublishedCoordinatesMatch("README.md", readme);
        assertEquals(declaredPublishedVersion, publishedVersion,
                "Public dependency examples must match gatewayCorePublishedVersion");
        assertEquals(publishedVersion,
                assertPublishedCoordinatesMatch("docs/GETTING_STARTED.md", gettingStarted));
        assertEquals(publishedVersion,
                assertPublishedCoordinatesMatch("docs-site/src/content/docs/index.md", docsIndex));
        for (var document : Map.of(
                "docs/RELEASE_NOTES.md", releaseNotes,
                "docs/CENTRAL_VALIDATION_UPLOAD.md", centralRunbook,
                "docs/RELEASE_POLICY.md", releasePolicy,
                "docs/ROADMAP.md", roadmap).entrySet()) {
            assertContainsPattern(
                    document.getValue(),
                    Pattern.quote("`" + declaredPublishedVersion + "`")
                            + ".{0,160}latest published.{0,160}version",
                    document.getKey() + " must identify gatewayCorePublishedVersion as the latest published version");
        }
        if (unreleased) {
            assertNotEquals(releaseVersion, publishedVersion,
                    "An unreleased candidate must not be advertised as the published coordinate");
            assertContainsPattern(
                    releaseNotes,
                    Pattern.quote("`" + publishedVersion + "`")
                            + ".{0,100}latest published (?:coordinate|version)",
                    "Unreleased notes must identify the version used by public dependency examples");
        } else {
            assertEquals(releaseVersion, publishedVersion,
                    "Published release notes and public dependency examples must name the same version");
        }

        assertReleaseEnvironmentInvariants("SECURITY.md", security);
        assertReleaseEnvironmentInvariants("docs/CENTRAL_VALIDATION_UPLOAD.md", centralRunbook);
        assertReleaseEnvironmentInvariants("docs/RELEASE_POLICY.md", releasePolicy);

        String postPublication = sectionToEnd(
                centralRunbook,
                "## Post-Publication Verification And Repository Finalization");
        assertTrue(postPublication.contains("mcp-gateway-core"));
        assertTrue(postPublication.contains("mcp-gateway-spring-webflux"));
        assertContainsPattern(postPublication, "verify.{0,100}both.{0,100}Maven coordinates",
                "Post-publication steps must verify both Maven coordinates");
        assertContainsPattern(postPublication, "(?:tag.{0,40}exact source commit|exact source commit.{0,100}tag)",
                "Post-publication steps must tag the exact validated source commit");
        assertTrue(postPublication.contains("GitHub Release"),
                "Post-publication steps must create or update the GitHub Release");
        assertContainsPattern(postPublication, "finalize.{0,160}(?:public documentation|release notes).{0,160}examples",
                "Post-publication steps must finalize release notes and public examples");
        assertContainsPattern(postPublication, "advance.{0,60}`develop`.{0,100}next `-SNAPSHOT`",
                "Post-publication steps must advance develop to the next snapshot");
        assertTrue(postPublication.contains("`v<version>`")
                        || postPublication.contains("`v" + releaseVersion + "`"),
                "Post-publication steps must document the release tag naming convention");

        assertContainsPattern(releaseNotes, "reviewed full commit SHA",
                "Release notes must record immutable reviewed Action pins");
        assertContainsPattern(releaseNotes, "structural(?:ly)? pars(?:e|es|ing).{0,40}(?:the )?decoded YAML.{0,160}`uses`",
                "Release notes must record structural workflow parsing");
        assertContainsPattern(releaseNotes, "aliases.{0,100}(?:merge-hidden|merges? that conceal)",
                "Release notes must record YAML alias and merge-hidden reference enforcement");
        assertContainsPattern(releaseNotes, "(?:reject|prohibit)[^.]{0,160}local action references",
                "Release notes must record the local-action prohibition");
        assertContainsPattern(
                releaseNotes,
                "(?:repository SHA-pinning enforcement.{0,60}enabled"
                        + "|repository setting.{0,100}rejects non-SHA action references)",
                "Release notes must record repository-level SHA enforcement");
        assertContainsPattern(releaseNotes, "`central-validation-upload`.{0,500}environment secrets",
                "Release notes must record the protected environment and credential migration");
        assertContainsPattern(releaseNotes, "`UrlScope`.{0,500}(?:before|prior to).{0,60}normalization",
                "Release notes must record validation-before-normalization hardening");
    }

    private static String assertPublishedCoordinatesMatch(String document, String content) {
        String coreVersion = singleCoordinateVersion(document, content, "mcp-gateway-core");
        String adapterVersion = singleCoordinateVersion(document, content, "mcp-gateway-spring-webflux");
        assertEquals(coreVersion, adapterVersion,
                () -> document + " must use one published version for both Maven coordinates");
        assertFalse(coreVersion.endsWith("-SNAPSHOT"),
                () -> document + " must not put a snapshot version in public dependency examples");
        if (content.contains("<artifactId>")) {
            assertEquals(coreVersion, singleMavenXmlVersion(document, content, "mcp-gateway-core"),
                    () -> document + " Maven XML core example must match its Gradle coordinates");
            assertEquals(adapterVersion, singleMavenXmlVersion(document, content, "mcp-gateway-spring-webflux"),
                    () -> document + " Maven XML adapter example must match its Gradle coordinates");
        }
        return coreVersion;
    }

    private static String singleCoordinateVersion(String document, String content, String artifactId) {
        var matcher = Pattern.compile(
                        "io\\.github\\.dtkmn:" + Pattern.quote(artifactId)
                                + ":([0-9]+(?:\\.[0-9]+){2}(?:[-+][0-9A-Za-z.-]+)?)")
                .matcher(content);
        Set<String> versions = new HashSet<>();
        while (matcher.find()) {
            versions.add(matcher.group(1));
        }
        assertEquals(1, versions.size(),
                () -> document + " must contain " + artifactId + " examples at exactly one version: " + versions);
        return versions.iterator().next();
    }

    private static String singleMavenXmlVersion(String document, String content, String artifactId) {
        var matcher = Pattern.compile(
                        "<artifactId>\\s*" + Pattern.quote(artifactId)
                                + "\\s*</artifactId>\\s*<version>\\s*([^<\\s]+)\\s*</version>",
                        Pattern.DOTALL)
                .matcher(content);
        Set<String> versions = new HashSet<>();
        while (matcher.find()) {
            versions.add(matcher.group(1));
        }
        assertEquals(1, versions.size(),
                () -> document + " must contain one Maven XML version for " + artifactId + ": " + versions);
        return versions.iterator().next();
    }

    private static void assertReleaseEnvironmentInvariants(String document, String content) {
        // These assertions prevent source-documentation drift. Live GitHub settings
        // still require independent inspection before every release.
        String normalized = content.replaceAll("\\s+", " ").toLowerCase(java.util.Locale.ROOT);
        assertTrue(normalized.contains("release refs are restricted to `main` only"),
                () -> document + " must restrict release deployments to main only");
        assertTrue(normalized.contains("at least one required reviewer must be distinct from the workflow dispatcher"),
                () -> document + " must require a reviewer distinct from the workflow dispatcher");
        assertTrue(normalized.contains("self-review is prevented"),
                () -> document + " must prevent self-review");
        assertTrue(normalized.contains("administrator bypass is disabled"),
                () -> document + " must disable administrator bypass");
        assertTrue(normalized.contains("release credentials exist only as environment secrets"),
                () -> document + " must keep release credentials only in environment secrets");
    }

    private static void assertContainsPattern(String content, String expression, String message) {
        assertTrue(Pattern.compile(expression, Pattern.CASE_INSENSITIVE | Pattern.DOTALL)
                        .matcher(content.replaceAll("\\s+", " "))
                        .find(),
                message);
    }

    private static String sectionToEnd(String content, String startMarker) {
        int start = content.indexOf(startMarker);
        assertTrue(start >= 0, () -> "Missing section start: " + startMarker);
        return content.substring(start);
    }

    private static Set<String> assertActionsPinnedToReviewedCommits(Path workflow, String content) {
        LoadSettings settings = LoadSettings.builder()
                .setLabel(workflow.toString())
                .setSchema(new CoreSchema())
                .setAllowDuplicateKeys(false)
                .setAllowRecursiveKeys(false)
                .setAllowNonScalarKeys(false)
                .setMaxAliasesForCollections(20)
                .setCodePointLimit(1_000_000)
                .build();
        Object parsed = assertDoesNotThrow(
                () -> new Load(settings).loadFromString(content),
                () -> workflow + " must be valid YAML without duplicate keys");
        Map<?, ?> root = requireMapping(parsed, workflow + " document");
        Map<?, ?> jobs = requireMapping(root.get("jobs"), workflow + " jobs");
        assertFalse(jobs.isEmpty(), () -> workflow + " should define at least one job");

        Set<String> actions = new HashSet<>();
        for (Map.Entry<?, ?> jobEntry : jobs.entrySet()) {
            String jobName = assertInstanceOf(
                    String.class,
                    jobEntry.getKey(),
                    () -> workflow + " job identifiers must be strings");
            Map<?, ?> job = requireMapping(jobEntry.getValue(), workflow + " job " + jobName);
            if (job.containsKey("uses")) {
                addReviewedAction(workflow, "job " + jobName, job.get("uses"), actions);
            }

            if (!job.containsKey("steps")) {
                continue;
            }
            Object stepsValue = job.get("steps");
            List<?> steps = requireList(stepsValue, workflow + " job " + jobName + " steps");
            for (int index = 0; index < steps.size(); index++) {
                Map<?, ?> step = requireMapping(
                        steps.get(index),
                        workflow + " job " + jobName + " step " + index);
                if (step.containsKey("uses")) {
                    addReviewedAction(
                            workflow,
                            "job " + jobName + " step " + index,
                            step.get("uses"),
                            actions);
                }
            }
        }
        assertFalse(actions.isEmpty(), () -> workflow + " should contain at least one reviewed action");
        return actions;
    }

    private static void addReviewedAction(
            Path workflow,
            String location,
            Object value,
            Set<String> actions
    ) {
        String reference = assertInstanceOf(
                String.class,
                value,
                () -> workflow + " " + location + " uses value must be a string");
        assertFalse(reference.isBlank(),
                () -> workflow + " " + location + " uses value must not be blank");
        assertFalse(reference.startsWith("./"),
                () -> workflow + " " + location + " uses a prohibited local action or reusable workflow: " + reference);

        int separator = reference.lastIndexOf('@');
        assertTrue(separator > 0,
                () -> workflow + " " + location + " is missing a commit reference: " + reference);
        String action = reference.substring(0, separator);
        String revision = reference.substring(separator + 1);
        assertTrue(FULL_COMMIT_SHA.matcher(revision).matches(),
                () -> workflow + " must pin " + action + " to a full lowercase commit SHA, not " + revision);
        assertTrue(REVIEWED_ACTION_SHAS.containsKey(action),
                () -> workflow + " uses an action or reusable workflow that has not been reviewed: " + action);
        assertEquals(REVIEWED_ACTION_SHAS.get(action), revision,
                () -> workflow + " must use the reviewed commit for " + action);
        actions.add(action);
    }

    private static Map<?, ?> requireMapping(Object value, String location) {
        assertInstanceOf(Map.class, value, () -> location + " must be a mapping");
        return (Map<?, ?>) value;
    }

    private static List<?> requireList(Object value, String location) {
        assertInstanceOf(List.class, value, () -> location + " must be a list");
        return (List<?>) value;
    }

    private static void assertPinnedAction(String workflow, String action) {
        String revision = REVIEWED_ACTION_SHAS.get(action);
        assertNotNull(revision, () -> "Missing reviewed commit for " + action);
        assertTrue(workflow.contains("uses: " + action + "@" + revision),
                () -> "Workflow should pin " + action + " to reviewed commit " + revision);
    }

    private static String section(String content, String startMarker, String endMarker) {
        int start = content.indexOf(startMarker);
        int end = content.indexOf(endMarker, start + startMarker.length());
        assertTrue(start >= 0, () -> "Missing section start: " + startMarker);
        assertTrue(end > start, () -> "Missing section end: " + endMarker);
        return content.substring(start, end);
    }
}
