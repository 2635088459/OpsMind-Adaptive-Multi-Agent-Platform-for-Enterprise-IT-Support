import js from "@eslint/js";
import globals from "globals";
import reactHooks from "eslint-plugin-react-hooks";
import reactRefresh from "eslint-plugin-react-refresh";
import tseslint from "typescript-eslint";
import { globalIgnores } from "eslint/config";

export default tseslint.config(
  globalIgnores(["dist", "coverage", "playwright-report"]),
  {
    files: ["**/*.{ts,tsx}"],
    // eslint-plugin-react-hooks@7.1.1's own `configs["recommended-latest"]` still
    // exports the pre-flat-config shape (`plugins: ["react-hooks"]` as a string
    // array) — a real, current bug in that package version, not usable directly
    // under ESLint 10's flat-config-only loader. Declaring the plugin object and
    // its recommended rules explicitly works around it without losing the rules.
    extends: [js.configs.recommended, tseslint.configs.recommended, reactRefresh.configs.vite],
    plugins: {
      "react-hooks": reactHooks,
    },
    rules: reactHooks.configs.recommended.rules,
    languageOptions: {
      ecmaVersion: 2023,
      globals: globals.browser,
    },
  },
);
