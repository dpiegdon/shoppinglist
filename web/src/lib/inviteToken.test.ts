import { describe, expect, it } from "vitest";
import pasteCases from "../../../shared-test-cases/invite-paste.json";
import { extractInviteToken, pastedInvite } from "./inviteToken";

// Driven by the table Android's PastedInviteTest reads too (T-301): the point is that both agree.
describe("pastedInvite", () => {
  it.each(pasteCases.cases)("$name", ({ input, expected }) => {
    expect(pastedInvite(input)).toBe(expected);
  });

  it("feeds extractInviteToken the link, so a pasted message redeems its token", () => {
    expect(extractInviteToken(pastedInvite("Join my list (https://p23q.org/shopping/invite/abc.def). Thanks!"))).toBe(
      "abc.def",
    );
  });
});

describe("extractInviteToken", () => {
  it("passes a bare token through, trimmed", () => {
    expect(extractInviteToken("abc.def")).toBe("abc.def");
    expect(extractInviteToken("  abc.def  ")).toBe("abc.def");
  });

  it("extracts the token from a full invite URL, incl. under a path prefix", () => {
    expect(extractInviteToken("https://p23q.org/invite/abc.def")).toBe("abc.def");
    expect(extractInviteToken("https://example.com/shopping/invite/abc.def")).toBe("abc.def");
  });

  it("tolerates a trailing slash, query, or fragment on a pasted URL", () => {
    expect(extractInviteToken("https://p23q.org/invite/abc.def/")).toBe("abc.def");
    expect(extractInviteToken("https://p23q.org/invite/abc.def?utm=x")).toBe("abc.def");
    expect(extractInviteToken("https://p23q.org/invite/abc.def#frag")).toBe("abc.def");
  });
});
