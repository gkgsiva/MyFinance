import { defineConfig } from 'vite';
import preact from '@preact/preset-vite';
import { VitePWA } from 'vite-plugin-pwa';

// Served from https://<user>.github.io/myfinance/ by GitHub Pages; BASE overrides it (e.g. "/" for a custom domain).
const base = process.env.BASE ?? '/myfinance/';

export default defineConfig({
  base,
  plugins: [
    preact(),
    VitePWA({
      registerType: 'autoUpdate',
      includeAssets: ['icon.svg', 'apple-touch-icon.png'],
      manifest: {
        name: 'MyFinance',
        short_name: 'MyFinance',
        description: 'Your bank and card statements, read on your phone. Nothing leaves the device.',
        theme_color: '#0f766e',
        background_color: '#f6f7f9',
        display: 'standalone',
        orientation: 'portrait',
        start_url: base,
        scope: base,
        icons: [
          { src: 'icon-192.png', sizes: '192x192', type: 'image/png' },
          { src: 'icon-512.png', sizes: '512x512', type: 'image/png' },
          { src: 'icon-512.png', sizes: '512x512', type: 'image/png', purpose: 'maskable' },
        ],
      },
      workbox: {
        globPatterns: ['**/*.{js,mjs,css,html,svg,png,woff2}'],
        maximumFileSizeToCacheInBytes: 6 * 1024 * 1024,
      },
    }),
  ],
  build: { target: 'es2020', chunkSizeWarningLimit: 1500 },
});
