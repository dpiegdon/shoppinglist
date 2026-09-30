import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import ToggleSwitch from "./ToggleSwitch";

// The knob takes the colour drawn on its track (T-343): white knobs read under 3:1 on the dark red
// and on the accent.
function knob(checked: boolean, props: Partial<Parameters<typeof ToggleSwitch>[0]> = {}) {
  render(<ToggleSwitch checked={checked} onChange={() => {}} label="Switch" {...props} />);
  return (screen.getByRole("switch").firstElementChild as HTMLElement).style.background;
}

describe("ToggleSwitch knob", () => {
  it("is the on-red colour on the red track when off", () => {
    expect(knob(false)).toBe("var(--color-danger-text)");
  });

  it("is the switch knob colour on the green track when on", () => {
    expect(knob(true)).toBe("var(--color-switch-knob)");
  });

  it("takes the colours a caller passes, as list properties' type switch does", () => {
    expect(knob(true, { onKnobColor: "var(--color-accent-text)" })).toBe("var(--color-accent-text)");
  });
});
