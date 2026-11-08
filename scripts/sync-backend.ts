// Copies what the clients share with the backend (anirban1809/harbor0-src) from its checkout:
// the contracts (zod schemas and domain types) and the OpenAPI document, then regenerates the API
// client's types from that document. Run it after pulling backend changes the apps need:
//   npm run sync:backend             copy from the checkout scripts/backend-dir.ts finds
//   npm run sync:backend -- --check  fail, changing nothing, when this copy is out of date
// The backend keeps its own copy of packages/api-client (its Lambda uses the session proxy); this
// repository owns that package, so the script only reports where the backend's copy differs.
import { cp, mkdtemp, readdir, readFile, rm, writeFile } from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import openapiTS, { astToString } from 'openapi-typescript';
import { backendDir } from './backend-dir';

const root = path.resolve(import.meta.dirname, '..');
const backend = backendDir();
if (!backend)
  throw new Error(
    'No harbor0-src checkout found. Clone it next to this repository or set HARBOR_BACKEND_DIR.',
  );
const check = process.argv.includes('--check');

async function files(dir: string): Promise<Map<string, string>> {
  const found = new Map<string, string>();
  for (const entry of await readdir(dir, { recursive: true, withFileTypes: true }))
    if (entry.isFile()) {
      const file = path.join(entry.parentPath, entry.name);
      found.set(path.relative(dir, file), await readFile(file, 'utf8'));
    }
  return found;
}
async function differences(from: string, to: string) {
  const [a, b] = await Promise.all([files(from), files(to).catch(() => new Map<string, string>())]);
  return [...new Set([...a.keys(), ...b.keys()])].filter((file) => a.get(file) !== b.get(file));
}

// Generate into a scratch copy first, so --check can compare without touching the tree.
const stage = await mkdtemp(path.join(os.tmpdir(), 'harbor0-sync-'));
try {
  await cp(path.join(backend, 'packages/contracts'), path.join(stage, 'contracts'), {
    recursive: true,
    filter: (file) => !file.includes('node_modules'),
  });
  const schema = JSON.parse(await readFile(path.join(backend, 'docs/openapi.json'), 'utf8'));
  await writeFile(path.join(stage, 'openapi.json'), JSON.stringify(schema, null, 2) + '\n');
  await writeFile(path.join(stage, 'generated.ts'), astToString(await openapiTS(schema)));

  const stale = [
    ...(
      await differences(path.join(stage, 'contracts'), path.join(root, 'packages/contracts'))
    ).map((file) => `packages/contracts/${file}`),
    ...(
      await Promise.all(
        [
          ['openapi.json', 'docs/openapi.json'],
          ['generated.ts', 'packages/api-client/src/generated.ts'],
        ].map(async ([from, to]) =>
          (await readFile(path.join(stage, from), 'utf8')) ===
          (await readFile(path.join(root, to), 'utf8').catch(() => ''))
            ? []
            : [to],
        ),
      )
    ).flat(),
  ];
  if (check) {
    if (stale.length) {
      console.error(
        `Out of date with ${backend}; run npm run sync:backend:\n  ${stale.join('\n  ')}`,
      );
      process.exit(1);
    }
    console.log(`Contracts and API types match ${backend}.`);
  } else {
    await rm(path.join(root, 'packages/contracts'), { recursive: true, force: true });
    await cp(path.join(stage, 'contracts'), path.join(root, 'packages/contracts'), {
      recursive: true,
    });
    await cp(path.join(stage, 'openapi.json'), path.join(root, 'docs/openapi.json'));
    await cp(
      path.join(stage, 'generated.ts'),
      path.join(root, 'packages/api-client/src/generated.ts'),
    );
    console.log(
      stale.length ? `Updated from ${backend}:\n  ${stale.join('\n  ')}` : 'Already up to date.',
    );
  }

  const apiClient = (
    await differences(
      path.join(root, 'packages/api-client/src'),
      path.join(backend, 'packages/api-client/src'),
    )
  ).filter((file) => file !== 'generated.ts');
  if (apiClient.length)
    console.warn(
      `The backend's copy of packages/api-client differs in: ${apiClient.join(', ')}.\n` +
        'Copy the changed files there if the backend (session proxy) or its sites need them.',
    );
} finally {
  await rm(stage, { recursive: true, force: true });
}
