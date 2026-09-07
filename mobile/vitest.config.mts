import { defineConfig } from 'vitest/config';

export default defineConfig({
  test: {
    // Ionic publishes browser directory imports which Node's native ESM loader
    // cannot resolve. Let Vite resolve these real modules for jsdom tests.
    server: {
      deps: { inline: [/@ionic\//, /@stencil\//, /ionicons/] }
    }
  }
});
