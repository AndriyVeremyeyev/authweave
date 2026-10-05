"use client";

import type { ReactNode } from "react";
import { AssessmentSectionForm } from "./assessment-section-form";

export function UsagePlanningForm({ action, children }: { action: string; children: ReactNode }) {
  return <AssessmentSectionForm section="usage" action={action}>{children}</AssessmentSectionForm>;
}
