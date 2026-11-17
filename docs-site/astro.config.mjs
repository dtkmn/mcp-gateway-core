import { defineConfig } from 'astro/config';
import starlight from '@astrojs/starlight';
import { syncDocs } from './scripts/sync-docs.mjs';

export default defineConfig({
  site: 'https://danieltse.org',
  base: '/mcp-gateway-core',
  trailingSlash: 'always',
  integrations: [
    {
      name: 'repository-docs',
      hooks: {
        'astro:config:setup': async ({ command }) => {
          if (command === 'dev' || command === 'build') {
            await syncDocs();
          }
        },
      },
    },
    starlight({
      title: 'MCP Gateway Core',
      description: 'Java contracts and Spring WebFlux adapters for MCP tool governance.',
      social: [
        {
          icon: 'github',
          label: 'GitHub',
          href: 'https://github.com/dtkmn/mcp-gateway-core',
        },
      ],
      editLink: {
        baseUrl: 'https://github.com/dtkmn/mcp-gateway-core/edit/main/docs-site/',
      },
      sidebar: [
        {
          label: 'Start',
          items: [
            { label: 'Overview', link: '/' },
            'guides/getting-started',
            'reference/contract-reference',
          ],
        },
        {
          label: 'Reference',
          items: [
            'reference/zap-integration',
            'reference/modules',
            'reference/compatibility',
          ],
        },
        {
          label: 'Project',
          items: [
            'project/roadmap',
            'project/security',
          ],
        },
        {
          label: 'Maintainers',
          items: [
            'maintainers/release-policy',
            'maintainers/release-notes',
            'maintainers/central-validation-upload',
          ],
        },
      ],
      customCss: ['./src/styles/custom.css'],
    }),
  ],
});
