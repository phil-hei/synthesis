// @ts-check
const eslint = require("@eslint/js");
const { defineConfig } = require("eslint/config");
const tseslint = require("typescript-eslint");
const angular = require("angular-eslint");

module.exports = defineConfig([
  {
    files: ["**/*.ts"],
    extends: [
      eslint.configs.recommended,
      tseslint.configs.recommended,
      tseslint.configs.stylistic,
      angular.configs.tsRecommended,
    ],
    processor: angular.processInlineTemplates,
    rules: {
      "@angular-eslint/directive-selector": [
        "error",
        {
          type: "attribute",
          prefix: "app",
          style: "camelCase",
        },
      ],
      "@angular-eslint/component-selector": [
        "error",
        {
          type: "element",
          prefix: "app",
          style: "kebab-case",
        },
      ],
      // Unterstrich-Präfix markiert bewusst ungenutzte Parameter (z. B. in Mocks).
      "@typescript-eslint/no-unused-vars": ["error", { argsIgnorePattern: "^_", varsIgnorePattern: "^_" }],
    },
  },
  {
    // Specs dürfen Testdaten lose typisieren; das Produktiv-Bundle bleibt streng.
    files: ["**/*.spec.ts"],
    rules: { "@typescript-eslint/no-explicit-any": "off" },
  },
  {
    files: ["**/*.html"],
    extends: [
      angular.configs.templateRecommended,
      angular.configs.templateAccessibility,
    ],
    rules: {
      // `!= null` fängt null und undefined in einem Vergleich; in Vorlagen hier gewollt.
      "@angular-eslint/template/eqeqeq": ["error", { allowNullOrUndefined: true }],
    },
  },
  {
    // Die Anmeldeseite setzt den Fokus bewusst ins erste Feld; sie hat keinen anderen Inhalt.
    // Inline-Vorlagen werden als virtuelle .html-Dateien unterhalb der .ts-Datei geprüft.
    files: ["**/login/login.component.ts/**"],
    rules: { "@angular-eslint/template/no-autofocus": "off" },
  },
]);
