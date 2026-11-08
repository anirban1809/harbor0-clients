/**
 * End-to-end encryption, the parts every app shares. Uses WebCrypto (browsers, Node 20+) and
 * hash-wasm for Argon2id and streaming SHA-256.
 *
 * Keys: one random 32-byte master key per account. It is stored only wrapped (AES-256-GCM) by
 * a key derived from the passphrase (Argon2id), from the recovery key (HKDF) and from each
 * unlocked device's secret (HKDF over a device half and a server half). Subkeys come from the
 * master key with HKDF: one wraps file keys, one encrypts names, one proves possession.
 *
 * Files (format H0E1): an 80-byte header, then the content in 4 MiB chunks, each AES-256-GCM
 * with its own nonce (file prefix ‖ chunk index) and the header plus a last-chunk flag as
 * associated data, so chunks can't be reordered, swapped between files or cut off. Every file
 * version has its own random content key, wrapped in the header. Encrypting is deterministic
 * for a given header, so an interrupted upload resumes with the same bytes.
 *
 * Names: `h0e1.` + base64url(iv ‖ ciphertext). The IV is an HMAC of the name, so the same
 * name always encrypts the same way and the server can still keep names unique in a folder.
 * The server learns which items share a name, and nothing else about it.
 */
import { argon2id, createSHA256 } from 'hash-wasm';
import type { PassphraseEnvelope, RecoveryEnvelope } from '@harbor/contracts';

const subtle = () => globalThis.crypto.subtle;
const text = new TextEncoder();
const utf8 = new TextDecoder('utf-8', { fatal: true });
const MASTER_AAD = text.encode('harbor0/e2ee/v1/master');
const NAME_AAD = text.encode('harbor0/e2ee/v1/name');
export const NAME_PREFIX = 'h0e1.';
/** Longest name, in UTF-8 bytes, that fits the server's limit once encrypted. */
export const MAX_NAME_BYTES = 255;
export const CHUNK_SIZE = 4 * 1024 * 1024;
export const HEADER_SIZE = 80;
const TAG = 16;
const MAGIC = [0x48, 0x30, 0x45, 0x31]; // "H0E1"
/** Argon2id cost for new passphrases: OWASP's 64 MiB, 3 passes. */
export type Argon2Cost = { memoryKiB: number; iterations: number; parallelism: number };
export const ARGON2: Argon2Cost = { memoryKiB: 65_536, iterations: 3, parallelism: 1 };

export class E2eeError extends Error {
  constructor(
    public code: 'WRONG_KEY' | 'CORRUPT' | 'NAME_TOO_LONG' | 'LOCKED',
    message: string,
  ) {
    super(message);
    this.name = 'E2eeError';
  }
}

export const random = (n: number) => globalThis.crypto.getRandomValues(new Uint8Array(n));
export function b64url(bytes: Uint8Array) {
  let s = '';
  for (let i = 0; i < bytes.length; i += 0x8000)
    s += String.fromCharCode(...bytes.subarray(i, i + 0x8000));
  return btoa(s).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}
export function fromB64url(s: string) {
  const bin = atob(s.replace(/-/g, '+').replace(/_/g, '/') + '='.repeat((4 - (s.length % 4)) % 4));
  const out = new Uint8Array(bin.length);
  for (let i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i);
  return out;
}
const concat = (...parts: Uint8Array[]) => {
  const out = new Uint8Array(parts.reduce((n, p) => n + p.length, 0));
  let at = 0;
  for (const p of parts) {
    out.set(p, at);
    at += p.length;
  }
  return out;
};
// WebCrypto wants ArrayBuffer-backed views. Every buffer here is one, so this only tells
// TypeScript so; copying each 4 MiB chunk would double the work.
const buf = (b: Uint8Array) => b as Uint8Array<ArrayBuffer>;

