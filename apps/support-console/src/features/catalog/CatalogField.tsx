import { useId, useState } from "react";
import { type CatalogEntry, findEntry } from "@/features/catalog/catalog";

const _CUSTOM = "__custom__";

/**
 * A labelled picker over a {@link CatalogEntry} list with an always-available
 * "Enter a different ID…" escape hatch. Replaces the raw UUID `<input>`s the
 * triage / assignment forms used to show — an operator picks "Network Support
 * Queue" instead of pasting `33333333-…`, but nothing that was possible before
 * is taken away. The value handed up is always the real backend id string.
 */
export function CatalogField({
  label,
  entries,
  value,
  onChange,
  optional = false,
  help,
  customPlaceholder = "Paste a UUID",
}: {
  label: string;
  entries: CatalogEntry[];
  value: string;
  onChange: (value: string) => void;
  optional?: boolean;
  help?: string;
  customPlaceholder?: string;
}) {
  const selectId = useId();
  const isKnown = findEntry(entries, value) !== undefined;
  // Once the operator switches to "custom" we stay there even while the field
  // is briefly empty, so the raw input doesn't vanish mid-typing.
  const [customMode, setCustomMode] = useState(value !== "" && !isKnown);
  const showCustom = customMode || (value !== "" && !isKnown);
  const selectValue = showCustom ? _CUSTOM : value;
  const resolved = findEntry(entries, value);

  return (
    <div className="flex flex-col gap-1.5">
      <label htmlFor={selectId} className="text-sm font-medium text-ink">
        {label}
        {optional ? <span className="ml-1 font-normal text-ink-muted">(optional)</span> : null}
      </label>
      {help ? <p className="text-xs leading-relaxed text-ink-muted">{help}</p> : null}

      <select
        id={selectId}
        aria-label={label}
        value={selectValue}
        onChange={(e) => {
          if (e.target.value === _CUSTOM) {
            setCustomMode(true);
            onChange("");
          } else {
            setCustomMode(false);
            onChange(e.target.value);
          }
        }}
        className="rounded-md border border-border bg-surface px-3 py-2 text-sm text-ink focus:border-brand-500 focus:outline-none"
      >
        <option value="">{optional ? "— none —" : "Select…"}</option>
        {entries.map((entry) => (
          <option key={entry.id} value={entry.id}>
            {entry.label}
            {entry.hint ? ` · ${entry.hint}` : ""}
          </option>
        ))}
        <option value={_CUSTOM}>Enter a different ID…</option>
      </select>

      {showCustom ? (
        <input
          aria-label={`${label} — custom ID`}
          value={value}
          onChange={(e) => onChange(e.target.value)}
          placeholder={customPlaceholder}
          className="rounded-md border border-border bg-surface px-3 py-2 font-mono text-xs text-ink focus:border-brand-500 focus:outline-none"
        />
      ) : resolved ? (
        <p className="font-mono text-[0.7rem] text-ink-muted">→ {resolved.id}</p>
      ) : null}
    </div>
  );
}
