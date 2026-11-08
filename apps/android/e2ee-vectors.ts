/* global console, process */
// Writes the test vectors the Android end-to-end encryption port is checked against, made with the
// shared TypeScript implementation. Every random value is fixed, so a Kotlin seal must match.
// Run from the repository root: npx tsx apps/android/e2ee-vectors.ts
import { createHash, webcrypto } from 'node:crypto';
import { writeFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, resolve } from 'node:path';
import {
  ARGON2,
  CHUNK_SIZE,
  FileCipher,
  HEADER_SIZE,
  accountKeys,
  decryptName,
  encryptName,
  encryptedSize,
  formatRecoveryKey,
  parseRecoveryKey,
  plainSize,
  unwrapForDevice,
  unwrapWithPassphrase,
  unwrapWithRecovery,
  wrapForDevice,
  wrapWithPassphrase,
  wrapWithRecovery,
} from '../../packages/api-client/src/e2ee';

const out = resolve(
  dirname(fileURLToPath(import.meta.url)),
  'app/src/test/resources/e2ee-vectors.json',
);
const hex = (b: Uint8Array) => Buffer.from(b).toString('hex');
const sha = (b: Uint8Array) => createHash('sha256').update(b).digest('hex');
/** Fixed bytes: a counter run through SHA-256, so each value is distinct and reproducible. */
const fixed = (label: string, n: number) => {
  const result = new Uint8Array(n);
  for (let i = 0, at = 0; at < n; i++) {
    const block = createHash('sha256').update(`${label}#${i}`).digest();
    result.set(block.subarray(0, Math.min(32, n - at)), at);
    at += 32;
  }
  return result;
};
// The next values random() hands out; anything unplanned fails loudly.
const queue: Uint8Array[] = [];
const real = webcrypto.getRandomValues.bind(webcrypto);
Object.defineProperty(globalThis.crypto, 'getRandomValues', {
  configurable: true,
  value: <T extends ArrayBufferView | null>(target: T): T => {
    const next = queue.shift();
    if (!next) return real(target as never) as T;
    const view = new Uint8Array(target!.buffer, target!.byteOffset, target!.byteLength);
    if (next.length !== view.length)
      throw new Error(`Expected ${view.length} random bytes, queued ${next.length}`);
    view.set(next);
    return target;
  },
});
const plan = (...values: Uint8Array[]) => {
  queue.length = 0;
  queue.push(...values);
  return values.map(hex);
};
const done = () => {
  if (queue.length) throw new Error(`${queue.length} queued random values were not used`);
};
// HKDF straight from WebCrypto, for the raw subkeys accountKeys keeps as CryptoKeys.
async function hkdf(secret: Uint8Array, info: string, length: number) {
  const base = await webcrypto.subtle.importKey('raw', secret, 'HKDF', false, ['deriveBits']);
  return new Uint8Array(
    await webcrypto.subtle.deriveBits(
      {
        name: 'HKDF',
        hash: 'SHA-256',
        salt: new Uint8Array(),
        info: new TextEncoder().encode(info),
      },
      base,
      length * 8,
    ),
  );
}
/** Deterministic file content. */
const content = (n: number, seed: number) => {
  const b = new Uint8Array(n);
  for (let i = 0; i < n; i++) b[i] = (i * 31 + seed + (i >>> 11)) & 255;
  return b;
};

const master = fixed('master', 32);
const keys = await accountKeys(master);

// Passphrase: NFKC folds the ligature and the composed é, with the real Argon2id cost.
const passphrase = 'correct ﬁsh horse café ☃';
const passRandom = plan(fixed('pass-salt', 16), fixed('pass-iv', 12));
const passEnvelope = await wrapWithPassphrase(master, passphrase, ARGON2);
done();
if (hex(await unwrapWithPassphrase(passEnvelope, passphrase.normalize('NFKC'))) !== hex(master))
  throw new Error('passphrase round trip');
