import { useCallback, useState, type ReactNode } from "react";
import { ConfirmDialog, type ConfirmOptions } from "../components/ConfirmDialog";

interface Pending {
  options: ConfirmOptions;
  resolve: (confirmed: boolean) => void;
}

/**
 * The ConfirmDialog as an await (T-343), for a handler that used to read `if (!confirm(…)) return`:
 * `if (!(await ask({ … }))) return`. Render the returned element anywhere in the component; it is
 * null while nothing is being asked.
 */
export function useConfirm(): [ReactNode, (options: ConfirmOptions) => Promise<boolean>] {
  const [pending, setPending] = useState<Pending | null>(null);

  const ask = useCallback(
    (options: ConfirmOptions) => new Promise<boolean>((resolve) => setPending({ options, resolve })),
    [],
  );

  function settle(confirmed: boolean) {
    pending?.resolve(confirmed);
    setPending(null);
  }

  const element = pending ? (
    <ConfirmDialog {...pending.options} onConfirm={() => settle(true)} onCancel={() => settle(false)} />
  ) : null;
  return [element, ask];
}
