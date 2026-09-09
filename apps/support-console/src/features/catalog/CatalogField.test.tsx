import { describe, it, expect, vi } from "vitest";
import { useState } from "react";
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { CatalogField } from "@/features/catalog/CatalogField";
import type { CatalogEntry } from "@/features/catalog/catalog";

const ENTRIES: CatalogEntry[] = [
  { id: "id-1", label: "First", hint: "one" },
  { id: "id-2", label: "Second" },
];

function Harness({ onChange }: { onChange: (v: string) => void }) {
  const [value, setValue] = useState("");
  return (
    <CatalogField
      label="Thing"
      entries={ENTRIES}
      value={value}
      onChange={(v) => {
        setValue(v);
        onChange(v);
      }}
    />
  );
}

describe("CatalogField", () => {
  it("hands up the real id when a catalog entry is picked", async () => {
    const onChange = vi.fn();
    const user = userEvent.setup();
    render(<Harness onChange={onChange} />);

    await user.selectOptions(screen.getByLabelText("Thing"), "id-2");

    expect(onChange).toHaveBeenLastCalledWith("id-2");
    expect(screen.getByText("→ id-2")).toBeInTheDocument();
  });

  it("reveals a raw input when 'Enter a different ID…' is chosen and passes typed text straight through", async () => {
    const onChange = vi.fn();
    const user = userEvent.setup();
    render(<Harness onChange={onChange} />);

    await user.selectOptions(screen.getByLabelText("Thing"), "__custom__");
    const custom = screen.getByLabelText("Thing — custom ID");
    await user.type(custom, "99999999-0000");

    expect(onChange).toHaveBeenLastCalledWith("99999999-0000");
  });
});