// A cheap cost too, so tests can run the KDF more than once.
const cheap = { memoryKiB: 19_456, iterations: 2, parallelism: 1 };
const cheapRandom = plan(fixed('cheap-salt', 16), fixed('cheap-iv', 12));
const cheapEnvelope = await wrapWithPassphrase(master, 'hunter2', cheap);
done();

const recovery = fixed('recovery', 20);
const recoveryText = formatRecoveryKey(recovery);
const sloppy = recoveryText.toLowerCase().replace(/-/g, ' ').replace(/0/g, 'o').replace(/1/g, 'l');
if (hex(parseRecoveryKey(sloppy)!) !== hex(recovery)) throw new Error('sloppy recovery parse');
const recRandom = plan(fixed('rec-salt', 16), fixed('rec-iv', 12));
const recEnvelope = await wrapWithRecovery(master, recovery);
done();
await unwrapWithRecovery(recEnvelope, recovery);

const local = fixed('device-local', 32);
const server = fixed('device-server', 32);
const devRandom = plan(fixed('device-iv', 12));
const devEnvelope = await wrapForDevice(master, local, server);
done();
await unwrapForDevice(devEnvelope, local, server);

const names = [
  'report.pdf',
  'Résumé (final).docx',
  'Café menu.txt',
  '日本語のファイル名.jpg',
  '🚢 harbor 🚢.png',
  '',
  'é'.repeat(127) + 'x',
];
const nameVectors = [];
for (const name of names) {
  const token = await encryptName(keys, name);
  if ((await decryptName(keys, token)) !== name.normalize('NFC'))
    throw new Error('name round trip');
  nameVectors.push({ name, token });
}
let tooLong = false;
try {
  await encryptName(keys, 'é'.repeat(128));
} catch {
  tooLong = true;
}
if (!tooLong) throw new Error('256-byte name was accepted');

// TS FileCipher counts chunks with the 4 MiB CHUNK_SIZE whatever the header says, so files are
// only ever 4 MiB-chunked: small ones are inlined, a multi-chunk one is hashed.
async function sealWhole(cipher: FileCipher, plain: Uint8Array) {
  return cipher.encryptedRange(plain.length, 0, encryptedSize(plain.length), async (a, b) =>
    plain.subarray(a, b),
  );
}
const smallFiles = [];
for (const [label, size] of [
  ['small-10k', 10_000],
  ['small-exact', 4096],
  ['small-empty', 0],
  ['small-one', 1],
] as const) {
  const random = plan(
    fixed(`${label}-key`, 32),
    fixed(`${label}-prefix`, 8),
    fixed(`${label}-wrap`, 12),
  );
  const cipher = await FileCipher.create(keys);
  done();
  const header = cipher.header;
  const plain = content(size, size % 251);
  const stored = await sealWhole(cipher, plain);
  const ranges = [];
  for (const [begin, end] of [
    [0, size],
    [0, 1],
    [4095, 4097],
    [5000, 9000],
    [size - 1, size],
  ]) {
    if (begin < 0 || end > size || end <= begin) continue;
    const r = cipher.cipherRange(size, begin, end);
    const plainOut = await cipher.decryptRange(size, begin, end, stored.subarray(r.start, r.end));
    if (hex(plainOut) !== hex(plain.subarray(begin, end))) throw new Error('range');
    ranges.push({ begin, end, first: r.first, last: r.last, start: r.start, storedEnd: r.end });
  }
  const parts = [];
  for (const [start, end] of [
    [0, 50],
    [50, 4200],
    [4170, 4192],
    [4192, stored.length],
    [100, stored.length - 3],
  ]) {
    if (end > stored.length || end <= start) continue;
    const bytes = await cipher.encryptedRange(size, start, end, async (a, b) =>
      plain.subarray(a, b),
    );
    if (hex(bytes) !== hex(stored.subarray(start, end))) throw new Error('encryptedRange');
    parts.push({ start, end, plainRange: cipher.plainRangeFor(size, start, end) });
  }
  smallFiles.push({
    label,
    size,
    seed: size % 251,
    random,
    header: hex(header),
    stored: hex(stored),
    ranges,
    parts,
  });
}

