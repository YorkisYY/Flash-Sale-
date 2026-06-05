import { heroui } from "@heroui/react";
import type { Config } from "tailwindcss";

const config: Config = {
  content: [
    "./app/**/*.{js,ts,jsx,tsx,mdx}",
    "./node_modules/@heroui/theme/dist/**/*.{js,ts,jsx,tsx}",
  ],
  theme: {
    extend: {},
  },
  darkMode: "class",
  // HeroUI bundles its own tailwindcss types which clash with the root
  // tailwindcss 3.x PluginAPI shape (missing `prefix`). The plugin works
  // fine at runtime; this cast just shuts the type checker up.
  plugins: [heroui() as never],
};

export default config;
