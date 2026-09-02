/**
 * SPEC-EP-011: the `VALIDATING` state's own decision, purely from
 * client-visible `File` metadata — advisory only (§14: never the real
 * security boundary; that's the shared attachments capability's own
 * server-side concern, not yet designed). Values sourced from that
 * capability's own published contract; since no such contract exists yet
 * (SPEC-EP-010 §6 non-goals), these are a reasonable, explicitly-labeled
 * placeholder allow-list/limit, not a real published spec value — swap
 * these two constants the moment that capability's own contract lands.
 */
export const ALLOWED_MIME_TYPES = ["image/png", "image/jpeg", "image/webp", "application/pdf"];
export const MAX_FILE_SIZE_BYTES = 25 * 1024 * 1024; // 25 MB

export type ValidationFailureReason = "unsupported-type" | "too-large";

export interface ValidationResult {
  valid: boolean;
  reason?: ValidationFailureReason;
  message?: string;
}

/** BI-EP-002: a rejected file gets a specific, distinct, employee-visible reason — never a generic error. */
export function validateAttachment(file: File): ValidationResult {
  if (!ALLOWED_MIME_TYPES.includes(file.type)) {
    return { valid: false, reason: "unsupported-type", message: `Unsupported file type: ${file.type || "unknown"}.` };
  }
  if (file.size > MAX_FILE_SIZE_BYTES) {
    return { valid: false, reason: "too-large", message: `File too large (max ${MAX_FILE_SIZE_BYTES / (1024 * 1024)} MB).` };
  }
  return { valid: true };
}
