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
const sitemapIndexXml = await readDist('sitemap-index.xml');
const sitemapXml = await readDist('sitemap-0.xml');
const sitemapIndexLocs = extractLocs(sitemapIndexXml, 'sitemap-index.xml');
const sitemapLocs = extractLocs(sitemapXml, 'sitemap-0.xml');

assertEqual(extract(indexHtml, /<link rel="canonical" href="([^"]+)"/, 'home canonical'), homeUrl);
assertEqual(extract(indexHtml, /<meta property="og:url" content="([^"]+)"/, 'home og:url'), homeUrl);
assertEqual(
  extract(gettingStartedHtml, /<link rel="canonical" href="([^"]+)"/, 'getting-started canonical'),
  `${homeUrl}guides/getting-started/`,
);

assertContains(
  indexHtml,
  'href="https://github.com/dtkmn/mcp-gateway-core/edit/main/docs-site/src/content/docs/index.md"',
  'home edit link should point at the docs-site source file',
);

for (const href of [
  'guides/getting-started/',
  'reference/contract-reference/',
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
  `${homeUrl}reference/modules/`,
  `${homeUrl}reference/compatibility/`,
  `${homeUrl}project/roadmap/`,
  `${homeUrl}maintainers/release-notes/`,
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
