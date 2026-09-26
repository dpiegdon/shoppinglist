import { render, screen, waitFor, cleanup, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router-dom";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import SettingsPage from "./SettingsPage";
import { AuthProvider } from "../auth/AuthContext";
import * as api from "../api/client";
import { en } from "../i18n/messages/en";

vi.mock("../api/client", async () => {
  const actual = await vi.importActual<typeof api>("../api/client");
  return {
    ...actual,
    getToken: vi.fn(() => null),
    getSettings: vi.fn(),
    updateSettings: vi.fn(),
    listSessions: vi.fn(),
    changePassword: vi.fn(),
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

// Sections are located through the catalog rather than by literal text: these tests are about
// which section a save came from, not about its wording, and a hardcoded copy of the heading
// silently turns a copy edit into a test failure (it already did once, for "Displayed initials").
function currencySection() {
  return screen
    .getByRole("heading", { name: en["settings.defaultCurrency"] })
    .closest("section") as HTMLElement;
}

function initialsSection() {
  return screen
    .getByRole("heading", { name: en["settings.initials"] })
    .closest("section") as HTMLElement;
}

function getCurrencySaveButton() {
  return within(currencySection()).getByRole("button", { name: en["action.save"] });
}

function getInitialsInput() {
  return within(initialsSection()).getByRole("textbox");
}

function getInitialsSaveButton() {
  return within(initialsSection()).getByRole("button", { name: en["action.save"] });
}

describe("SettingsPage currency save must never write initials (T-101, T-103)", () => {
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
    // The currency form never sends initials at all (T-103), resolved or not — the server
    // leaves an absent key unchanged (T-87).
    expect(body).not.toHaveProperty("initials");
    // The override must still be showing on screen afterwards, not wiped to blank.
    await waitFor(() => expect(getInitialsInput()).toHaveValue("CZ"));
  });

  it("does not pin the email-derived default as an override on a currency save (T-103)", async () => {
    // The bug this guards: GET /settings returns the *resolved* value, so an account with no
    // override reads back the derived default ("BO" for bob@…) looking exactly like a stored
    // one. Echoing it back on a currency save stored it for real, so a later email change no
    // longer re-derived the initials.
    vi.mocked(api.getSettings).mockResolvedValue({ default_currency: "EUR", initials: "BO" });
    vi.mocked(api.updateSettings).mockResolvedValue({ default_currency: "USD", initials: "BO" });

    renderSettingsPage();
    await waitFor(() => expect(getInitialsInput()).toHaveValue("BO"));

    await userEvent.click(getCurrencySaveButton());

    await waitFor(() => expect(api.updateSettings).toHaveBeenCalled());
    expect(vi.mocked(api.updateSettings).mock.calls[0][0]).not.toHaveProperty("initials");
  });

  it("still sends initials on an explicit initials edit+save, and never default_currency (T-272)", async () => {
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
    // Omitted, not merely equal to the resolved value (T-272): the server treats an absent key as
    // "leave unchanged", the same convention the currency form already relies on for `initials`.
    expect(body).not.toHaveProperty("default_currency");
  });

  it("does not submit an unsaved edit sitting in the currency box when saving initials (T-272)", async () => {
    vi.mocked(api.getSettings).mockResolvedValue({ default_currency: "EUR", initials: "AB" });
    vi.mocked(api.updateSettings).mockResolvedValue({ default_currency: "EUR", initials: "ZZ" });

    renderSettingsPage();

    await waitFor(() => expect(getInitialsInput()).toHaveValue("AB"));

    // Typed into the currency box but never saved there.
    const currencyInput = within(currencySection()).getByRole("textbox");
    await userEvent.clear(currencyInput);
    await userEvent.type(currencyInput, "pizza slices");

    await userEvent.clear(getInitialsInput());
    await userEvent.type(getInitialsInput(), "zz");
    await userEvent.click(getInitialsSaveButton());

    await waitFor(() => expect(api.updateSettings).toHaveBeenCalled());
    const body = vi.mocked(api.updateSettings).mock.calls[0][0];
    expect(body).not.toHaveProperty("default_currency");
  });
});

describe("SettingsPage no longer holds the server console (T-220)", () => {
  beforeEach(() => {
    localStorage.clear();
    vi.mocked(api.getToken).mockReturnValue("tok");
    vi.mocked(api.listSessions).mockResolvedValue({ sessions: [] });
    vi.mocked(api.getSettings).mockResolvedValue({ default_currency: "EUR", initials: "BO" });
  });

  afterEach(() => {
    vi.clearAllMocks();
    cleanup();
  });

  it("offers an admin no route to /admin — that entry lives in the main menu now", async () => {
    // An admin account: before T-220 this is exactly who saw the section here.
    localStorage.setItem(
      "shoppinglist_account",
      JSON.stringify({ id: "admin-1", email: "boss@example.com", isAdmin: true }),
    );

    renderSettingsPage();
    await waitFor(() => expect(getInitialsInput()).toHaveValue("BO"));

    expect(screen.queryByRole("link", { name: en["nav.serverAdmin"] })).not.toBeInTheDocument();
    expect(screen.queryByRole("link", { name: /admin/i })).not.toBeInTheDocument();
  });
});

describe("SettingsPage keeps account preferences only (T-224)", () => {
  beforeEach(() => {
    localStorage.clear();
    vi.mocked(api.getToken).mockReturnValue("tok");
    vi.mocked(api.listSessions).mockResolvedValue({ sessions: [] });
    vi.mocked(api.getSettings).mockResolvedValue({ default_currency: "EUR", initials: "BO" });
  });

  afterEach(() => {
    vi.clearAllMocks();
    cleanup();
  });

  it("says nothing about the version or the app itself — that is the About page", async () => {
    // This page never carried a version line, where Android's did; its one moved to About with
    // the update block. The check is here so the two settings screens keep saying the same thing.
    renderSettingsPage();
    await waitFor(() => expect(getInitialsInput()).toHaveValue("BO"));

    expect(screen.queryByText(/version/i)).not.toBeInTheDocument();
    expect(screen.queryByText(en["about.tagline"])).not.toBeInTheDocument();
    expect(screen.queryByText(en["about.license"])).not.toBeInTheDocument();
  });
});

describe("SettingsPage asks for the new password twice (T-313)", () => {
  beforeEach(() => {
    vi.mocked(api.getToken).mockReturnValue(null);
    vi.mocked(api.getSettings).mockReturnValue(new Promise(() => {}));
    vi.mocked(api.listSessions).mockResolvedValue({ sessions: [] });
    vi.mocked(api.changePassword).mockResolvedValue(undefined);
  });

  afterEach(() => {
    vi.clearAllMocks();
    cleanup();
  });

  async function fillPasswords(current: string, next: string, again: string) {
    await userEvent.type(screen.getByLabelText(en["settings.currentPassword"]), current);
    await userEvent.type(screen.getByLabelText(en["settings.newPassword"]), next);
    await userEvent.type(screen.getByLabelText(en["settings.newPasswordAgain"]), again);
  }

  const submit = () => userEvent.click(screen.getByRole("button", { name: en["settings.changePassword"] }));

  it("sends nothing when the two differ, says so at the repeat field, and clears that on typing", async () => {
    renderSettingsPage();
    await fillPasswords("oldpassword", "newpassword1", "newpassword2");

    await submit();

    expect(api.changePassword).not.toHaveBeenCalled();
    const again = screen.getByLabelText(en["settings.newPasswordAgain"]);
    expect(again).toHaveAttribute("aria-invalid", "true");
    expect(screen.getByRole("alert")).toHaveTextContent("The passwords do not match.");
    expect(again).toHaveAttribute("aria-describedby", screen.getByRole("alert").id);

    // Editing either new-password field takes the complaint away.
    await userEvent.type(again, "x");
    expect(screen.queryByText("The passwords do not match.")).not.toBeInTheDocument();
    await submit();
    expect(screen.getByText("The passwords do not match.")).toBeInTheDocument();
    await userEvent.type(screen.getByLabelText(en["settings.newPassword"]), "x");
    expect(screen.queryByText("The passwords do not match.")).not.toBeInTheDocument();
    expect(api.changePassword).not.toHaveBeenCalled();
  });

  it("sends the one new password as before when the two match", async () => {
    renderSettingsPage();
    await fillPasswords("oldpassword", "newpassword1", "newpassword1");

    await submit();

    await waitFor(() =>
      expect(api.changePassword).toHaveBeenCalledWith({ current_password: "oldpassword", new_password: "newpassword1" }),
    );
    expect(screen.queryByText("The passwords do not match.")).not.toBeInTheDocument();
    await waitFor(() => expect(screen.getByLabelText(en["settings.newPasswordAgain"])).toHaveValue(""));
  });
});

describe("SettingsPage names a session without a device label (T-316)", () => {
  beforeEach(() => {
    localStorage.clear();
    vi.mocked(api.getToken).mockReturnValue("tok");
    vi.mocked(api.getSettings).mockResolvedValue({ default_currency: "EUR", initials: "BO" });
    vi.mocked(api.listSessions).mockResolvedValue({
      sessions: [
        { id: "s1", device_label: null, created_at: 1, last_seen_at: 1, current: false },
        { id: "s2", device_label: "Pixel", created_at: 1, last_seen_at: 1, current: true },
      ],
    });
  });

  afterEach(() => {
    vi.clearAllMocks();
    cleanup();
  });

  it("shows Unknown device, as the app does, and a stored label as it is", async () => {
    renderSettingsPage();
    expect(await screen.findByText("Unknown device")).toBeInTheDocument();
    expect(screen.getByText(/Pixel/)).toBeInTheDocument();
  });
});