async function hkdf(secret: Uint8Array, info: string, length = 32, salt = new Uint8Array()) {
  const base = await subtle().importKey('raw', buf(secret), 'HKDF', false, ['deriveBits']);
  return new Uint8Array(
    await subtle().deriveBits(
      { name: 'HKDF', hash: 'SHA-256', salt: buf(salt), info: text.encode(info) },
      base,
      length * 8,
    ),
  );
}
const aesKey = (raw: Uint8Array) =>
  subtle().importKey('raw', buf(raw), 'AES-GCM', false, ['encrypt', 'decrypt']);
async function seal(key: CryptoKey, iv: Uint8Array, data: Uint8Array, aad: Uint8Array) {
  return new Uint8Array(
    await subtle().encrypt(
      { name: 'AES-GCM', iv: buf(iv), additionalData: buf(aad) },
      key,
      buf(data),
    ),
  );
}
async function open(key: CryptoKey, iv: Uint8Array, data: Uint8Array, aad: Uint8Array) {
  try {
    return new Uint8Array(
      await subtle().decrypt(
        { name: 'AES-GCM', iv: buf(iv), additionalData: buf(aad) },
        key,
        buf(data),
      ),
    );
  } catch {
    throw new E2eeError('WRONG_KEY', 'The key doesn’t match.');
  }
}

async function wrapMaster(kek: Uint8Array, master: Uint8Array) {
  const iv = random(12);
  return {
    iv: b64url(iv),
    ciphertext: b64url(await seal(await aesKey(kek), iv, master, MASTER_AAD)),
  };
}
async function unwrapMaster(kek: Uint8Array, e: { iv: string; ciphertext: string }) {
  const master = await open(
    await aesKey(kek),
    fromB64url(e.iv),
    fromB64url(e.ciphertext),
    MASTER_AAD,
  );
  if (master.length !== 32) throw new E2eeError('CORRUPT', 'The stored key is damaged.');
  return master;
}

async function passphraseKek(passphrase: string, salt: Uint8Array, p: Argon2Cost) {
  return argon2id({
    password: passphrase.normalize('NFKC'),
    salt,
    parallelism: p.parallelism,
    iterations: p.iterations,
    memorySize: p.memoryKiB,
    hashLength: 32,
    outputType: 'binary',
  });
}
export async function wrapWithPassphrase(
  master: Uint8Array,
  passphrase: string,
  cost: Argon2Cost = ARGON2,
): Promise<PassphraseEnvelope> {
  const salt = random(16);
  return {
    v: 1,
    kdf: 'argon2id',
    salt: b64url(salt),
    ...cost,
    ...(await wrapMaster(await passphraseKek(passphrase, salt, cost), master)),
  };
}
export async function unwrapWithPassphrase(e: PassphraseEnvelope, passphrase: string) {
  const kek = await passphraseKek(passphrase, fromB64url(e.salt), e);
  return unwrapMaster(kek, e);
}

// Recovery keys: 20 random bytes as 32 Crockford base32 characters, shown in 8 groups of 4.
const CROCKFORD = '0123456789ABCDEFGHJKMNPQRSTVWXYZ';
export function formatRecoveryKey(bytes: Uint8Array) {
  let bits = 0;
  let value = 0;
  let out = '';
  for (const b of bytes) {
    value = (value << 8) | b;
    bits += 8;
    while (bits >= 5) {
      out += CROCKFORD[(value >>> (bits - 5)) & 31];
      bits -= 5;
    }
  }
  return out.match(/.{4}/g)!.join('-');
}
/** Reads a typed recovery key, forgiving case, spaces, dashes and look-alike letters. */
export function parseRecoveryKey(typed: string) {
  const chars = typed
    .toUpperCase()
    .replace(/[\s-]/g, '')
    .replace(/O/g, '0')
    .replace(/[IL]/g, '1')
    .replace(/U/g, 'V');
  if (!/^[0-9A-HJKMNP-TV-Z]{32}$/.test(chars)) return null;
  const out = new Uint8Array(20);
  let bits = 0;
  let value = 0;
  let at = 0;
  for (const c of chars) {
    value = (value << 5) | CROCKFORD.indexOf(c);
    bits += 5;
    if (bits >= 8) {
      out[at++] = (value >>> (bits - 8)) & 255;
      bits -= 8;
    }
  }
  return out;
}
export const newRecoveryKey = () => random(20);
export async function wrapWithRecovery(
  master: Uint8Array,
  recovery: Uint8Array,
): Promise<RecoveryEnvelope> {
  const salt = random(16);
  const kek = await hkdf(recovery, 'harbor0/e2ee/v1/recovery', 32, salt);
  return { v: 1, kdf: 'hkdf', salt: b64url(salt), ...(await wrapMaster(kek, master)) };
}
export async function unwrapWithRecovery(e: RecoveryEnvelope, recovery: Uint8Array) {
  return unwrapMaster(await hkdf(recovery, 'harbor0/e2ee/v1/recovery', 32, fromB64url(e.salt)), e);
}

