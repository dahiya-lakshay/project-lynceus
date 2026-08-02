// Shared ESLint flat-config rules layered on top of each app's own
// framework-specific config (e.g. eslint-config-next). Centralizing the
// "no any, no unused imports" rules here means every app in the workspace
// enforces AGENTS.md's TypeScript conventions identically, instead of each
// app's eslint.config.mjs re-declaring them and drifting over time.
const baseConfig = [
  {
    rules: {
      "@typescript-eslint/no-explicit-any": "error",
      "@typescript-eslint/no-unused-vars": [
        "error",
        { argsIgnorePattern: "^_", varsIgnorePattern: "^_" },
      ],
      "no-unused-vars": "off",
    },
  },
];

module.exports = baseConfig;
