import type { NextConfig } from "next";

const nextConfig: NextConfig = {
  // Vercel packages the app through its adapter; standalone is for self-hosting.
  output: process.env.VERCEL ? undefined : "standalone",
  poweredByHeader: false,
  reactStrictMode: true,
};

export default nextConfig;
