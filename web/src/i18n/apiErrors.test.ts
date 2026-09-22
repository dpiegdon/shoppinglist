import { describe, expect, it } from "vitest";
import { ApiError } from "../api/client";
import { errorMessage } from "./apiErrors";
import { en } from "./messages/en";
import type { MessageKey } from "./messages/en";

const t = (key: MessageKey) => (en as Record<string, string>)[key];
const apiError = (code: string) => new ApiError(422, code, "English text from the server");

describe("server errors in the app's language", () => {
  it("tells the two currency rules apart (T-199)", () => {
    // One code for both used to tell someone whose list label was too long to type "EUR".
    expect(errorMessage(t, apiError("invalid_currency"), "apiError.serverBusy")).toBe(
      en["apiError.invalidCurrency"],
    );
    expect(errorMessage(t, apiError("invalid_list_currency"), "apiError.serverBusy")).toBe(
      en["apiError.invalidListCurrency"],
    );
  });

  it("names the list that is gone rather than leaving a parked row with no reason (T-214)", () => {
    expect(errorMessage(t, apiError("unknown_list"), "apiError.serverBusy")).toBe(
      en["apiError.unknownList"],
    );
  });

  it("maps the four codes the wire contract listed but neither client mapped (T-271)", () => {
    expect(errorMessage(t, apiError("invalid_expense"), "apiError.serverBusy")).toBe(
      en["apiError.invalidExpense"],
    );
    expect(errorMessage(t, apiError("invalid_field"), "apiError.serverBusy")).toBe(
      en["apiError.invalidField"],
    );
    expect(errorMessage(t, apiError("invalid_status"), "apiError.serverBusy")).toBe(
      en["apiError.invalidStatus"],
    );
    expect(errorMessage(t, apiError("invalid_device_label"), "apiError.serverBusy")).toBe(
      en["apiError.invalidDeviceLabel"],
    );
  });

  it("falls back to the screen's own message for a code no person can cause", () => {
    expect(errorMessage(t, apiError("invalid_cursor"), "apiError.serverBusy")).toBe(
      en["apiError.serverBusy"],
    );
  });
});
