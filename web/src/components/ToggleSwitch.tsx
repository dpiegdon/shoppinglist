/**
 * Accessible on/off switch (T-112). The admin's registration switch keeps its meaning colours, a
 * green track when on and red when off; list properties' type switch (T-340) passes the accent and
 * a neutral grey instead, as Android's Material switch draws it. The knob takes the colour drawn on
 * its track (T-343): white on the light red, near-black on the dark red and on the accent, where
 * white read under 3:1.
 */
export default function ToggleSwitch({
  checked,
  disabled,
  onChange,
  label,
  onColor = "var(--color-switch-on)",
  offColor = "var(--color-danger)",
  onKnobColor = "var(--color-switch-knob)",
  offKnobColor = "var(--color-danger-text)",
}: {
  checked: boolean;
  disabled?: boolean;
  onChange: () => void;
  label: string;
  onColor?: string;
  offColor?: string;
  onKnobColor?: string;
  offKnobColor?: string;
}) {
  return (
    <button
      type="button"
      role="switch"
      aria-checked={checked}
      aria-label={label}
      disabled={disabled}
      onClick={onChange}
      style={{
        position: "relative",
        width: "3rem",
        height: "1.6rem",
        borderRadius: "999px",
        border: "none",
        flexShrink: 0,
        cursor: disabled ? "default" : "pointer",
        opacity: disabled ? 0.5 : 1,
        background: checked ? onColor : offColor,
        transition: "background 0.15s",
      }}
    >
      <span
        style={{
          position: "absolute",
          top: "0.2rem",
          // Logical, not `left` (T-126): this knob's POSITION is what says on/off, so in an
          // RTL layout it has to travel the other way or the switch reads inverted.
          insetInlineStart: checked ? "1.6rem" : "0.2rem",
          width: "1.2rem",
          height: "1.2rem",
          borderRadius: "50%",
          background: checked ? onKnobColor : offKnobColor,
          transition: "inset-inline-start 0.15s",
        }}
      />
    </button>
  );
}
