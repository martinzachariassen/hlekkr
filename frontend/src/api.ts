// Prod points at "/api": same-origin through the Caddy proxy, which injects the internal key
// server-side, so the key never reaches the browser.
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

async function toError(res: Response): Promise<Error> {
  let body: { error?: string; correlationId?: string } | null = null;
  try {
    body = await res.json();
  } catch {}
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

// Cold-start resilience: the API may idle to zero, and the Caddy proxy holds the dial while it
// boots (see Caddyfile). This retry covers what the proxy can't absorb — a gateway 5xx or dropped
// connection — and onWaking lets the UI explain the wait instead of showing a silent spinner.
const WAKE_HINT_AFTER_MS = 2500;
const WAKE_RETRY_STATUSES = new Set([500, 502, 503, 504]);
const WAKE_RETRY_DELAYS_MS = [1000, 2000, 4000, 6000];

export interface RequestOptions {
  onWaking?: () => void;
}

const sleep = (ms: number) => new Promise<void>((resolve) => setTimeout(resolve, ms));

async function request(path: string, init: RequestInit, opts?: RequestOptions): Promise<Response> {
  const wakeHint = opts?.onWaking ? setTimeout(opts.onWaking, WAKE_HINT_AFTER_MS) : undefined;
  try {
    for (let attempt = 0; ; attempt++) {
      try {
        const res = await fetch(API_BASE + path, init);
        if (WAKE_RETRY_STATUSES.has(res.status) && attempt < WAKE_RETRY_DELAYS_MS.length) {
          await sleep(WAKE_RETRY_DELAYS_MS[attempt]);
          continue;
        }
        return res;
      } catch {
        if (attempt < WAKE_RETRY_DELAYS_MS.length) {
          await sleep(WAKE_RETRY_DELAYS_MS[attempt]);
          continue;
        }
        throw networkError();
      }
    }
  } finally {
    if (wakeHint) clearTimeout(wakeHint);
  }
}

export async function shorten(
  targetUrl: string,
  expiresAt?: string,
  opts?: RequestOptions,
): Promise<CreatedLink> {
  const res = await request(
    "/links",
    {
      method: "POST",
      headers: headers(),
      body: JSON.stringify(expiresAt ? { targetUrl, expiresAt } : { targetUrl }),
    },
    opts,
  );
  if (!res.ok) throw await toError(res);
  return res.json();
}

export async function fetchStats(code: string, token: string, opts?: RequestOptions): Promise<Stats> {
  const res = await request(
    `/links/${encodeURIComponent(code)}/stats`,
    { headers: headers({ Authorization: `Bearer ${token}` }) },
    opts,
  );
  if (!res.ok) throw await toError(res);
  return res.json();
}

export async function deleteLink(code: string, token: string, opts?: RequestOptions): Promise<void> {
  const res = await request(
    `/links/${encodeURIComponent(code)}`,
    { method: "DELETE", headers: headers({ Authorization: `Bearer ${token}` }) },
    opts,
  );
  if (!res.ok) throw await toError(res);
}

export const apiDocsUrl = /^https?:\/\//.test(API_BASE) ? `${API_BASE}/swagger` : "/swagger";
