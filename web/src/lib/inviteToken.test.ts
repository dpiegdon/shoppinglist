import { describe, expect, it } from "vitest";
import { extractInviteToken } from "./inviteToken";

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
