// vite.config.ts
import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

export default defineConfig({
  plugins: [react()],
  server: {
    port: 3000,
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
    },
  },
  build: {
    outDir:    'dist',
    sourcemap: false,
    rollupOptions: {
      output: {
        manualChunks: {
          vendor:   ['react','react-dom','react-query'],
          charts:   ['recharts'],
          ui:       ['zustand','react-hook-form','react-hot-toast'],
        },
      },
    },
  },
  define: {
    'import.meta.env.VITE_API_URL': JSON.stringify(process.env.VITE_API_URL ?? ''),
  },
});

// ── tsconfig.json (inline as comment) ────────────────────────
// {
//   "compilerOptions": {
//     "target": "ES2020",
//     "lib": ["ES2020","DOM","DOM.Iterable"],
//     "module": "ESNext",
//     "skipLibCheck": true,
//     "moduleResolution": "bundler",
//     "allowImportingTsExtensions": true,
//     "resolveJsonModule": true,
//     "isolatedModules": true,
//     "noEmit": true,
//     "jsx": "react-jsx",
//     "strict": true,
//     "noUnusedLocals": true,
//     "noUnusedParameters": true,
//     "noFallthroughCasesInSwitch": true
//   },
//   "include": ["src"],
//   "references": [{ "path": "./tsconfig.node.json" }]
// }
