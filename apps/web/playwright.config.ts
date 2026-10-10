import { defineConfig } from "@playwright/test";

// The Java IT owns the real Core, ephemeral database and isolated production web server.
export default defineConfig({
  testDir: "./tests/browser",
  forbidOnly: true,
  fullyParallel: false,
  workers: 1,
  retries: 0,
  maxFailures: 1,
  timeout: 60_000,
  // Fixed 18-case cohort (formerly 12); per-test/action deadlines and zero retries stay unchanged.
  globalTimeout: 360_000,
  reporter: "line",
  use: { baseURL: "http://localhost:3000", headless: true, trace: "off", screenshot: "only-on-failure",
    actionTimeout: 10_000, navigationTimeout: 15_000 },
  projects: [
    { name: "desktop", use: { browserName: "chromium", viewport: { width: 1440, height: 1000 } } },
    { name: "mobile", use: { browserName: "chromium", viewport: { width: 390, height: 844 }, isMobile: true, hasTouch: true } },
  ],
});
