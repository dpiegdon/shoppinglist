import { render, screen, cleanup } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router-dom";
import { afterEach, describe, expect, it, vi } from "vitest";
import App from "../App";
import AboutPage from "./AboutPage";
import { en } from "../i18n/messages/en";
import * as api from "../api/client";

vi.mock("../api/client", async () => {
  const actual = await vi.importActual<typeof api>("../api/client");
  return {
    ...actual,
    getToken: vi.fn(() => "tok"),
    onForcedLogout: vi.fn(),
    // The shell mounts the sync provider; nothing here is about syncing, so it never answers.
    sync: vi.fn(() => new Promise(() => {})),
  };
});

function setVersionMeta(version: string) {
  const meta = document.createElement("meta");
  meta.name = "app-version";
  meta.content = version;
  document.head.appendChild(meta);
}

afterEach(() => {
  document.head.querySelectorAll('meta[name^="app-"]').forEach((m) => m.remove());
  localStorage.clear();
  vi.clearAllMocks();
  cleanup();
});

describe("AboutPage (T-224)", () => {
  it("shows the mark, the name, what Tuppu means, the version and the licence", () => {
    setVersionMeta("2.0.0");
    render(<MemoryRouter><AboutPage /></MemoryRouter>);

    // The same mark the login page opens with, at the same size (T-213/T-216).
    const mark = document.querySelector('img[src$="/favicon.svg"]') as HTMLImageElement | null;
    expect(mark).not.toBeNull();
    expect(mark!.getAttribute("width")).toBe("72");
    expect(screen.getByRole("heading", { level: 1, name: en["app.title"] })).toBeInTheDocument();
    expect(screen.getByText(en["about.tagline"])).toBeInTheDocument();
    // The heading, the sign and its transliteration already say the name three times: the sentence
    // under them starts with what it means, not with the name a fourth time.
    expect(en["about.tagline"]).toBe("The Akkadian word for a clay tablet — the thing a list was pressed into.");
    expect(en["about.tagline"]).not.toMatch(/tuppu/i);
    expect(screen.getByText("Version 2.0.0")).toBeInTheDocument();
    expect(screen.getByText(en["about.license"])).toBeInTheDocument();
  });

  it("links the source code beside the licence, opening in a new tab (T-311)", () => {
    render(<MemoryRouter><AboutPage /></MemoryRouter>);

    const link = screen.getByRole("link", { name: en["about.sourceCode"] });
    expect(link.getAttribute("href")).toBe("https://github.com/dpiegdon/shoppinglist");
    expect(link.getAttribute("target")).toBe("_blank");
    expect(link.getAttribute("rel")).toBe("noopener");
    // In the small print, right beside the licence line.
    expect(link.parentElement).toBe(screen.getByText(en["about.license"]).parentElement);
  });

  it("shows the name in cuneiform over its transliteration (T-225)", () => {
    render(<MemoryRouter><AboutPage /></MemoryRouter>);

    // An inline svg, not an <img>: nothing to fetch or route, and it follows currentColor on both
    // themes. Its accessible name is the transliteration.
    const sign = screen.getByRole("img", { name: en["about.transliteration"] });
    expect(sign.style.fill).toMatch(/currentcolor/i);
    expect(sign.tagName.toLowerCase()).toBe("svg"); // inline path, nothing fetched (T-231)
    expect(sign.querySelector("path")).not.toBeNull();
    expect(sign.style.height).toBe("64px");
    // About, unlike login, also spells the reading out under the sign.
    expect(screen.getByText(en["about.transliteration"])).toBeInTheDocument();
  });

  it("shows the version line empty rather than crashing when the server injected no meta tag", () => {
    // A dev server (Vite alone) serves index.html untouched, so there is no version to read.
    render(<MemoryRouter><AboutPage /></MemoryRouter>);

    const line = screen.getByText((_, el) => el?.textContent === "Version ");
    expect(line).toBeInTheDocument();
    expect(screen.getByText(en["about.tagline"])).toBeInTheDocument();
  });

  it("is what the menu's About entry opens", async () => {
    setVersionMeta("2.0.0");
    localStorage.setItem(
      "shoppinglist_account",
      JSON.stringify({ id: "acc-1", email: "shopper@example.com", isAdmin: false }),
    );
    render(<MemoryRouter initialEntries={["/"]}><App /></MemoryRouter>);

    await userEvent.click(screen.getByRole("button", { name: en["nav.menu"] }));
    await userEvent.click(screen.getByRole("link", { name: en["nav.about"] }));

    expect(screen.getByText(en["about.tagline"])).toBeInTheDocument();
  });
});
