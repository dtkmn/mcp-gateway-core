import { readFile } from 'node:fs/promises';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';

const siteRoot = fileURLToPath(new URL('..', import.meta.url));
const distRoot = join(siteRoot, 'dist');

const site = 'https://danieltse.org';
const base = '/mcp-gateway-core';
const homeUrl = `${site}${base}/`;

const indexHtml = await readDist('index.html');
const gettingStartedHtml = await readDist('guides/getting-started/index.html');
const compatibilityHtml = await readDist('reference/compatibility/index.html');
const releaseNotesHtml = await readDist('maintainers/release-notes/index.html');
const releasePolicyHtml = await readDist('maintainers/release-policy/index.html');
const contractReferenceHtml = await readDist('reference/contract-reference/index.html');
const zapIntegrationHtml = await readDist('reference/zap-integration/index.html');
const securityHtml = await readDist('project/security/index.html');
const sitemapIndexXml = await readDist('sitemap-index.xml');
const sitemapXml = await readDist('sitemap-0.xml');
const faviconSvg = await readDist('favicon.svg');
const sitemapIndexLocs = extractLocs(sitemapIndexXml, 'sitemap-index.xml');
const sitemapLocs = extractLocs(sitemapXml, 'sitemap-0.xml');

assertEqual(extract(indexHtml, /<link rel="canonical" href="([^"]+)"/, 'home canonical'), homeUrl);
assertEqual(extract(indexHtml, /<meta property="og:url" content="([^"]+)"/, 'home og:url'), homeUrl);
assertContains(indexHtml, 'href="/mcp-gateway-core/favicon.svg"', 'home page should link to the deployed favicon');
assertContains(faviconSvg, '<svg', 'favicon should be a valid SVG document');
assertEqual(
  extract(gettingStartedHtml, /<link rel="canonical" href="([^"]+)"/, 'getting-started canonical'),
  `${homeUrl}guides/getting-started/`,
);
assertEqual(
  extract(zapIntegrationHtml, /<link rel="canonical" href="([^"]+)"/, 'integration reference canonical'),
  `${homeUrl}reference/zap-integration/`,
);
assertContains(
  gettingStartedHtml,
  `href="${homeUrl}reference/zap-integration/"`,
  'getting-started should link to the deployed integration reference',
);
assertContains(
  zapIntegrationHtml,
  'href="/mcp-gateway-core/reference/zap-integration/"',
  'sidebar should link to the integration reference under the deployment base',
);
for (const anchor of ['audit-observer-helper-unreleased', 'rejection-responses-and-observability']) {
  assertContains(
    zapIntegrationHtml,
    `href="${homeUrl}reference/contract-reference/#${anchor}"`,
    `integration reference should link to the deployed ${anchor} contract`,
  );
  assertContains(contractReferenceHtml, `id="${anchor}"`, `contract reference should contain the ${anchor} destination`);
}

assertContains(
  indexHtml,
  'href="https://github.com/dtkmn/mcp-gateway-core/edit/main/docs-site/src/content/docs/index.md"',
  'home edit link should point at the docs-site source file',
);

