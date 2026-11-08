import { z } from 'zod';
import { publicLinkExpiry } from './public-links';

/**
 * File requests: a link where people without an account upload into one of the owner's folders.
 * They see nothing that's in it. See docs/specs/client-rooms-file-requests.md.
 */

/** Requests that still work, per account. */
export const FILE_REQUEST_LIMIT = 100;
export const FILE_REQUEST_DEFAULT_EXPIRY_DAYS = 30;
/** Limits a new request starts with, and the most an owner can set. */
export const FILE_REQUEST_DEFAULTS = {
  maxFileBytes: 10_000_000_000,
  maxFiles: 200,
  maxTotalBytes: 100_000_000_000,
} as const;
export const FILE_REQUEST_LIMITS = {
  maxFileBytes: 100_000_000_000,
  maxFiles: 5000,
  maxTotalBytes: 1_000_000_000_000,
} as const;
/** Requests a minute to one request's routes, from everyone, and from one visitor. */
export const FILE_REQUEST_RATE = { request: 300, visitor: 120 } as const;
/** Most parts one signing request returns. */
export const FILE_REQUEST_PARTS = 8;

/** 16 random bytes, base64url: the whole secret of a standard request's link. */
export const fileRequestToken = z.string().regex(/^[A-Za-z0-9_-]{22}$/);

const b64 = (max: number) =>
  z
    .string()
    .regex(/^[A-Za-z0-9_-]*$/)
    .max(max);

/** Sealed for the request by the owner's browser; what the server keeps of an encrypted one. */
export const fileRequestE2eeSchema = z.object({
  /** The link's secret, wrapped by the account's master key. */
  secretWrap: z.string(),
  /** The request's P-256 private key, wrapped by the account's master key. */
  privateKeyWrap: z.string(),
  /** Title, note and public key, sealed with a key derived from the link's secret. */
  details: z.string(),
});
export type FileRequestE2ee = z.infer<typeof fileRequestE2eeSchema>;

export const fileRequestSchema = z.object({
  id: z.string(),
  /** The folder uploads land in. */
  driveItemId: z.string(),
  /** The folder's name now; null once it's in the trash, when the request stops working. */
  folderName: z.string().nullable(),
  /** Empty on an encrypted request: they're in `e2ee.details`. */
  title: z.string(),
  message: z.string(),
  /** The address to share. An encrypted request's lacks its secret; see `e2ee`. */
  url: z.string(),
  hasPassword: z.boolean(),
  askName: z.boolean(),
  createdAt: z.string(),
  expiresAt: z.string().nullable(),
  disabledAt: z.string().nullable(),
  maxFileBytes: z.number(),
  maxFiles: z.number(),
  maxTotalBytes: z.number(),
  /** Files and bytes received so far and on the way. */
  usedFiles: z.number(),
  usedBytes: z.number(),
  e2ee: fileRequestE2eeSchema.nullable(),
});
export type FileRequest = z.infer<typeof fileRequestSchema>;

const limits = {
  maxFileBytes: z.number().int().min(1).max(FILE_REQUEST_LIMITS.maxFileBytes),
  maxFiles: z.number().int().min(1).max(FILE_REQUEST_LIMITS.maxFiles),
  maxTotalBytes: z.number().int().min(1).max(FILE_REQUEST_LIMITS.maxTotalBytes),
};
export const fileRequestInput = z
  .object({
    driveItemId: z.string().min(1).max(128),
    title: z.string().trim().max(120).default(''),
    message: z.string().trim().max(2000).default(''),
    expiresInDays: publicLinkExpiry.nullable().default(FILE_REQUEST_DEFAULT_EXPIRY_DAYS),
    password: z.string().min(4).max(128).nullable().default(null),
    askName: z.boolean().default(true),
    /** A room's request: guests may add files and folders anywhere inside the folder. */
    room: z.boolean().default(false),
    maxFileBytes: limits.maxFileBytes.default(FILE_REQUEST_DEFAULTS.maxFileBytes),
    maxFiles: limits.maxFiles.default(FILE_REQUEST_DEFAULTS.maxFiles),
    maxTotalBytes: limits.maxTotalBytes.default(FILE_REQUEST_DEFAULTS.maxTotalBytes),
    /** Required from end-to-end encrypted accounts. */
    e2ee: z
      .object({
        secretWrap: b64(200).min(40),
        privateKeyWrap: b64(1000).min(40),
        details: b64(8000).min(40),
      })
      .strict()
      .optional(),
  })
  .strict();
export const fileRequestUpdate = z
  .object({
    title: z.string().trim().max(120).optional(),
    message: z.string().trim().max(2000).optional(),
    details: b64(8000).min(40).optional(),
    expiresInDays: publicLinkExpiry.nullable().optional(),
    password: z.string().min(4).max(128).nullable().optional(),
    askName: z.boolean().optional(),
    maxFileBytes: limits.maxFileBytes.optional(),
    maxFiles: limits.maxFiles.optional(),
    maxTotalBytes: limits.maxTotalBytes.optional(),
  })
  .strict();
