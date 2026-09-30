import { cleanup, render } from "@testing-library/react";
import { afterEach, describe, expect, it } from "vitest";
import { useDocumentTitle } from "./useDocumentTitle";

function Page({ parts }: { parts: (string | undefined)[] }) {
  useDocumentTitle(...parts);
  return null;
}

describe("useDocumentTitle (T-338)", () => {
  afterEach(() => {
    cleanup();
    document.title = "";
  });

  it("names the page, then the app", () => {
    render(<Page parts={["All items", "Groceries"]} />);
    expect(document.title).toBe("All items · Groceries · Tuppu");
  });

  it("leaves out a part not known yet, and is the app's name alone with none", () => {
    const { rerender } = render(<Page parts={[undefined]} />);
    expect(document.title).toBe("Tuppu");
    rerender(<Page parts={["Groceries"]} />);
    expect(document.title).toBe("Groceries · Tuppu");
  });

  it("puts the app's name back when the page goes", () => {
    const { unmount } = render(<Page parts={["Settings"]} />);
    unmount();
    expect(document.title).toBe("Tuppu");
  });
});
