import { useCallback, useRef, useState, type FormEvent, type ReactNode } from "react";
import {
  apiDocsUrl,
  deleteLink,
  fetchStats,
  shorten,
  type CreatedLink,
  type Stats,
} from "./api.ts";
import { track } from "./umami.ts";

const SOURCE_URL = "https://github.com/martinzachariassen/url-shortener";

type Mode = "create" | "manage";

export function App() {
  const [mode, setMode] = useState<Mode>("create");
  const [created, setCreated] = useState<CreatedLink | null>(null);

  return (
    <div className="page">
      <header className="topbar">
        <a className="brand" href="/" aria-label="short — home" data-umami-event="open-home">
          <span className="brand-mark" aria-hidden="true" />
          short
        </a>
        <nav className="nav">
          <button
            type="button"
            className="linklike"
            data-umami-event={mode === "create" ? "open-manage" : "open-create"}
            onClick={() => setMode(mode === "create" ? "manage" : "create")}
          >
            {mode === "create" ? "Manage a link" : "Shorten a link"}
          </button>
          <a href={apiDocsUrl} target="_blank" rel="noopener" data-umami-event="open-api-docs">
            API
          </a>
          <a href={SOURCE_URL} target="_blank" rel="noopener" data-umami-event="open-source">
            Source
          </a>
        </nav>
      </header>

      <main className="stage">
        {mode === "create" ? (
          <CreateView created={created} setCreated={setCreated} onManage={() => setMode("manage")} />
        ) : (
          <ManageView seed={created} />
        )}
      </main>

      <footer className="foot">
        <span>
          Built by{" "}
          <a href="https://mlz.no" target="_blank" rel="noopener" data-umami-event="open-author">
            Martin Zachariassen
          </a>
        </span>
        <span>Kotlin · Ktor · Postgres · no cookies</span>
      </footer>
    </div>
  );
}

function CreateView({
  created,
  setCreated,
  onManage,
}: {
  created: CreatedLink | null;
  setCreated: (c: CreatedLink | null) => void;
  onManage: () => void;
}) {
  const [url, setUrl] = useState("");
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);

  async function onSubmit(e: FormEvent) {
    e.preventDefault();
    setError("");
    const raw = url.trim();
    try {
      const parsed = new URL(raw);
      if (!/^https?:$/.test(parsed.protocol)) {
        setError("Only http:// and https:// links are supported.");
        return;
      }
    } catch {
      setError("Enter a full URL, starting with http:// or https://");
      return;
    }

    setBusy(true);
    try {
      const link = await shorten(raw);
      setCreated(link);
      track("shorten-success");
    } catch (err) {
      setError(err instanceof Error ? err.message : "Something went wrong.");
      track("shorten-error");
    } finally {
      setBusy(false);
    }
  }

  if (created) {
    return <ResultPanel link={created} onReset={() => setCreated(null)} onManage={onManage} />;
  }

  return (
    <>
      <div className="intro">
        <p className="eyebrow">
          <span className="dot" aria-hidden="true" />
          Free · no sign-up · no tracking beyond a click count
        </p>
        <h1>Shorten a link.</h1>
        <p className="lede">
          Paste a URL, get a short one back — plus a one-time key to check its clicks or take it down.
        </p>
      </div>

      <form className="panel" onSubmit={onSubmit} noValidate>
        <div className="field-row">
          <div className="field">
            <label htmlFor="url" className="sr-only">
              URL to shorten
            </label>
            <input
              id="url"
              type="url"
              inputMode="url"
              placeholder="https://your-long-url.example/goes/here"
              autoComplete="off"
              spellCheck={false}
              value={url}
              onChange={(e) => setUrl(e.target.value)}
              autoFocus
            />
          </div>
          <button type="submit" className="btn" disabled={busy} data-umami-event="shorten">
            {busy ? "Shortening…" : "Shorten →"}
          </button>
        </div>
        <p className={`msg error${error ? " show" : ""}`} role="alert">
          {error}
        </p>
      </form>

      <ul className="trust">
        <li>unsafe links blocked on submit</li>
        <li>your key stored hashed</li>
        <li>clicks counted, never profiled</li>
      </ul>
    </>
  );
}

function ResultPanel({
  link,
  onReset,
  onManage,
}: {
  link: CreatedLink;
  onReset: () => void;
  onManage: () => void;
}) {
  return (
    <>
      <div className="intro">
        <p className="eyebrow">
          <span className="dot" aria-hidden="true" />
          Ready
        </p>
        <h1>Here's your link.</h1>
      </div>

      <div className="panel result" aria-live="polite">
        <div className="result-row">
          <a
            className="result-url"
            href={link.shortUrl}
            target="_blank"
            rel="noopener"
            data-umami-event="open-short-link"
          >
            {link.shortUrl}
          </a>
          <CopyButton value={link.shortUrl} label="Copy" event="copy-link" />
        </div>

        <div className="token">
          <p className="token-label">Save this key — shown once, stored only as a hash</p>
          <div className="result-row">
            <span className="token-val">{link.ownerToken}</span>
            <CopyButton value={link.ownerToken} label="Copy key" event="copy-key" />
          </div>
        </div>

        <div className="result-actions">
          <button type="button" className="linklike accent" onClick={onManage} data-umami-event="goto-stats">
            Check its stats →
          </button>
          <button type="button" className="linklike" onClick={onReset} data-umami-event="shorten-another">
            Shorten another
          </button>
        </div>
      </div>
    </>
  );
}

