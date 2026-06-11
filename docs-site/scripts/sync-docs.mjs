import { mkdir, readFile, rm, writeFile } from 'node:fs/promises';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const repoRoot = fileURLToPath(new URL('../..', import.meta.url));
const siteRoot = fileURLToPath(new URL('..', import.meta.url));
const docsRoot = join(siteRoot, 'src/content/docs');

const pages = [
  {
    source: 'docs/GETTING_STARTED.md',
    target: 'guides/getting-started.md',
    title: 'Getting Started',
    description: 'Wire MCP Gateway Core into an existing Java MCP server.',
  },
  {
    source: 'docs/CONTRACT_REFERENCE.md',
    target: 'reference/contract-reference.md',
    title: 'Contract Reference',
    description: 'Field and value semantics for MCP Gateway Core contracts.',
  },
  {
    source: 'docs/MODULES.md',
    target: 'reference/modules.md',
    title: 'Module Map',
    description: 'Package ownership and dependency boundaries.',
  },
  {
    source: 'docs/COMPATIBILITY.md',
    target: 'reference/compatibility.md',
    title: 'Compatibility',
    description: 'Public-preview compatibility promises and non-promises.',
  },
  {
    source: 'docs/ROADMAP.md',
    target: 'project/roadmap.md',
    title: 'Roadmap',
    description: 'Graduation criteria and future direction.',
  },
  {
    source: 'SECURITY.md',
    target: 'project/security.md',
    title: 'Security',
    description: 'Security policy and vulnerability reporting.',
  },
  {
    source: 'docs/RELEASE_POLICY.md',
    target: 'maintainers/release-policy.md',
    title: 'Release Policy',
    description: 'Public-preview release gates for maintainers.',
  },
  {
    source: 'docs/CENTRAL_VALIDATION_UPLOAD.md',
    target: 'maintainers/central-validation-upload.md',
    title: 'Central Validation Upload',
    description: 'Guarded Maven Central validation-upload process.',
  },
];

await Promise.all([
  rm(join(docsRoot, 'guides'), { recursive: true, force: true }),
  rm(join(docsRoot, 'reference'), { recursive: true, force: true }),
  rm(join(docsRoot, 'project'), { recursive: true, force: true }),
  rm(join(docsRoot, 'maintainers'), { recursive: true, force: true }),
]);

for (const page of pages) {
  const sourcePath = join(repoRoot, page.source);
  const targetPath = join(docsRoot, page.target);
  const source = await readFile(sourcePath, 'utf8');
  const content = stripFirstHeading(source);
  const generated = [
    '---',
    `title: ${quoteYaml(page.title)}`,
    `description: ${quoteYaml(page.description)}`,
    `editUrl: ${quoteYaml(`https://github.com/dtkmn/mcp-gateway-core/edit/main/${page.source}`)}`,
    '---',
    '',
    '<!-- This page is generated from the repository root docs. Edit the source Markdown file, not this generated file. -->',
    '',
    content.trimEnd(),
    '',
  ].join('\n');

  await mkdir(dirname(targetPath), { recursive: true });
  await writeFile(targetPath, generated);
}

function stripFirstHeading(markdown) {
  return markdown.replace(/^# .+\n+/, '');
}

function quoteYaml(value) {
  return JSON.stringify(value);
}
