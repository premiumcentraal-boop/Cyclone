import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

export default defineConfig({
  plugins: [react()],
  clearScreen: false,
  server: {
    port: 1420,
    strictPort: true,
    host: "127.0.0.1",
  },
  envPrefix: ["VITE_"],
  test: {
    // Keep test discovery inside src/ — browser profiles and other local
    // tool artifacts (e.g. .visual/) can contain *.test.* files that are not
    // part of the suite.
    include: ["src/**/*.test.ts"],
  },
});