/** A device's copy: the key wrapped by both halves of the device secret. */
export type DeviceEnvelope = { iv: string; ciphertext: string };
const deviceKek = (local: Uint8Array, server: Uint8Array) =>
  hkdf(concat(local, server), 'harbor0/e2ee/v1/device');
export async function wrapForDevice(master: Uint8Array, local: Uint8Array, server: Uint8Array) {
  return wrapMaster(await deviceKek(local, server), master);
}
export async function unwrapForDevice(e: DeviceEnvelope, local: Uint8Array, server: Uint8Array) {
  return unwrapMaster(await deviceKek(local, server), e);
}

/** The keys an unlocked device works with, derived once from the master key. */
export type AccountKeys = {
  master: Uint8Array;
  fileWrap: CryptoKey;
  nameEnc: CryptoKey;
  nameMac: CryptoKey;
  /** Sent to the server to show this device holds the key. */
  proof: string;
};
export async function accountKeys(master: Uint8Array): Promise<AccountKeys> {
  const [fileWrap, names, proof] = await Promise.all([
    hkdf(master, 'harbor0/e2ee/v1/file-keys'),
    hkdf(master, 'harbor0/e2ee/v1/names', 64),
    hkdf(master, 'harbor0/e2ee/v1/proof'),
  ]);
  return {
    master,
    fileWrap: await aesKey(fileWrap),
    nameEnc: await aesKey(names.subarray(0, 32)),
    nameMac: await subtle().importKey(
      'raw',
      buf(names.subarray(32)),
      { name: 'HMAC', hash: 'SHA-256' },
      false,
      ['sign'],
    ),
    proof: b64url(proof),
  };
}
export const newMasterKey = () => random(32);

export const isEncryptedName = (name: string) => /^h0e1\.[A-Za-z0-9_-]+$/.test(name);
export async function encryptName(keys: AccountKeys, name: string) {
  const plain = text.encode(name.normalize('NFC'));
  if (plain.length > MAX_NAME_BYTES)
    throw new E2eeError('NAME_TOO_LONG', 'This name is too long for an encrypted account.');
  const iv = new Uint8Array(await subtle().sign('HMAC', keys.nameMac, buf(plain))).subarray(0, 12);
  return NAME_PREFIX + b64url(concat(iv, await seal(keys.nameEnc, iv, plain, NAME_AAD)));
}
export async function decryptName(keys: AccountKeys, token: string) {
  const bytes = fromB64url(token.slice(NAME_PREFIX.length));
  if (bytes.length < 12 + TAG) throw new E2eeError('CORRUPT', 'The name is damaged.');
  return utf8.decode(await open(keys.nameEnc, bytes.subarray(0, 12), bytes.subarray(12), NAME_AAD));
}

/** Stored size of a file of `plain` bytes. */
export const encryptedSize = (plain: number) =>
  HEADER_SIZE + plain + TAG * Math.max(1, Math.ceil(plain / CHUNK_SIZE));
