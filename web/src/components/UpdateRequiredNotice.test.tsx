import { act, render, screen } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import App from "../App";
import UpdateRequiredNotice from "./UpdateRequiredNotice";
import { RELOAD_STORAGE_KEY, reportClientOutdated, resetClientOutdatedForTests } from "../api/protocol";

/** T-244: the full-page notice, and the app root that puts it in place of everything else. */
describe("the update-required notice", () => {
  beforeEach(() => {
    // The login page probes the server on mount; keep it off the network.
    globalThis.fetch = vi.fn().mockResolvedValue({
      status: 200,
      ok: true,
      headers: new Headers({ "content-type": "application/json" }),
      json: async () => ({ allow_registration: true }),
    } as Response);
  });

  afterEach(() => {
    resetClientOutdatedForTests();
    sessionStorage.removeItem(RELOAD_STORAGE_KEY);
    vi.restoreAllMocks();
  });

  it("says what happened and offers a reload", () => {
    render(<UpdateRequiredNotice />);

    expect(screen.getByRole("alert")).toHaveTextContent("Update required");
    expect(
      screen.getByText("The server has been updated. Reload this page to continue."),
    ).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Reload" })).toBeInTheDocument();
  });

  it("replaces the whole app once a second 426 has raised it, so nothing keeps syncing", () => {
    // The first refusal is handled by the automatic reload; the reloaded page is a fresh module
    // over the same sessionStorage, which resetting the module state models.
    reportClientOutdated(vi.fn(), 1_000_000);
    resetClientOutdatedForTests();
    render(
      <MemoryRouter>
        <App />
      </MemoryRouter>,
    );
    expect(screen.queryByText("Update required")).not.toBeInTheDocument();
    expect(screen.getByLabelText("Email")).toBeInTheDocument();

    // A second refusal inside the window: the routes go, the notice takes over.
    act(() => {
      reportClientOutdated(vi.fn(), 1_000_001);
    });

    expect(screen.getByText("Update required")).toBeInTheDocument();
    // Nothing of the app is left on screen to make another request.
    expect(screen.queryByLabelText("Email")).not.toBeInTheDocument();
  });

  it("is already up when the refusal beat the app to the screen", () => {
    // The first sync starts from a mount effect, so a 426 can be raised before the root mounts.
    reportClientOutdated(vi.fn(), 1_000_000);
    resetClientOutdatedForTests(); // the reloaded page
    reportClientOutdated(vi.fn(), 1_000_001);

    render(
      <MemoryRouter>
        <App />
      </MemoryRouter>,
    );

    expect(screen.getByText("Update required")).toBeInTheDocument();
  });
});
