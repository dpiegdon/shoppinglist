import { render, screen, waitFor, cleanup, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router-dom";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import SettingsPage from "./SettingsPage";
import { AuthProvider } from "../auth/AuthContext";
import * as api from "../api/client";

vi.mock("../api/client", async () => {
  const actual = await vi.importActual<typeof api>("../api/client");
  return {
    ...actual,
    getToken: vi.fn(() => null),
    getSettings: vi.fn(),
    updateSettings: vi.fn(),
    listSessions: vi.fn(),
  };
});

function renderSettingsPage() {
  return render(
    <MemoryRouter>
      <AuthProvider>
        <SettingsPage />
      </AuthProvider>
    </MemoryRouter>,
  );
}

function currencySection() {
  return screen.getByRole("heading", { name: "Default currency" }).closest("section") as HTMLElement;
}

function initialsSection() {
  return screen.getByRole("heading", { name: "Display initials" }).closest("section") as HTMLElement;
}

function getCurrencySaveButton() {
  return within(currencySection()).getByRole("button", { name: "Save" });
}

function getInitialsInput() {
  return within(initialsSection()).getByRole("textbox");
}

function getInitialsSaveButton() {
  return within(initialsSection()).getByRole("button", { name: "Save" });
}

describe("SettingsPage currency save vs. unresolved initials preload (T-101)", () => {
  beforeEach(() => {
    vi.mocked(api.getToken).mockReturnValue(null);
    vi.mocked(api.listSessions).mockResolvedValue({ sessions: [] });
  });

  afterEach(() => {
    vi.clearAllMocks();
    cleanup();
  });

  it("omits the initials key when saving currency before the settings preload resolves", async () => {
    // Preload never resolves during this test - simulates a slow/in-flight request racing the save.
    vi.mocked(api.getSettings).mockReturnValue(new Promise(() => {}));
    vi.mocked(api.updateSettings).mockResolvedValue({ default_currency: "USD", initials: "" });

    renderSettingsPage();

    await userEvent.click(getCurrencySaveButton());

    await waitFor(() => expect(api.updateSettings).toHaveBeenCalled());
    const body = vi.mocked(api.updateSettings).mock.calls[0][0];
    expect(body).not.toHaveProperty("initials");
  });

  it("omits the initials key when the settings preload has failed", async () => {
    vi.mocked(api.getSettings).mockRejectedValue(new Error("network down"));
    vi.mocked(api.updateSettings).mockResolvedValue({ default_currency: "USD", initials: "" });

    renderSettingsPage();

    await userEvent.click(getCurrencySaveButton());

    await waitFor(() => expect(api.updateSettings).toHaveBeenCalled());
    const body = vi.mocked(api.updateSettings).mock.calls[0][0];
    expect(body).not.toHaveProperty("initials");
  });

  it("preserves a resolved custom initials override across a currency-only save", async () => {
    vi.mocked(api.getSettings).mockResolvedValue({ default_currency: "EUR", initials: "CZ" });
    vi.mocked(api.updateSettings).mockResolvedValue({ default_currency: "USD", initials: "CZ" });

    renderSettingsPage();

    // Let the preload resolve into the initials field before saving currency.
    await waitFor(() => expect(getInitialsInput()).toHaveValue("CZ"));

    await userEvent.click(getCurrencySaveButton());

    await waitFor(() => expect(api.updateSettings).toHaveBeenCalled());
    const body = vi.mocked(api.updateSettings).mock.calls[0][0];
    // Either omitted, or sent as the now-known-good resolved value - never a blank
    // string that would clobber the override server-side.
    if ("initials" in body) {
      expect(body.initials).toBe("CZ");
    }
    // The override must still be showing on screen afterwards, not wiped to blank.
    await waitFor(() => expect(getInitialsInput()).toHaveValue("CZ"));
  });

  it("still sends initials on an explicit initials edit+save", async () => {
    vi.mocked(api.getSettings).mockResolvedValue({ default_currency: "EUR", initials: "AB" });
    vi.mocked(api.updateSettings).mockResolvedValue({ default_currency: "EUR", initials: "ZZ" });

    renderSettingsPage();

    await waitFor(() => expect(getInitialsInput()).toHaveValue("AB"));

    await userEvent.clear(getInitialsInput());
    await userEvent.type(getInitialsInput(), "zz");
    await userEvent.click(getInitialsSaveButton());

    await waitFor(() => expect(api.updateSettings).toHaveBeenCalled());
    const body = vi.mocked(api.updateSettings).mock.calls[0][0];
    expect(body.initials).toBe("ZZ");
    expect(body.default_currency).toBe("EUR");
  });
});