export const fileRequestAllowanceSchema = z.object({
  blockedReason: z.string().nullable(),
  active: z.number().int(),
  limit: z.number().int(),
});

/** What's been received. On an encrypted request, files show here once they're in the drive. */
export const fileRequestFileSchema = z.object({
  id: z.string(),
  /** On an encrypted request, this and `uploaderName` are encrypted like any file name. */
  name: z.string(),
  sizeBytes: z.number(),
  uploaderName: z.string().nullable(),
  receivedAt: z.string(),
  itemId: z.string().nullable(),
});
export type FileRequestFile = z.infer<typeof fileRequestFileSchema>;

/** A guest's encrypted file, waiting for the owner's app to add it to the drive. */
export const fileRequestInboxItemSchema = z.object({
  id: z.string(),
  requestId: z.string(),
  sizeBytes: z.number(),
  receivedAt: z.string(),
  /** Waiting for a header, being rewritten, or ready to add. */
  state: z.enum(['STAGED', 'REWRITING', 'READY']),
  /** The first 20 bytes of its H0E1 header. */
  head: z.string(),
  /** The content key and what's known about the file, sealed to the request's public key. */
  sealedKey: z.string(),
  sealedMeta: z.string(),
  /** The request's private key, wrapped by the account. */
  privateKeyWrap: z.string(),
});
export type FileRequestInboxItem = z.infer<typeof fileRequestInboxItemSchema>;
export const fileRequestHeaderInput = z.object({ header: b64(120).min(100) }).strict();
export const fileRequestAdoptInput = z
  .object({
    name: z.string().min(1).max(2048),
    /** Who sent it, encrypted like a file name, for the request's file list. */
    uploaderName: z.string().max(2048).nullable().default(null),
  })
  .strict();

export const fileRequestViewSchema = z.object({
  requestedBy: z.object({ displayName: z.string(), memberSince: z.string() }),
  expiresAt: z.string().nullable(),
  passwordRequired: z.boolean(),
  e2ee: z.boolean(),
  /** Withheld until the password is given. */
  title: z.string(),
  message: z.string(),
  /** An encrypted request's sealed title, note and public key. */
  details: z.string().nullable(),
  askName: z.boolean(),
  maxFileBytes: z.number(),
  filesLeft: z.number(),
  bytesLeft: z.number(),
  /** Guests may choose a folder inside the request's, and make new ones. */
  folders: z.boolean(),
});
export type FileRequestView = z.infer<typeof fileRequestViewSchema>;

export const fileRequestUploadInput = z
  .object({
    /** The file's name; ignored for an encrypted file, whose name is in `sealed`. */
    name: z.string().max(255).default(''),
    sizeBytes: z.number().int().min(0).max(5_000_000_000_000),
    mimeType: z.string().max(255).default('application/octet-stream'),
    uploaderName: z.string().trim().max(100).nullable().default(null),
    /** On a room's request, a folder inside the room. */
    folderId: z.string().min(1).max(128).optional(),
    /** Required on encrypted requests. */
    sealed: z
      .object({ head: b64(40).min(20), sealedKey: b64(400).min(40), sealedMeta: b64(4000).min(20) })
      .strict()
      .optional(),
  })
  .strict();
export const fileRequestFolderInput = z
  .object({
    name: z.string().trim().min(1).max(255),
    folderId: z.string().min(1).max(128).optional(),
    /** Use a folder already called this, as an uploaded folder does. */
    existing: z.boolean().default(false),
  })
  .strict();
export const fileRequestUploadSchema = z.object({
  id: z.string(),
  partSizeBytes: z.number(),
});
export const fileRequestPartsInput = z
  .object({ partNumbers: z.array(z.number().int().min(1)).min(1).max(FILE_REQUEST_PARTS) })
  .strict();
export const fileRequestPartsSchema = z.object({
  parts: z.array(z.object({ partNumber: z.number(), uploadUrl: z.string() })),
});
export const fileRequestCompleteInput = z
  .object({
    parts: z
      .array(z.object({ partNumber: z.number().int().min(1), etag: z.string().max(256) }).strict())
      .min(1)
      .max(10000),
    contentHash: z.string().regex(/^[a-f0-9]{64}$/),
  })
  .strict();

/** A visitor's report of a public link or file request. */
export const abuseReportInput = z
  .object({
    kind: z.enum(['LINK', 'REQUEST']),
    token: fileRequestToken,
    /** The full address, so staff can open exactly what was reported, even if it's encrypted. */
    url: z.string().max(512),
    reason: z.enum(['ILLEGAL', 'COPYRIGHT', 'MALWARE', 'PHISHING', 'OTHER']),
    details: z.string().trim().max(2000).default(''),
    contact: z.string().trim().max(254).default(''),
  })
  .strict();
export type AbuseReportInput = z.infer<typeof abuseReportInput>;
/** Reports one visitor may send an hour. */
export const ABUSE_REPORT_HOURLY = 5;
