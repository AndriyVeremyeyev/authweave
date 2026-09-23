import { capabilityFields, capabilityValues, type Capability } from "./capabilities.ts";

export type CapabilityWeights = Partial<Record<Capability, number>>;

export type WeightedContribution = {
  capability: string;
  weight: number;
  outcome: "AVAILABLE" | "UNAVAILABLE";
  earnedPoints: number;
};

export type WeightedCandidate = {
  optionId: string;
  displayName: string;
  plan: string;
  region: string;
  status: "SCORED" | "EXCLUDED" | "UNRESOLVED_HARD_CONSTRAINTS" | "UNKNOWN_PREFERENCE_EVIDENCE";
  score: number | null;
  contributions: WeightedContribution[];
};

export type WeightedPreview = {
  assessmentVersion: number;
  catalogVersion: string;
  scoringPolicyVersion: string;
  candidates: WeightedCandidate[];
};

export class InvalidWeightForm extends Error { }

export function preferredCapabilities(profile: Record<string, unknown>): Capability[] | null {
  const values = capabilityValues(profile);
  return values ? capabilityFields.filter(field => values[field.capability] === "PREFERRED")
    .map(field => field.capability) : null;
}

export function parseWeightForm(params: URLSearchParams): {
  expectedVersion: number; weights: CapabilityWeights;
} {
  const keys = [...params.keys()];
  if (keys.length < 2 || keys.length > capabilityFields.length + 1) throw new InvalidWeightForm();
  const versionValues = params.getAll("expectedVersion");
  if (versionValues.length !== 1 || !/^(0|[1-9][0-9]*)$/.test(versionValues[0])) {
    throw new InvalidWeightForm();
  }
  const expectedVersion = Number(versionValues[0]);
  if (!Number.isSafeInteger(expectedVersion)) throw new InvalidWeightForm();
  const weights: CapabilityWeights = {};
  for (const key of keys) {
    if (key === "expectedVersion") continue;
    if (!capabilityFields.some(field => field.capability === key) || params.getAll(key).length !== 1) {
      throw new InvalidWeightForm();
    }
    const raw = params.get(key)!;
    if (!/^(?:[1-9]|[1-9][0-9]|100)$/.test(raw)) throw new InvalidWeightForm();
    weights[key as Capability] = Number(raw);
  }
  if (Object.values(weights).reduce((sum, weight) => sum + weight, 0) !== 100) {
    throw new InvalidWeightForm();
  }
  return { expectedVersion, weights };
}

export function weightsMatchPreferences(weights: CapabilityWeights, preferred: Capability[]): boolean {
  const keys = Object.keys(weights);
  return keys.length === preferred.length && preferred.length > 0 &&
    keys.every(key => preferred.includes(key as Capability)) &&
    Object.values(weights).every(weight => Number.isInteger(weight) && weight >= 1 && weight <= 100) &&
    Object.values(weights).reduce((sum, weight) => sum + weight, 0) === 100;
}