/** Original size of a stored encrypted file. */
export function plainSize(stored: number) {
  const body = stored - HEADER_SIZE;
  if (body < TAG) return 0;
  return body - TAG * Math.ceil(body / (CHUNK_SIZE + TAG));
}
const chunkCount = (plain: number) => Math.max(1, Math.ceil(plain / CHUNK_SIZE));

function chunkSizeOf(header: Uint8Array) {
  if (header.length < LINK_HEAD_SIZE || MAGIC.some((b, i) => header[i] !== b) || header[4] !== 1)
    throw new E2eeError('CORRUPT', 'This file isn’t encrypted the way harbor0 expects.');
  const chunk = new DataView(header.buffer, header.byteOffset).getUint32(8);
  if (chunk < 4096 || chunk > 64 * 1024 * 1024)
    throw new E2eeError('CORRUPT', 'This file’s header is damaged.');
  return chunk;
}
/** A stored version's random content key, unwrapped from its 80-byte header. */
async function unwrapContentKey(keys: Pick<AccountKeys, 'fileWrap'>, header: Uint8Array) {
  if (header.length < HEADER_SIZE)
    throw new E2eeError('CORRUPT', 'This file isn’t encrypted the way harbor0 expects.');
  chunkSizeOf(header);
  return open(
    keys.fileWrap,
    header.subarray(20, 32),
    header.subarray(32, 80),
    header.subarray(0, 20),
  );
}

