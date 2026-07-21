// Loads Umami only when both env vars are set, so an unconfigured build ships zero analytics code.
export function initUmami(): void {
  const src = import.meta.env.VITE_UMAMI_SRC;
  const websiteId = import.meta.env.VITE_UMAMI_WEBSITE_ID;
  if (!src || !websiteId) return;

  const script = document.createElement("script");
  script.defer = true;
  script.src = src;
  script.setAttribute("data-website-id", websiteId);
  document.head.appendChild(script);
}

// Fires a custom event (no-op when Umami isn't loaded), for outcomes the auto-bound
// `data-umami-event` click attributes can't see.
export function track(event: string, data?: Record<string, unknown>): void {
  window.umami?.track(event, data);
}
