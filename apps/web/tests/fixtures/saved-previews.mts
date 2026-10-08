import { readFile } from "node:fs/promises";
import { cloneElement, isValidElement, type ReactElement } from "react";
import ts from "typescript";

const moduleUrl = (source: string) => `data:text/javascript;base64,${Buffer.from(source).toString("base64")}`;

// Compile the actual Server Components against supplied display components, not replacement renderers.
export async function savedPreviewComponents(dependencies: Record<string, unknown>) {
  const slot = `__authweave_saved_preview_${crypto.randomUUID()}`;
  const globals = globalThis as unknown as Record<string, unknown>;
  globals[slot] = dependencies;
  const source = await readFile(new URL("../../src/app/assessments/[id]/saved-previews.tsx", import.meta.url), "utf8");
  const compiled = ts.transpileModule(source, { compilerOptions: { module: ts.ModuleKind.ESNext,
    target: ts.ScriptTarget.ES2022, jsx: ts.JsxEmit.ReactJSX } }).outputText
    .replace(/import\s+([\s\S]*?)\s+from\s+"([^"]+)";/g, (whole: string, bindings: string, specifier: string) => {
      if (specifier === "react/jsx-runtime" || specifier === "react") return whole.replace(JSON.stringify(specifier), JSON.stringify(import.meta.resolve(specifier)));
      const names = bindings.slice(1, -1).split(",").map(name => name.trim());
      for (const name of names) if (!Object.hasOwn(dependencies, name)) dependencies[name] = () => null;
      const shim = names.map(name => `export const ${name} = globalThis[${JSON.stringify(slot)}][${JSON.stringify(name)}];`).join("\n");
      return whole.replace(JSON.stringify(specifier), JSON.stringify(moduleUrl(shim)));
    });
  try {
    const url = moduleUrl(compiled), components = await import(url);
    Object.assign(dependencies, components);
    const asynchronous = new Set([components.ComparisonPreview, components.ArchitecturePreview, components.UsagePreview,
      components.OperationsPreview, components.AuditPreview, components.AssurancePreview]);
    // Static markup cannot render async Server Components. Resolve only this explicit set;
    // leave all client/hook components untouched for the real React renderer.
    async function resolve<T>(tree: T): Promise<T> {
      if (Array.isArray(tree)) {
        const values = await Promise.all(tree.map(resolve));
        if (values.every((value, index) => value === tree[index])) return tree;
        return values.map((value, index) => isValidElement(value) && value.key === null
          ? cloneElement(value, { key: `resolved-${index}` }) : value) as T;
      }
      if (isValidElement(tree)) {
        const element = tree as ReactElement<Record<string, unknown>>;
        if (asynchronous.has(element.type)) return resolve(await (element.type as (props: unknown) => Promise<T>)(element.props));
        const props = Object.fromEntries(await Promise.all(Object.entries(element.props).map(async ([key, value]) => [key, await resolve(value)])));
        return Object.entries(props).every(([key, value]) => value === element.props[key]) ? tree : cloneElement(element, props) as T;
      }
      if (tree && typeof tree === "object" && Object.getPrototypeOf(tree) === Object.prototype) {
        const entries = await Promise.all(Object.entries(tree).map(async ([key, value]) => [key, await resolve(value)] as const));
        return entries.every(([key, value]) => value === (tree as Record<string, unknown>)[key]) ? tree : Object.fromEntries(entries) as T;
      }
      return tree;
    }
    return { url, components, resolve };
  } finally { delete globals[slot]; }
}
