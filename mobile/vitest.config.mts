import { defineConfig } from 'vitest/config';

export default defineConfig({
  test: {
    // Suites mock Capacitor's global plugin registry independently. Reusing
    // module instances leaks those mocks between files as worker counts change.
    isolate: true,
    // Ionic publishes browser directory imports which Node's native ESM loader
    // cannot resolve. Let Vite resolve these real modules for jsdom tests.
    server: {
      deps: { inline: [/@ionic\//, /@stencil\//, /ionicons/] }
    }
  }
});
