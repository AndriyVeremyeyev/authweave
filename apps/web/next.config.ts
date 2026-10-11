import type { NextConfig } from "next";

const nextConfig: NextConfig = {
  // Vercel packages the app through its adapter; standalone is for self-hosting.
  output: process.env.VERCEL ? undefined : "standalone",
  poweredByHeader: false,
  reactStrictMode: true,
  // Incoming URLs can contain one-use OIDC codes/state, including encoded paths.
  // Keep other development diagnostics; never print incoming request URLs.
  logging: { incomingRequests: false },
};

export default nextConfig;
