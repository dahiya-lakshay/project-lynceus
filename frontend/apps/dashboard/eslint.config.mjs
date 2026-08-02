import nextCoreWebVitals from "eslint-config-next/core-web-vitals";
import nextTypescript from "eslint-config-next/typescript";
import sharedConfig from "@lynceus/config-eslint";

// eslint-config-next@16 ships native ESLint 9 flat-config arrays (see its
// dist/*.d.ts: `Linter.Config[]`), so this goes through directly rather
// than via @eslint/eslintrc's FlatCompat legacy bridge — FlatCompat's
// legacy-config validator throws ("Converting circular structure to JSON")
// on eslint-plugin-react's self-referencing config object with this
// combination of eslint-plugin-react/eslint-config-next versions, and the
// flat exports sidestep that translation layer entirely.
const eslintConfig = [
  ...nextCoreWebVitals,
  ...nextTypescript,
  ...sharedConfig,
  {
    ignores: [".next/**", "node_modules/**", "next-env.d.ts"],
  },
];

export default eslintConfig;
