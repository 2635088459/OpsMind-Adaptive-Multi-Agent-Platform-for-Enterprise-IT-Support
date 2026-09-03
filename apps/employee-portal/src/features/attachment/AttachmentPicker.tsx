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
        aria-label="Attach a photo or file"
        className="flex size-[34px] shrink-0 items-center justify-center rounded-[9px] border border-border bg-surface text-ink-muted hover:bg-surface-muted"
      >
        <span aria-hidden="true">＋</span>
      </button>

      {attachments.length > 0 ? (
        <ul className="mt-2 flex flex-col gap-1.5">
          {attachments.map((attachment) => (
            <li
              key={attachment.id}
              className="inline-flex w-fit items-center gap-2 rounded-lg border border-border bg-surface py-1.5 pr-2.5 pl-1.5 text-xs text-ink-muted"
              data-testid="staged-attachment"
              data-status={attachment.status}
            >
              <span className="size-[22px] shrink-0 rounded-[5px] bg-gradient-to-br from-border to-faint" aria-hidden="true" />
              <span>{attachment.fileName}</span>
              <span className={attachment.status === "failed" ? "text-danger" : "text-faint"}>
                {attachment.status === "failed" ? attachment.errorMessage : STATUS_LABEL[attachment.status]}
              </span>
              <button type="button" onClick={() => remove(attachment.id)} className="text-ink-muted underline">
                Remove
              </button>
            </li>
          ))}
        </ul>
      ) : null}
    </div>
  );
}
