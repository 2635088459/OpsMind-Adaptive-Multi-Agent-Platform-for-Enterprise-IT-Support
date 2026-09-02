import { create } from "zustand";

export type AttachmentStatus = "validating" | "uploading" | "ready" | "failed";

export interface StagedAttachment {
  id: string;
  fileName: string;
  status: AttachmentStatus;
  ref: string | null;
  errorMessage: string | null;
}

interface AttachmentState {
  attachments: StagedAttachment[];
  stage: (id: string, fileName: string) => void;
  markUploading: (id: string) => void;
  markReady: (id: string, ref: string) => void;
  markFailed: (id: string, errorMessage: string) => void;
  remove: (id: string) => void;
  reset: () => void;
}

/**
 * SPEC-EP-010 §3.2: the full attachment state machine
 * (VALIDATING → UPLOADING → READY/FAILED). BI-EP-002's own enforcement
 * point lives in the composer (only `ready` refs are ever sent), not here —
 * this store is pure bookkeeping of what's staged.
 */
export const useAttachmentStore = create<AttachmentState>((set) => ({
  attachments: [],

  stage: (id, fileName) =>
    set((state) => ({ attachments: [...state.attachments, { id, fileName, status: "validating", ref: null, errorMessage: null }] })),

  markUploading: (id) =>
    set((state) => ({ attachments: state.attachments.map((a) => (a.id === id ? { ...a, status: "uploading" } : a)) })),

  markReady: (id, ref) =>
    set((state) => ({ attachments: state.attachments.map((a) => (a.id === id ? { ...a, status: "ready", ref } : a)) })),

  markFailed: (id, errorMessage) =>
    set((state) => ({ attachments: state.attachments.map((a) => (a.id === id ? { ...a, status: "failed", errorMessage } : a)) })),

  remove: (id) => set((state) => ({ attachments: state.attachments.filter((a) => a.id !== id) })),

  reset: () => set({ attachments: [] }),
}));