/** One file version's key, ready to encrypt or decrypt its chunks. */
export class FileCipher {
  private constructor(
    readonly header: Uint8Array,
    private key: CryptoKey,
    private prefix: Uint8Array,
    readonly chunkSize: number,
  ) {}
  /** A new random key for a new version. */
  static async create(keys: Pick<AccountKeys, 'fileWrap'>) {
    const content = random(32);
    const head = new Uint8Array(HEADER_SIZE);
    head.set(MAGIC, 0);
    head[4] = 1;
    new DataView(head.buffer).setUint32(8, CHUNK_SIZE);
    head.set(random(8), 12);
    const wrapIv = random(12);
    head.set(wrapIv, 20);
    const aad = head.subarray(0, 20);
    head.set(await seal(keys.fileWrap, wrapIv, content, aad), 32);
    return new FileCipher(head, await aesKey(content), head.slice(12, 20), CHUNK_SIZE);
  }
  /** Reads the key from a stored file's header. */
  static async open(keys: Pick<AccountKeys, 'fileWrap'>, header: Uint8Array) {
    const head = header.slice(0, HEADER_SIZE);
    const content = await unwrapContentKey(keys, head);
    return new FileCipher(head, await aesKey(content), head.slice(12, 20), chunkSizeOf(head));
  }
  /**
   * A version's cipher from its content key and the first 20 bytes of its header (magic,
   * chunk size, nonce prefix): what a public link hands out instead of the account's key.
   */
  static async withKey(head: Uint8Array, content: Uint8Array) {
    const header = new Uint8Array(HEADER_SIZE);
    header.set(head.subarray(0, LINK_HEAD_SIZE));
    return new FileCipher(header, await aesKey(content), header.slice(12, 20), chunkSizeOf(header));
  }
  private nonce(index: number) {
    const n = new Uint8Array(12);
    n.set(this.prefix, 0);
    new DataView(n.buffer).setUint32(8, index);
    return n;
  }
  private aad(last: boolean) {
    return concat(this.header.subarray(0, 20), new Uint8Array([last ? 1 : 0]));
  }
  encryptChunk(index: number, plain: Uint8Array, last: boolean) {
    return seal(this.key, this.nonce(index), plain, this.aad(last));
  }
  async decryptChunk(index: number, cipher: Uint8Array, last: boolean) {
    try {
      return await open(this.key, this.nonce(index), cipher, this.aad(last));
    } catch {
      throw new E2eeError('CORRUPT', 'Part of this file is damaged or was changed.');
    }
  }
  /** Stored byte range [start, end) of the chunks holding plaintext [begin, end). */
  cipherRange(plainTotal: number, begin: number, end: number) {
    const first = Math.floor(begin / this.chunkSize);
    const last = Math.max(first, Math.ceil(end / this.chunkSize) - 1);
    const stored = HEADER_SIZE + plainTotal + TAG * chunkCount(plainTotal);
    return {
      first,
      last,
      start: HEADER_SIZE + first * (this.chunkSize + TAG),
      end: Math.min(stored, HEADER_SIZE + (last + 1) * (this.chunkSize + TAG)),
    };
  }
  /**
   * Decrypts the chunks in a stored range read with `cipherRange`, returning plaintext
   * [begin, end). `plainTotal` tells which chunk is the last.
   */
  async decryptRange(plainTotal: number, begin: number, end: number, stored: Uint8Array) {
    const { first, last } = this.cipherRange(plainTotal, begin, end);
    const final = chunkCount(plainTotal) - 1;
    const parts: Uint8Array[] = [];
    let at = 0;
    for (let i = first; i <= last; i++) {
      const len = Math.min(this.chunkSize, plainTotal - i * this.chunkSize) + TAG;
      parts.push(await this.decryptChunk(i, stored.subarray(at, at + len), i === final));
      at += len;
    }
    const plain = concat(...parts);
    const offset = begin - first * this.chunkSize;
    return plain.subarray(offset, offset + (end - begin));
  }
  /** The plaintext [begin, end) that stored bytes [start, end) are sealed from, if any. */
  plainRangeFor(plainTotal: number, start: number, end: number): [number, number] | null {
    if (end <= HEADER_SIZE) return null;
    const span = this.chunkSize + TAG;
    const first = Math.floor((Math.max(start, HEADER_SIZE) - HEADER_SIZE) / span);
    const last = Math.floor((end - 1 - HEADER_SIZE) / span);
    return [first * this.chunkSize, Math.min(plainTotal, (last + 1) * this.chunkSize)];
  }
  /**
   * Stored bytes [start, end) of the encrypted file, reading plaintext through `read`. Only the
   * chunks the range touches are encrypted.
   */
  async encryptedRange(
    plainTotal: number,
    start: number,
    end: number,
    read: (begin: number, end: number) => Promise<Uint8Array>,
  ) {
    const out: Uint8Array[] = [];
    if (start < HEADER_SIZE) out.push(this.header.subarray(start, Math.min(end, HEADER_SIZE)));
    const range = this.plainRangeFor(plainTotal, start, end);
    if (range) {
      const span = this.chunkSize + TAG;
      const first = Math.floor((Math.max(start, HEADER_SIZE) - HEADER_SIZE) / span);
      const last = Math.floor((end - 1 - HEADER_SIZE) / span);
      const final = chunkCount(plainTotal) - 1;
      const plain = await read(range[0], range[1]);
      for (let i = first; i <= last && i <= final; i++) {
        const from = (i - first) * this.chunkSize;
        const sealed = await seal(
          this.key,
          this.nonce(i),
          plain.subarray(from, Math.min(plain.length, from + this.chunkSize)),
          this.aad(i === final),
        );
        const chunkStart = HEADER_SIZE + i * span;
        out.push(
          sealed.subarray(
            Math.max(0, start - chunkStart),
            Math.min(sealed.length, end - chunkStart),
          ),
        );
      }
    }
    return concat(...out);
  }
}

/**
 * A small file encrypted whole, as any file is (H0E1, with its own random key): how apps keep a
 * Photos thumbnail of an encrypted photo.
 */
