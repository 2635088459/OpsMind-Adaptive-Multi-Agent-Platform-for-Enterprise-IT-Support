import { describe, it, expect, beforeEach } from "vitest";
import { usePortalViewStore } from "@/store/portalViewStore";

describe("portalViewStore", () => {
  beforeEach(() => {
    usePortalViewStore.setState({ view: "conversation" });
  });

  it("defaults to the conversation view", () => {
    expect(usePortalViewStore.getState().view).toBe("conversation");
  });

  it("switches to the new-ticket form and back", () => {
    usePortalViewStore.getState().showNewTicket();
    expect(usePortalViewStore.getState().view).toBe("newTicket");

    usePortalViewStore.getState().showConversation();
    expect(usePortalViewStore.getState().view).toBe("conversation");
  });
});
