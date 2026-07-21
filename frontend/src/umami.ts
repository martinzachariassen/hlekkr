// Umami is privacy-first, cookieless analytics. It loads only when both env vars are set, so the
// no-tracking default stays true: an unconfigured build ships zero analytics code.
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

// Fire a custom event; a no-op when Umami isn't loaded. Buttons also carry `data-umami-event`
// attributes, which Umami binds automatically on click — this is for outcomes it can't see.
export function track(event: string, data?: Record<string, unknown>): void {
  window.umami?.track(event, data);
}
