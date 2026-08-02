import type { NextConfig } from "next";

// `output: "standalone"` produces a minimal, self-contained server bundle
// (.next/standalone) that copies only the production node_modules subset
// actually needed at runtime. The multi-stage Dockerfile copies that output
// directly rather than shipping the full workspace + node_modules into the
// final image layer.
const nextConfig: NextConfig = {
  output: "standalone",
};

export default nextConfig;
