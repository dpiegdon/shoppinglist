import { useT } from "../i18n";

/** A guest's outline badge, announced as "Dave (guest)". */
export function GuestBadge({ name, initials }: { name: string; initials: string }) {
  const t = useT();
  return (
    <span className="guest-badge" role="img" aria-label={t("guest.label", { name })} title={t("guest.label", { name })}>
      {initials}
    </span>
  );
}