export async function sealWhole(keys: Pick<AccountKeys, 'fileWrap'>, plain: Uint8Array) {
  const cipher = await FileCipher.create(keys);
  const count = chunkCount(plain.length);
  const chunks: Uint8Array[] = [];
  for (let i = 0; i < count; i++)
    chunks.push(
      await cipher.encryptChunk(
        i,
        plain.subarray(i * CHUNK_SIZE, (i + 1) * CHUNK_SIZE),
        i === count - 1,
      ),
    );
  return concat(cipher.header, ...chunks);
}
/** The plaintext of a small file read whole (`sealWhole`). */
export async function openWhole(keys: Pick<AccountKeys, 'fileWrap'>, stored: Uint8Array) {
  const cipher = await FileCipher.open(keys, stored);
  const total = plainSize(stored.length);
  return cipher.decryptRange(total, 0, total, stored.subarray(HEADER_SIZE));
}
/** Streaming SHA-256, for the stored (encrypted) bytes the server checks uploads against. */
export async function sha256Stream() {
  const hash = await createSHA256();
  hash.init();
  return {
    update: (bytes: Uint8Array) => void hash.update(bytes),
    hex: () => hash.digest('hex'),
    /** The hash so far, to carry on from later with `load`, even in another page. */
    save: () => b64url(hash.save()),
    load: (state: string) => void hash.load(fromB64url(state)),
  };
}

/*
 * Public links (docs/specs/public-folder-links.md). A link's random 32-byte secret is only in its
 * URL; keys derived from it seal each shared item's name and content key. The owner's account
 * keeps the secret wrapped by the master key so its apps can show the link again.
 */
/** Bytes of a header a link wrap is bound to and hands out: magic, chunk size, nonce prefix. */
export const LINK_HEAD_SIZE = 20;
const LINK_SECRET_AAD = text.encode('harbor0/e2ee/v1/link-secret');
export type LinkKeys = { names: CryptoKey; files: CryptoKey };

export const newLinkSecret = () => random(32);
export async function linkKeys(secret: Uint8Array): Promise<LinkKeys> {
  const [names, files] = await Promise.all([
    hkdf(secret, 'harbor0/e2ee/v1/link-names'),
    hkdf(secret, 'harbor0/e2ee/v1/link-files'),
  ]);
  return { names: await aesKey(names), files: await aesKey(files) };
}
async function sealed(key: CryptoKey, data: Uint8Array, aad: Uint8Array) {
  const iv = random(12);
  return b64url(concat(iv, await seal(key, iv, data, aad)));
}
async function opened(key: CryptoKey, wrapped: string, aad: Uint8Array) {
  const bytes = fromB64url(wrapped);
  if (bytes.length < 12 + TAG) throw new E2eeError('CORRUPT', 'The link’s data is damaged.');
  return open(key, bytes.subarray(0, 12), bytes.subarray(12), aad);
}
const linkSecretKey = async (keys: Pick<AccountKeys, 'master'>) =>
  aesKey(await hkdf(keys.master, 'harbor0/e2ee/v1/link-secrets'));
/** The link's secret wrapped by the account, for the server to keep for the owner. */
export async function wrapLinkSecret(keys: Pick<AccountKeys, 'master'>, secret: Uint8Array) {
  return sealed(await linkSecretKey(keys), secret, LINK_SECRET_AAD);
}
export async function unwrapLinkSecret(keys: Pick<AccountKeys, 'master'>, secretWrap: string) {
  return opened(await linkSecretKey(keys), secretWrap, LINK_SECRET_AAD);
}
/** The link's address with its secret: the server's URL plus `.<secret>` in the fragment. */
export const linkUrl = (url: string, secret: Uint8Array) => `${url}.${b64url(secret)}`;

// The item id is bound in, so the server can't move a name to another item.
const linkNameAad = (itemId: string) => text.encode(`harbor0/e2ee/v1/link-name:${itemId}`);
export async function wrapLinkName(link: LinkKeys, itemId: string, name: string) {
  return sealed(link.names, text.encode(name.normalize('NFC')), linkNameAad(itemId));
}
export async function unwrapLinkName(link: LinkKeys, itemId: string, nameWrap: string) {
  return utf8.decode(await opened(link.names, nameWrap, linkNameAad(itemId)));
}

