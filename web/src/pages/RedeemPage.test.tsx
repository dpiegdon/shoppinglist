import { cleanup, render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { afterEach, describe, expect, it, vi } from "vitest";
import RedeemPage from "./RedeemPage";
import { SyncProvider } from "../hooks/SyncContext";
import * as api from "../api/client";

vi.mock("../api/client", async () => {
  const actual = await vi.importActual<typeof api>("../api/client");
  return { ...actual, sync: vi.fn(), redeemInvite: vi.fn() };
});

afterEach(() => {
  vi.clearAllMocks();
  cleanup();
});

describe("RedeemPage", () => {
  it("redeems the token of an invite link pasted inside a whole message (T-301)", async () => {
    vi.mocked(api.redeemInvite).mockResolvedValue({ list_id: "list-1" } as Awaited<
      ReturnType<typeof api.redeemInvite>
    >);
    vi.mocked(api.sync).mockResolvedValue({ cursor: 1, changes: { lists: [], items: [] } } as Awaited<
      ReturnType<typeof api.sync>
    >);
    render(
      <MemoryRouter initialEntries={["/redeem"]}>
        <SyncProvider>
          <Routes>
            <Route path="/redeem" element={<RedeemPage />} />
            <Route path="/list/:listId" element={<p>list page</p>} />
          </Routes>
        </SyncProvider>
      </MemoryRouter>,
    );

    await userEvent.type(
      screen.getByLabelText("Invite code or link"),
      "Get the app from https://p23q.org/app and join my list (https://p23q.org/shopping/invite/abc.def). Thanks!",
    );
    await userEvent.click(screen.getByRole("button", { name: "Join list" }));

    expect(await screen.findByText("list page")).toBeTruthy();
    expect(api.redeemInvite).toHaveBeenCalledWith("abc.def");
  });
});
