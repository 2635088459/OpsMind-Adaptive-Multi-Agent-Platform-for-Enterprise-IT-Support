import { useRef } from "react";
import { useAttachmentStore } from "@/features/attachment/attachmentStore";
import { useUploadAttachment } from "@/features/attachment/useUploadAttachment";

const STATUS_LABEL: Record<string, string> = {
  validating: "Checking…",
  uploading: "Uploading…",
  ready: "Ready",
  failed: "Failed",
};

/** SPEC-EP-010: the file-picker affordance plus each staged attachment's own progress. */
export function AttachmentPicker() {
  const attachments = useAttachmentStore((state) => state.attachments);
  const remove = useAttachmentStore((state) => state.remove);
  const upload = useUploadAttachment();
  const inputRef = useRef<HTMLInputElement>(null);

  return (
    <div>
      <input
        ref={inputRef}
        type="file"
        aria-label="Attach a file"
        className="hidden"
        onChange={(event) => {
          const file = event.target.files?.[0];
          if (file) void upload(file);
          event.target.value = "";
        }}
      />
      <button
        type="button"
        onClick={() => inputRef.current?.click()}
        className="rounded-md border border-border bg-surface px-3 py-1.5 text-sm font-medium text-ink hover:bg-surface-muted"
      >
        Attach file
      </button>

      {attachments.length > 0 ? (
        <ul className="mt-2 flex flex-col gap-1">
          {attachments.map((attachment) => (
            <li key={attachment.id} className="flex items-center gap-2 text-sm text-ink-muted" data-testid="staged-attachment" data-status={attachment.status}>
              <span>{attachment.fileName}</span>
              <span className={attachment.status === "failed" ? "text-danger" : ""}>
                {attachment.status === "failed" ? attachment.errorMessage : STATUS_LABEL[attachment.status]}
              </span>
              <button type="button" onClick={() => remove(attachment.id)} className="text-xs text-ink-muted underline">
                Remove
              </button>
            </li>
          ))}
        </ul>
      ) : null}
    </div>
  );
}
