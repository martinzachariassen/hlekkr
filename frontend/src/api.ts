// Gated management calls (create/stats/delete). Dev: straight to the local API. Prod: "/api", so
// they go same-origin through the Caddy proxy, which injects the internal key server-side — the key
// never reaches the browser. See the project README (Deployment).
const API_BASE = (import.meta.env.VITE_API_BASE ?? "http://localhost:8080").replace(/\/+$/, "");

export interface CreatedLink {
  code: string;
  shortUrl: string;
  ownerToken: string;
}

export interface DailyCount {
  date: string;
  count: number;
}

export interface Stats {
  totalClicks: number;
  last7Days: DailyCount[];
}

function headers(extra?: Record<string, string>): Record<string, string> {
  return { "Content-Type": "application/json", ...extra };
}

// The API never leaks internals; map its status codes to something a person can act on.
async function toError(res: Response): Promise<Error> {
  let body: { error?: string; correlationId?: string } | null = null;
  try {
    body = await res.json();
  } catch {
    /* non-JSON error body */
  }
  switch (res.status) {
    case 400:
      return new Error(
        body?.error ??
          "That URL was rejected — it must start with http:// or https:// and can't point at a private or local address.",
      );
    case 404:
      return new Error("No link found for that code and key.");
    case 413:
      return new Error("That request was too large.");
    case 429: {
      const retry = res.headers.get("Retry-After");
      return new Error(retry ? `Too fast — try again in ${retry}s.` : "Too fast — try again shortly.");
    }
    default: {
      const ref = body?.correlationId ? ` (ref: ${body.correlationId})` : "";
      return new Error((body?.error ?? "Something went wrong on our end.") + ref);
    }
  }
}

function networkError(): Error {
  return new Error(
    `Couldn't reach the API at ${API_BASE}. Check it's running and that this origin is allowed in its CORS settings.`,
  );
}

async function request(path: string, init: RequestInit): Promise<Response> {
  try {
    return await fetch(API_BASE + path, init);
  } catch {
    throw networkError();
  }
}

export async function shorten(targetUrl: string, expiresAt?: string): Promise<CreatedLink> {
  const res = await request("/links", {
    method: "POST",
    headers: headers(),
    body: JSON.stringify(expiresAt ? { targetUrl, expiresAt } : { targetUrl }),
  });
  if (!res.ok) throw await toError(res);
  return res.json();
}

export async function fetchStats(code: string, token: string): Promise<Stats> {
  const res = await request(`/links/${encodeURIComponent(code)}/stats`, {
    headers: headers({ Authorization: `Bearer ${token}` }),
  });
  if (!res.ok) throw await toError(res);
  return res.json();
}

export async function deleteLink(code: string, token: string): Promise<void> {
  const res = await request(`/links/${encodeURIComponent(code)}`, {
    method: "DELETE",
    headers: headers({ Authorization: `Bearer ${token}` }),
  });
  if (!res.ok) throw await toError(res);
}

// Swagger UI is same-origin in production (Caddy proxies /swagger to the private API); in local dev
// the API runs on its own origin, so point straight at it there.
export const apiDocsUrl = /^https?:\/\//.test(API_BASE) ? `${API_BASE}/swagger` : "/swagger";
