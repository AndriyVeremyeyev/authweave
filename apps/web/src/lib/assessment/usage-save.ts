import { postProfileSection, profileReloadPath, profileSaveFeedback } from "./profile-save.ts";

// Keep the existing Usage entry points and acknowledgement contract unchanged.
export { profileWriteResponse as usageWriteResponse } from "./profile-save.ts";
export type { ProfileWriteOutcome as UsageWriteOutcome, ProfileSaveResult as UsageSaveResult } from "./profile-save.ts";
export const usageReloadPath = (action: string) => profileReloadPath("usage", action);
export const postUsagePlanning = (action: string, params: URLSearchParams, fetcher: typeof fetch = fetch) =>
  postProfileSection("usage", action, params, fetcher);
export const usageSaveFeedback = { ...profileSaveFeedback,
  invalid: { ...profileSaveFeedback.invalid, title: "These usage inputs were rejected" } } as const;