// The version's header is bound in: it is random per version, so a wrap opens no other file.
const linkFileAad = (head: Uint8Array) =>
  concat(text.encode('harbor0/e2ee/v1/link-file'), head.subarray(0, LINK_HEAD_SIZE));
/** A stored version's content key, re-sealed for a link, from the 80-byte header the server keeps. */
export async function wrapLinkFileKey(
  keys: Pick<AccountKeys, 'fileWrap'>,
  link: LinkKeys,
  header: Uint8Array,
) {
  const content = await unwrapContentKey(keys, header);
  const head = header.slice(0, LINK_HEAD_SIZE);
  return { keyWrap: await sealed(link.files, content, linkFileAad(head)), head: b64url(head) };
}
/** A visitor's cipher for a file shared by a link. */
export async function openLinkFile(link: LinkKeys, wrap: { keyWrap: string; head: string }) {
  const head = fromB64url(wrap.head);
  const content = await opened(link.files, wrap.keyWrap, linkFileAad(head));
  return FileCipher.withKey(head, content);
}

/*
 * File requests and client rooms (docs/specs/client-rooms-file-requests.md). A request's secret is
 * only in its link, like a public link's, and seals its details. Guests seal each file's key to
 * the request's P-256 public key, so only the owner's account can open what they send.
 */
const P256 = { name: 'ECDH', namedCurve: 'P-256' } as const;
const REQUEST_SECRET_AAD = text.encode('harbor0/e2ee/v1/request-private-key');
const REQUEST_DETAILS_AAD = text.encode('harbor0/e2ee/v1/request-details');
const ROOM_AAD = text.encode('harbor0/e2ee/v1/room');
/** A P-256 public key in raw form, and the two parts of a guest's sealed key. */
const PUBLIC_KEY_SIZE = 65;

export type RequestDetails = { title: string; message: string; publicKey: string };
/** What a guest says about a file; sealed so the server sees none of it. */
export type RequestFileMeta = { name: string; type: string; sender: string | null };

const requestKeyWrapKey = async (keys: Pick<AccountKeys, 'master'>) =>
  aesKey(await hkdf(keys.master, 'harbor0/e2ee/v1/request-keys'));
const requestDetailsKey = async (secret: Uint8Array) =>
  aesKey(await hkdf(secret, 'harbor0/e2ee/v1/request-details'));

/** A new request's secret and key pair, as the server keeps them and as the link carries them. */
export async function newFileRequest(
  keys: Pick<AccountKeys, 'master'>,
  details: { title: string; message: string },
) {
  const pair = (await subtle().generateKey(P256, true, ['deriveBits'])) as CryptoKeyPair;
  const publicKey = b64url(new Uint8Array(await subtle().exportKey('raw', pair.publicKey)));
  const privateKey = new Uint8Array(await subtle().exportKey('pkcs8', pair.privateKey));
  const secret = newLinkSecret();
  return {
    secret,
    e2ee: {
      secretWrap: await wrapLinkSecret(keys, secret),
      privateKeyWrap: await sealed(await requestKeyWrapKey(keys), privateKey, REQUEST_SECRET_AAD),
      details: await sealRequestDetails(secret, { ...details, publicKey }),
    },
  };
}
export async function sealRequestDetails(secret: Uint8Array, details: RequestDetails) {
  return sealed(
    await requestDetailsKey(secret),
    text.encode(JSON.stringify(details)),
    REQUEST_DETAILS_AAD,
  );
}
export async function openRequestDetails(secret: Uint8Array, details: string) {
  const plain = await opened(await requestDetailsKey(secret), details, REQUEST_DETAILS_AAD);
  return JSON.parse(utf8.decode(plain)) as RequestDetails;
}

const requestFileKey = async (shared: ArrayBuffer, head: Uint8Array, info: string) =>
  aesKey(await hkdf(new Uint8Array(shared), info, 32, buf(head)));