// A real file: 4 MiB chunks from FileCipher.create, two full chunks and a bit, hashed.
const bigSize = 2 * CHUNK_SIZE + 1234;
const bigRandom = plan(fixed('big-key', 32), fixed('big-prefix', 8), fixed('big-wrap', 12));
const big = await FileCipher.create(keys);
done();
const bigPlain = content(bigSize, 7);
const bigStored = await sealWhole(big, bigPlain);
const partSize = 5 * 1024 * 1024;
const bigParts = [];
for (let start = 0; start < bigStored.length; start += partSize) {
  const end = Math.min(bigStored.length, start + partSize);
  bigParts.push({ start, end, sha256: sha(bigStored.subarray(start, end)) });
}
const window = big.cipherRange(bigSize, CHUNK_SIZE - 10, CHUNK_SIZE + 10);
const windowPlain = await big.decryptRange(
  bigSize,
  CHUNK_SIZE - 10,
  CHUNK_SIZE + 10,
  bigStored.subarray(window.start, window.end),
);

const bigRanges = [];
for (const [start, end] of [
  [0, 81],
  [100, CHUNK_SIZE + 200],
  [HEADER_SIZE + CHUNK_SIZE + 16 - 5, HEADER_SIZE + CHUNK_SIZE + 16 + 5],
  [bigStored.length - 40, bigStored.length],
]) {
  const bytes = await big.encryptedRange(bigSize, start, end, async (a, b) =>
    bigPlain.subarray(a, b),
  );
  if (hex(bytes) !== hex(bigStored.subarray(start, end))) throw new Error('big encryptedRange');
  bigRanges.push({
    start,
    end,
    sha256: sha(bytes),
    plainRange: big.plainRangeFor(bigSize, start, end),
  });
}

const sizes = [
  0,
  1,
  4095,
  CHUNK_SIZE - 1,
  CHUNK_SIZE,
  CHUNK_SIZE + 1,
  3 * CHUNK_SIZE,
  10_000_000_000,
].map((plain) => ({ plain, stored: encryptedSize(plain), back: plainSize(encryptedSize(plain)) }));

writeFileSync(
  out,
  JSON.stringify(
    {
      note: 'Generated by apps/android/e2ee-vectors.ts from packages/api-client/src/e2ee.ts. Do not edit.',
      master: hex(master),
      subkeys: {
        fileWrap: hex(await hkdf(master, 'harbor0/e2ee/v1/file-keys', 32)),
        names: hex(await hkdf(master, 'harbor0/e2ee/v1/names', 64)),
        proofRaw: hex(await hkdf(master, 'harbor0/e2ee/v1/proof', 32)),
        proof: keys.proof,
      },
      passphrase: { passphrase, random: passRandom, envelope: passEnvelope },
      cheapPassphrase: { passphrase: 'hunter2', random: cheapRandom, envelope: cheapEnvelope },
      recovery: {
        bytes: hex(recovery),
        text: recoveryText,
        sloppy,
        invalid: ['', recoveryText.slice(0, -1), recoveryText.replace(/^./, '*')],
        random: recRandom,
        envelope: recEnvelope,
      },
      device: { local: hex(local), server: hex(server), random: devRandom, envelope: devEnvelope },
      names: nameVectors,
      smallFiles,
      bigFile: {
        size: bigSize,
        seed: 7,
        random: bigRandom,
        header: hex(big.header),
        storedSize: bigStored.length,
        sha256: sha(bigStored),
        plainSha256: sha(bigPlain),
        partSize,
        parts: bigParts,
        ranges: bigRanges,
        window: {
          begin: CHUNK_SIZE - 10,
          end: CHUNK_SIZE + 10,
          first: window.first,
          last: window.last,
          start: window.start,
          storedEnd: window.end,
          plain: hex(windowPlain),
        },
      },
      sizes,
    },
    null,
    2,
  ) + '\n',
);
console.log(`Wrote ${out}`);
process.exit(0);