const latestPublishedVersion = extract(
  indexHtml,
  /latest published preview(?: release| line) is <code[^>]*>([^<]+)<\/code>/,
  'home latest published preview release',
);
const coordinateVersions = [
  ...new Set(
    [...indexHtml.matchAll(/io\.github\.dtkmn:mcp-gateway-(?:core|spring-webflux):([^<&\s"]+)/g)]
      .map((match) => match[1]),
  ),
];
assertEqual(coordinateVersions.join(', '), latestPublishedVersion);

assertEqual(
  extract(
    gettingStartedHtml,
    /main examples below target the published <code[^>]*>([^<]+)<\/code>/,
    'getting-started published version',
  ),
  latestPublishedVersion,
);
assertEqual(
  extract(
    securityHtml,
    /<code[^>]*>([^<]+)<\/code> is the latest published release and the only line expected to receive\s+security fixes\./,
    'security-supported version',
  ),
  latestPublishedVersion,
);

for (const anchor of [
  'context-resolution-unreleased',
  'audit-observer-helper-unreleased',
  'adapter-rejection-observation-unreleased',
  'metadata-details',
]) {
  assertContains(
    gettingStartedHtml,
    `href="${homeUrl}reference/contract-reference/#${anchor}"`,
    `getting-started should link to the deployed ${anchor} contract`,
  );
  assertContains(
    contractReferenceHtml,
    `id="${anchor}"`,
    `contract reference should contain the ${anchor} destination`,
  );
}

for (const [, href] of gettingStartedHtml.matchAll(/<a\b[^>]*\bhref="([^"]+)"/g)) {
  const target = new URL(href, `${homeUrl}guides/getting-started/`);
  assert(
    target.origin !== site || !target.pathname.endsWith('.md'),
    `getting-started must not link to Markdown files on the website: ${href}`,
  );
}
assertContains(
  gettingStartedHtml,
  `href="${homeUrl}project/security/"`,
  'getting-started should link to the security support policy',
);

for (const [page, html] of [
  ['compatibility', compatibilityHtml],
  ['release notes', releaseNotesHtml],
]) {
  assertContains(
    html,
    `href="${homeUrl}reference/contract-reference/#active-tool-registry"`,
    `${page} should link to the deployed active-tool registry contract`,
  );
}

for (const anchor of ['active-tool-registry', 'active-tool-registry-unreleased']) {
  assertContains(contractReferenceHtml, `id="${anchor}"`, `contract reference should retain the ${anchor} anchor`);
}

assertContains(
  releasePolicyHtml,
  `href="${homeUrl}maintainers/central-validation-upload/"`,
  'release policy should link to the deployed Central validation upload guide',
);

for (const artifact of ['mcp-gateway-core', 'mcp-gateway-spring-webflux']) {
  assertContains(
    indexHtml,
    `io.github.dtkmn:${artifact}:${latestPublishedVersion}`,
    `home page should advertise ${artifact} at the latest published version`,
  );
  assertContains(
    gettingStartedHtml,
    `io.github.dtkmn:${artifact}:${latestPublishedVersion}`,
    `getting-started should use the published version of ${artifact}`,
  );
}

for (const href of [
  'guides/getting-started/',
  'reference/contract-reference/',
  'reference/zap-integration/',
  'reference/modules/',
  'reference/compatibility/',
  'project/roadmap/',
  'maintainers/release-notes/',
]) {
  assertContains(indexHtml, `href="${href}"`, `home page should link to ${href} relative to the canonical page`);
}

assertDoesNotMatch(
  indexHtml,
  /href="\/(?:guides|reference|project|maintainers)\//,
  'home page must not emit root-relative docs links that escape the deployment base',
);

assertAllLocsUnderBase(sitemapIndexLocs, 'sitemap index');
assertAllLocsUnderBase(sitemapLocs, 'sitemap');

assertContains(sitemapIndexLocs, `${homeUrl}sitemap-0.xml`, 'sitemap index should live under the deployment base');

for (const url of [
  homeUrl,
  `${homeUrl}guides/getting-started/`,
  `${homeUrl}reference/contract-reference/`,
  `${homeUrl}reference/zap-integration/`,
  `${homeUrl}reference/modules/`,
  `${homeUrl}reference/compatibility/`,
  `${homeUrl}project/roadmap/`,
  `${homeUrl}maintainers/release-notes/`,
  `${homeUrl}maintainers/central-validation-upload/`,
]) {
  assertContains(sitemapLocs, url, `sitemap should include ${url}`);
}

function readDist(path) {
  return readFile(join(distRoot, path), 'utf8');
}

function extract(content, pattern, label) {
  const match = content.match(pattern);
  assert(match, `Missing ${label}`);
  return match[1];
}

function extractLocs(xml, label) {
  const locs = [...xml.matchAll(/<loc>([^<]+)<\/loc>/g)].map((match) => match[1]);
  assert(locs.length > 0, `${label} must include at least one <loc>`);
  return locs;
}

function assertAllLocsUnderBase(locs, label) {
  for (const loc of locs) {
    assert(loc.startsWith(homeUrl), `${label} loc must stay under ${homeUrl}: ${loc}`);
  }
}

function assertEqual(actual, expected) {
  assert(actual === expected, `Expected ${expected}, got ${actual}`);
}

function assertContains(content, expected, message) {
  assert(content.includes(expected), `${message}: missing ${expected}`);
}

function assertDoesNotMatch(content, pattern, message) {
  assert(!pattern.test(content), message);
}

function assert(condition, message) {
  if (!condition) {
    throw new Error(`Build output verification failed: ${message}`);
  }
}
