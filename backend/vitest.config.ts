import { defineConfig } from 'vitest/config'

export default defineConfig({
  test: {
    environment: 'node',
    include: ['test/**/*.test.ts'],
    // Cada test construye su propia app con repos en memoria, así que pueden
    // correr en paralelo sin pisarse.
    globals: false,
  },
})
