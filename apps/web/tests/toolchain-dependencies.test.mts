import assert from "node:assert/strict";
import { mkdtemp, mkdir, readFile, rm, writeFile } from "node:fs/promises";
import { createRequire } from "node:module";
import { tmpdir } from "node:os";
import { dirname, join, relative } from "node:path";
import test from "node:test";
import { fileURLToPath } from "node:url";
import { ESLint } from "eslint";

const require = createRequire(import.meta.url);
const pluginRequire = createRequire(require.resolve("@next/eslint-plugin-next"));
const pluginDist = dirname(require.resolve("@next/eslint-plugin-next"));
const { getRootDirs } = require(join(pluginDist, "utils/get-root-dirs.js"));
const adapter = pluginRequire("fast-glob");
const webRoot = fileURLToPath(new URL("../", import.meta.url));
const slash = (path: string) => path.replaceAll("\\", "/");
const roots = (rootDir?: unknown) => getRootDirs({ cwd: webRoot, settings: { next: { rootDir } } });

async function withRoots(run: (fixture: string) => Promise<void>) {
  const fixture = await mkdtemp(join(tmpdir(), "authweave-eslint-roots-"));
  try {
    for (const path of ["portal/pages", "portal/nested/pages", "workforce/pages", "folder with spaces/pages"]) {
      await mkdir(join(fixture, "apps", path), { recursive: true });
    }
    await writeFile(join(fixture, "apps", "not-a-directory.ts"), "export {};\n");
    await writeFile(join(fixture, "apps", "portal", "pages", "home.tsx"), "export default function Home() { return null; }\n");
    await run(fixture);
  } finally { await rm(fixture, { recursive: true, force: true }); }
}

test("Next runtime, config and plugin stay aligned with the reviewed security patch", async () => {
  const manifest = JSON.parse(await readFile(new URL("../package.json", import.meta.url), "utf8"));
  assert.equal(manifest.dependencies.next, "16.3.8");
  assert.equal(manifest.devDependencies["eslint-config-next"], manifest.dependencies.next);
  for (const name of ["next", "eslint-config-next", "@next/eslint-plugin-next"]) {
    const installed = JSON.parse(await readFile(join(webRoot, "node_modules", name, "package.json"), "utf8"));
    assert.equal(installed.version, manifest.dependencies.next);
  }
  assert.equal(manifest.devDependencies["fast-glob"], "file:tooling/eslint-root-glob");
  assert.equal(manifest.dependencies["fast-glob"], undefined);
  assert.deepEqual(manifest.overrides, { "@next/eslint-plugin-next@16.3.8": { "fast-glob": "$fast-glob" } });
});

test("the installed plugin resolves the local adapter, not the vulnerable braces chain", async () => {
  assert.deepEqual(Object.keys(adapter), ["globSync"]);
  assert.equal(pluginRequire.resolve("fast-glob"), fileURLToPath(new URL("../tooling/eslint-root-glob/index.mjs", import.meta.url)));
  assert.equal(pluginRequire("fast-glob/package.json").name, "@authweave/eslint-root-glob");
  const lock = JSON.parse(await readFile(new URL("../package-lock.json", import.meta.url), "utf8"));
  for (const path of Object.keys(lock.packages)) {
    assert.equal(/(?:^|\/)node_modules\/(?:braces|micromatch)$/.test(path), false, path);
  }
  assert.equal(lock.packages["node_modules/fast-glob"].resolved, "tooling/eslint-root-glob");
  assert.equal(lock.packages["tooling/eslint-root-glob"].dependencies.tinyglobby, "0.2.17");
  // An upstream expansion of the call surface must trigger review, not silent substitution.
  const source = await readFile(join(pluginDist, "utils/get-root-dirs.js"), "utf8");
  assert.ok(source.includes('require("fast-glob")'));
  assert.ok(source.includes("_fastglob.globSync"));
  assert.ok(source.includes("onlyDirectories: true"));
});

test("the adapter fails explicitly if a future plugin asks for the unsupported fast-glob API", () => {
  for (const [pattern, options] of [[null, { onlyDirectories: true }], [[], { onlyDirectories: true }],
    ["apps/*", undefined], ["apps/*", { onlyDirectories: false }],
    ["apps/*", { onlyDirectories: true, cwd: "/" }], ["apps/*", { onlyDirectories: true, onlyFiles: true }]]) {
    assert.throws(() => adapter.globSync(pattern, options), TypeError);
  }
});

test("the actual Next root resolver defaults to its context cwd without globbing", () => {
  assert.deepEqual(roots(), [webRoot]);
  assert.deepEqual(roots(null), [webRoot]);
  assert.deepEqual(roots(123), [webRoot]);
  assert.deepEqual(roots([]), []);
});

test("literal relative, absolute and trailing-slash roots never expand into nested directories", async () => {
  await withRoots(async fixture => {
    const absolute = slash(join(fixture, "apps", "portal"));
    const relativeRoot = slash(relative(process.cwd(), absolute));
    for (const pattern of [absolute, relativeRoot, `${absolute}/`, `${relativeRoot}/`]) {
      assert.deepEqual(roots(pattern), [pattern]);
    }
    const spaced = slash(join(fixture, "apps", "folder with spaces"));
    assert.deepEqual(roots(spaced), [spaced]);
  });
});

test("wildcard, brace and mixed root arrays preserve directories-only Next discovery", async () => {
  await withRoots(async fixture => {
    const apps = slash(join(fixture, "apps"));
    const expected = ["portal", "workforce", "folder with spaces"].map(name => `${apps}/${name}`).sort();
    assert.deepEqual(roots(`${apps}/*`).sort(), expected);
    assert.deepEqual(roots(`${apps}/{portal,workforce}`).sort(), [`${apps}/portal`, `${apps}/workforce`]);
    assert.deepEqual(roots([`${apps}/portal`, null, `${apps}/workforce/`, `${apps}/missing`]), [`${apps}/portal`, `${apps}/workforce/`]);
    assert.deepEqual(roots(`${apps}/not-a-directory.ts`), []);
    assert.deepEqual(roots(`${apps}/missing`), []);
  });
});

test("the actual Next resolver retains its backslash normalization with the adapter", async () => {
  await withRoots(async fixture => {
    const pattern = slash(join(fixture, "apps", "portal"));
    assert.deepEqual(roots(pattern.replaceAll("/", "\\")), [pattern]);
  });
});

test("real Next lint still finds pages through root globs and rejects unsafe plain navigation", async () => {
  await withRoots(async fixture => {
    const eslint = new ESLint({ cwd: webRoot, overrideConfig: [{ settings: { next: { rootDir: `${slash(fixture)}/apps/{portal,workforce}` } } }] });
    const filePath = join(webRoot, "src/app/toolchain-regression.tsx");
    const [unsafe] = await eslint.lintText('export default function Page() { return <a href="/home">Home</a>; }', { filePath });
    assert.ok(unsafe.messages.some(message => message.ruleId === "@next/next/no-html-link-for-pages"));
    const [safe] = await eslint.lintText('import Link from "next/link"; export default function Page() { return <Link href="/home">Home</Link>; }', { filePath });
    assert.equal(safe.errorCount, 0); assert.equal(safe.warningCount, 0);
  });
});
