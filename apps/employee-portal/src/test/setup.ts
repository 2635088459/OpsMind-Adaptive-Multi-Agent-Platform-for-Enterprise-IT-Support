import "@testing-library/jest-dom/vitest";
import { afterEach } from "vitest";
import { cleanup } from "@testing-library/react";

// React Testing Library's own automatic post-test cleanup only self-registers
// when it detects a GLOBAL `afterEach` (Jest provides one; vitest only does
// with `test.globals: true`, which this project's vite.config.ts deliberately
// leaves false — explicit imports over ambient globals). Real bug found live:
// without this, a second render() in the same file leaves the first test's
// DOM mounted alongside it (AuthGate.test.tsx's two "Welcome" headings both
// present at once, failing `findByRole` on ambiguity).
afterEach(() => {
  cleanup();
});