const REQUEST_KEY_INFO = 'harbor0/e2ee/v1/request-file-key';
const REQUEST_META_INFO = 'harbor0/e2ee/v1/request-file-meta';
const REQUEST_FILE_AAD = text.encode('harbor0/e2ee/v1/request-file');

/**
 * A guest's cipher for one file: the normal format under a random content key, with the part of
 * the header that holds the wrapped key left blank for the owner's app to fill in. Its key and
 * details are sealed to the request's public key with a key pair used for this file alone.
 */
export async function sealRequestFile(publicKey: string, meta: RequestFileMeta) {
  const content = random(32);
  const head = new Uint8Array(HEADER_SIZE);
  head.set(MAGIC, 0);
  head[4] = 1;
  new DataView(head.buffer).setUint32(8, CHUNK_SIZE);
  head.set(random(8), 12);
  const cipher = await FileCipher.withKey(head, content);
  const theirs = await subtle().importKey('raw', buf(fromB64url(publicKey)), P256, false, []);
  const mine = (await subtle().generateKey(P256, true, ['deriveBits'])) as CryptoKeyPair;
  const shared = await subtle().deriveBits({ name: 'ECDH', public: theirs }, mine.privateKey, 256);
  const bound = head.subarray(0, LINK_HEAD_SIZE);
  const ephemeral = new Uint8Array(await subtle().exportKey('raw', mine.publicKey));
  const key = await sealed(
    await requestFileKey(shared, bound, REQUEST_KEY_INFO),
    content,
    REQUEST_FILE_AAD,
  );
  return {
    cipher,
    sealed: {
      head: b64url(bound),
      sealedKey: b64url(concat(ephemeral, fromB64url(key))),
      sealedMeta: await sealed(
        await requestFileKey(shared, bound, REQUEST_META_INFO),
        text.encode(JSON.stringify(meta)),
        REQUEST_FILE_AAD,
      ),
    },
  };
}

/**
 * Opens a guest's file on the owner's side: what they said about it, and the normal 80-byte header
 * that wraps its content key with the account's, which replaces the blank one in storage.
 */
export async function adoptRequestFile(
  keys: Pick<AccountKeys, 'master' | 'fileWrap'>,
  file: { privateKeyWrap: string; head: string; sealedKey: string; sealedMeta: string },
) {
  const pkcs8 = await opened(
    await requestKeyWrapKey(keys),
    file.privateKeyWrap,
    REQUEST_SECRET_AAD,
  );
  const privateKey = await subtle().importKey('pkcs8', buf(pkcs8), P256, false, ['deriveBits']);
  const bytes = fromB64url(file.sealedKey);
  const theirs = await subtle().importKey(
    'raw',
    buf(bytes.subarray(0, PUBLIC_KEY_SIZE)),
    P256,
    false,
    [],
  );
  const shared = await subtle().deriveBits({ name: 'ECDH', public: theirs }, privateKey, 256);
  const bound = fromB64url(file.head);
  const content = await opened(
    await requestFileKey(shared, bound, REQUEST_KEY_INFO),
    b64url(bytes.subarray(PUBLIC_KEY_SIZE)),
    REQUEST_FILE_AAD,
  );
  const meta = await opened(
    await requestFileKey(shared, bound, REQUEST_META_INFO),
    file.sealedMeta,
    REQUEST_FILE_AAD,
  );
  const header = new Uint8Array(HEADER_SIZE);
  header.set(bound);
  const wrapIv = random(12);
  header.set(wrapIv, 20);
  header.set(await seal(keys.fileWrap, wrapIv, content, bound), 32);
  return {
    header: b64url(header),
    meta: JSON.parse(utf8.decode(meta)) as RequestFileMeta,
  };
}

/** An encrypted link's room page, sealed so only someone with the link can read it. */
export const sealRoom = (link: LinkKeys, content: string) =>
  sealed(link.names, text.encode(content), ROOM_AAD);
export const openRoom = async (link: LinkKeys, sealedContent: string) =>
  utf8.decode(await opened(link.names, sealedContent, ROOM_AAD));