function ManageView({ seed }: { seed: CreatedLink | null }) {
  const [code, setCode] = useState(seed?.code ?? "");
  const [token, setToken] = useState(seed?.ownerToken ?? "");
  const [stats, setStats] = useState<Stats | null>(null);
  const [error, setError] = useState("");
  const [status, setStatus] = useState("");
  const [busy, setBusy] = useState(false);

  const lookup = useCallback(async () => {
    setError("");
    setStatus("");
    if (!code.trim() || !token.trim()) {
      setError("Enter both the code and the key.");
      return;
    }
    setBusy(true);
    try {
      setStats(await fetchStats(code.trim(), token.trim()));
      track("stats-view");
    } catch (err) {
      setStats(null);
      setError(err instanceof Error ? err.message : "Something went wrong.");
    } finally {
      setBusy(false);
    }
  }, [code, token]);

  async function onDelete() {
    setBusy(true);
    try {
      await deleteLink(code.trim(), token.trim());
      setStats(null);
      setStatus("Deleted — this link no longer redirects.");
      track("delete-success");
    } catch (err) {
      setError(err instanceof Error ? err.message : "Something went wrong.");
    } finally {
      setBusy(false);
    }
  }

  return (
    <>
      <div className="intro">
        <p className="eyebrow">
          <span className="dot" aria-hidden="true" />
          Your key, your link
        </p>
        <h1>Manage a link.</h1>
        <p className="lede">Enter a short code and its key to see clicks or take it down.</p>
      </div>

      <form
        className="panel"
        onSubmit={(e) => {
          e.preventDefault();
          void lookup();
        }}
      >
        <div className="field-row wrap">
          <div className="field grow">
            <label htmlFor="code" className="sr-only">
              Short code
            </label>
            <input
              id="code"
              type="text"
              placeholder="code, e.g. d10ndrX"
              autoComplete="off"
              spellCheck={false}
              value={code}
              onChange={(e) => setCode(e.target.value)}
            />
          </div>
          <div className="field grow">
            <label htmlFor="token" className="sr-only">
              Private key
            </label>
            <input
              id="token"
              type="password"
              placeholder="private key"
              autoComplete="off"
              spellCheck={false}
              value={token}
              onChange={(e) => setToken(e.target.value)}
            />
          </div>
          <button type="submit" className="btn ghost" disabled={busy} data-umami-event="stats-lookup">
            {busy ? "…" : "Look up"}
          </button>
        </div>
        <p className={`msg error${error ? " show" : ""}`} role="alert">
          {error}
        </p>
        <p className={`msg${status ? " show" : ""}`} role="status">
          {status}
        </p>

        {stats && <StatsPanel stats={stats} busy={busy} onDelete={onDelete} />}
      </form>
    </>
  );
}

function StatsPanel({ stats, busy, onDelete }: { stats: Stats; busy: boolean; onDelete: () => void }) {
  const [confirming, setConfirming] = useState(false);
  const max = Math.max(1, ...stats.last7Days.map((d) => d.count));

  return (
    <div className="stats" aria-live="polite">
      <div className="stats-total">
        <span className="num">{stats.totalClicks}</span>
        <span className="lbl">total clicks</span>
      </div>
      <div className="bars" aria-label="Clicks over the last 7 days">
        {stats.last7Days.map((d) => (
          <div className="bar-col" key={d.date}>
            <span className="bar-count">{d.count}</span>
            <div className="bar" style={{ height: `${Math.max(3, Math.round((d.count / max) * 100))}%` }} />
            <span className="bar-day">{weekday(d.date)}</span>
          </div>
        ))}
      </div>
      <div className="danger">
        {confirming ? (
          <>
            <span className="danger-label">Can't be undone.</span>
            <button type="button" className="btn danger" disabled={busy} onClick={onDelete} data-umami-event="delete-confirm">
              Yes, delete
            </button>
            <button
              type="button"
              className="linklike"
              onClick={() => setConfirming(false)}
              data-umami-event="delete-cancel"
            >
              Cancel
            </button>
          </>
        ) : (
          <button type="button" className="linklike danger-text" onClick={() => setConfirming(true)} data-umami-event="delete-open">
            Delete this link
          </button>
        )}
      </div>
    </div>
  );
}

function CopyButton({ value, label, event }: { value: string; label: string; event: string }): ReactNode {
  const [copied, setCopied] = useState(false);
  const timer = useRef<ReturnType<typeof setTimeout> | null>(null);

  async function copy() {
    try {
      await navigator.clipboard.writeText(value);
    } catch {
      const ta = document.createElement("textarea");
      ta.value = value;
      ta.style.position = "fixed";
      ta.style.opacity = "0";
      document.body.appendChild(ta);
      ta.select();
      try {
        document.execCommand("copy");
      } catch {
        /* clipboard unavailable */
      }
      document.body.removeChild(ta);
    }
    setCopied(true);
    if (timer.current) clearTimeout(timer.current);
    timer.current = setTimeout(() => setCopied(false), 1400);
  }

  return (
    <button type="button" className="copy" onClick={copy} data-umami-event={event}>
      {copied ? "Copied" : label}
    </button>
  );
}

function weekday(iso: string): string {
  const d = new Date(iso);
  return Number.isNaN(d.getTime()) ? iso : d.toLocaleDateString(undefined, { weekday: "short" });
}
