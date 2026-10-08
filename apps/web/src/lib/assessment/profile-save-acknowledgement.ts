export const profileSaveAcknowledgementByteLimit = 65_536;

// These six v6 fields are domain sets. Other arrays, including usage assumptions,
// are ordered lists. Never sort or rewrite the submitted or returned profile.
const setPaths = new Set([
  '["application","clients"]', '["audience","populations"]',
  '["security","complianceTargets"]',
  '["security","dataResidencyDetails","allowedCountries"]',
  '["security","dataResidencyDetails","dataCategories"]',
  '["security","auditabilityRequirements","selectedCriteria"]',
]);

/** Match all submitted JSON values, allowing only object-key and declared-set order changes. */
export function savedProfileMatches(expected: unknown, saved: unknown): boolean {
  function match(left: unknown, right: unknown, path: string[]): boolean {
    // V6 profiles are much shallower; refuse adversarial nesting without exhausting the stack.
    if (path.length > 16) return false;
    if (left === null) return right === null;
    if (typeof left !== "object") return (typeof left === "string" || typeof left === "boolean" ||
        (typeof left === "number" && Number.isFinite(left))) && left === right;
    if (!right || typeof right !== "object" || Array.isArray(left) !== Array.isArray(right)) return false;
    if (Array.isArray(left)) {
      const values = right as unknown[];
      if (left.length !== values.length) return false;
      if (setPaths.has(JSON.stringify(path))) {
        return left.every(value => typeof value === "string") && values.every(value => typeof value === "string") &&
          new Set(left).size === left.length && new Set(values).size === values.length &&
          left.every(value => values.includes(value));
      }
      return left.every((value, index) => match(value, values[index], [...path, String(index)]));
    }
    const object = left as Record<string, unknown>, other = right as Record<string, unknown>;
    const keys = Object.keys(object);
    return keys.length === Object.keys(other).length && keys.every(key => Object.hasOwn(other, key) &&
      match(object[key], other[key], [...path, key]));
  }
  return match(expected, saved, []);
}
