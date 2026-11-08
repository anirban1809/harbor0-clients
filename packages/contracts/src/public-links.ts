import { z } from 'zod';

/**
 * Public links let anyone with the link view and download a file, or a folder's files, without
 * an account. Pages for them are served from their own domain (harbor0content.online), so if a
 * shared file gets that domain flagged, harbor0.com and the app are unaffected.
 */

/** Accounts younger than this can't link to executables and installers. */
export const PUBLIC_LINK_EXECUTABLE_MIN_AGE_DAYS = 30;
/** Links that still work, per account. */
export const PUBLIC_LINK_LIMIT = { free: 20, paid: 500 } as const;
/** Free accounts' links all expire; paid accounts may also choose a link that never expires. */
export const publicLinkExpiry = z.union([z.literal(1), z.literal(7), z.literal(30)]);
export const PUBLIC_LINK_DEFAULT_EXPIRY_DAYS = 7;
/** Requests a minute to one link, from everyone, and from one visitor across all links. */
export const PUBLIC_LINK_RATE = { link: 120, visitor: 60 } as const;
/** Wrong passwords a link accepts an hour before it asks the visitor to wait. */
export const PUBLIC_LINK_PASSWORD_ATTEMPTS = 10;

/**
 * Files people use to spread malware: programs, installers, scripts and disk images. Young
 * accounts can't link to them, and the public page warns before anyone downloads one.
 */
export const EXECUTABLE_EXTENSIONS = [
  'exe',
  'msi',
  'msix',
  'appx',
  'scr',
  'com',
  'bat',
  'cmd',
  'ps1',
  'vbs',
  'vbe',
  'js',
  'jse',
  'wsf',
  'hta',
  'cpl',
  'jar',
  'apk',
  'xapk',
  'aab',
  'ipa',
  'dmg',
  'pkg',
  'app',
  'deb',
  'rpm',
  'appimage',
  'sh',
  'run',
  'bin',
  'lnk',
  'iso',
  'img',
  'reg',
  'dll',
  'sys',
  'docm',
  'xlsm',
  'pptm',
] as const;
const executables = new Set<string>(EXECUTABLE_EXTENSIONS);
export const isExecutableName = (name: string) => {
  const dot = name.lastIndexOf('.');
  return dot > 0 && executables.has(name.slice(dot + 1).toLowerCase());
};

/** 16 random bytes, base64url: the whole secret of a link. */
export const publicLinkToken = z.string().regex(/^[A-Za-z0-9_-]{22}$/);

export const publicLinkSchema = z.object({
  id: z.string(),
  driveItemId: z.string(),
  itemType: z.enum(['FILE', 'FOLDER']),
  /** The item's name now; null once it is deleted, when the link stops working. */
  itemName: z.string().nullable(),
  /** The address to share. The owner can copy it again at any time. */
  url: z.string(),
  hasPassword: z.boolean(),
  createdAt: z.string(),
  /** Null when the link never expires. */
  expiresAt: z.string().nullable(),
  /** Set by staff when the link was taken down; the owner can't turn it back on. */
  disabledAt: z.string().nullable(),
  /**
   * An end-to-end encrypted link: the link's secret, wrapped by the account's master key. `url`
   * lacks the secret; the owner's app unwraps it and appends `.<secret>` to rebuild the link.
   */
  e2ee: z.object({ secretWrap: z.string() }).nullable(),
  /** The link opens as a client room: see `roomContentSchema`. */
  room: z.boolean().default(false),
});
export type PublicLink = z.infer<typeof publicLinkSchema>;

export const publicLinkInput = z
  .object({
    driveItemId: z.string().min(1).max(128),
    /** Days until the link stops working; null for no end (paid storage only). */
    expiresInDays: publicLinkExpiry.nullable().default(PUBLIC_LINK_DEFAULT_EXPIRY_DAYS),
    /** Visitors must enter this before they see what the link shares. */
    password: z.string().min(4).max(128).nullable().default(null),
    /** Required from end-to-end encrypted accounts: see `publicLinkSchema.e2ee`. */
    e2ee: z
      .object({ secretWrap: z.string().regex(/^[A-Za-z0-9_-]{40,200}$/) })
      .strict()
      .optional(),
  })
  .strict();

/** Changes to a link that works: a new end (counted from now) and/or password; null removes it. */
export const publicLinkUpdate = z
  .object({
    expiresInDays: publicLinkExpiry.nullable().optional(),
    password: z.string().min(4).max(128).nullable().optional(),
  })
  .strict();

/** What the owner may make right now, for the create dialog. */
export const publicLinkAllowanceSchema = z.object({
  /** Null when the account can make links; otherwise why not, ready to show. */
  blockedReason: z.string().nullable(),
  /** Whether links that never expire are offered. */
  neverExpires: z.boolean(),
  active: z.number().int(),
  limit: z.number().int(),
});

/**
 * How a visitor of an end-to-end encrypted link decrypts an item, all base64url: its name
 * sealed with the link's name key, and for a file its content key sealed with the link's file key
 * and the first 20 bytes of its H0E1 header. `keyWrap` and `head` are null for a file stored
 * unencrypted (one an account turning encryption on hasn't converted yet).
 */
export const publicLinkWrapSchema = z.object({
  nameWrap: z.string(),
  keyWrap: z.string().nullable(),
  head: z.string().nullable(),
});
export type PublicLinkWrap = z.infer<typeof publicLinkWrapSchema>;

/** A file or folder as a visitor sees it: no ids of the owner's account, no hashes. */
export const publicItemSchema = z.object({
  id: z.string(),
  type: z.enum(['FILE', 'FOLDER']),
  name: z.string(),
  mimeType: z.string().nullable(),
  sizeBytes: z.number(),
  updatedAt: z.string(),
  /** A program or installer: the page warns before downloading it. */
  executable: z.boolean(),
  /**
   * Encrypted links only, where `name` is empty: how to decrypt it, or null while the owner's
   * app hasn't shared this item's current version yet.
   */
  wrap: publicLinkWrapSchema.nullable().optional(),
});
export type PublicItem = z.infer<typeof publicItemSchema>;

export const publicLinkViewSchema = z.object({
  /** Who shared it, as their account shows it, and since when they've had the account. */
  sharedBy: z.object({ displayName: z.string(), memberSince: z.string() }),
  expiresAt: z.string().nullable(),
  /** The visitor must send the password first; `item` is withheld until then. */
  passwordRequired: z.boolean(),
  /** End-to-end encrypted: names and files are decrypted with the secret in the link. */
  e2ee: z.boolean(),
  item: publicItemSchema.nullable(),
  /** A client room's page: `content` as saved, sealed on an encrypted link. */
  room: z.object({ content: z.string() }).nullable().default(null),
});
export type PublicLinkView = z.infer<typeof publicLinkViewSchema>;

export const publicFolderPageSchema = z.object({
  /** The folder listed, and its folders down from the linked one, for a breadcrumb. */
  path: z.array(
    z.object({
      id: z.string(),
      name: z.string(),
      wrap: publicLinkWrapSchema.nullable().optional(),
    }),
  ),
  items: z.array(publicItemSchema),
  nextCursor: z.string().nullable(),
});
export type PublicFolderPage = z.infer<typeof publicFolderPageSchema>;

/** Media a link page may show inline, by extension, for files stored with a generic type. */
const PREVIEW_TYPES: Record<string, string> = {
  png: 'image/png',
  jpg: 'image/jpeg',
  jpeg: 'image/jpeg',
  gif: 'image/gif',
  webp: 'image/webp',
  avif: 'image/avif',
  bmp: 'image/bmp',
  ico: 'image/x-icon',
  mp4: 'video/mp4',
  m4v: 'video/mp4',
  mov: 'video/quicktime',
  webm: 'video/webm',
  ogv: 'video/ogg',
  mp3: 'audio/mpeg',
  wav: 'audio/wav',
  wave: 'audio/wav',
  ogg: 'audio/ogg',
  oga: 'audio/ogg',
  opus: 'audio/ogg',
  m4a: 'audio/mp4',
  aac: 'audio/aac',
  flac: 'audio/flac',
  weba: 'audio/webm',
  aif: 'audio/aiff',
  aiff: 'audio/aiff',
  pdf: 'application/pdf',
};
const INLINE_TYPE =
  /^(image\/(png|jpeg|gif|webp|avif|bmp|x-icon)|video\/[\w.+-]+|audio\/[\w.+-]+|application\/pdf)$/;
/**
 * The type a preview address is served as. Only images, media and PDF keep theirs; anything
 * else (HTML, SVG, scripts) is served as plain text, so opening the address can't run it.
 */
export function previewContentType(name: string, mimeType: string | null) {
  const mime = mimeType?.split(';')[0].trim().toLowerCase() ?? '';
  if (INLINE_TYPE.test(mime)) return mime;
  const extension = name.toLowerCase().split('.').at(-1) ?? '';
  return PREVIEW_TYPES[extension] ?? 'text/plain; charset=utf-8';
}

export const publicPreviewSchema = z.object({
  url: z.string(),
  expiresAt: z.string(),
  sizeBytes: z.number(),
  contentType: z.string(),
});
export type PublicPreview = z.infer<typeof publicPreviewSchema>;

/** Most folders, and items per folder, one listing request returns: for Download all. */
export const PUBLIC_LISTING_LIMIT = { folders: 25, items: 200 } as const;
/** Most files one request gets download links for. */
export const PUBLIC_DOWNLOADS_LIMIT = 100;

export const publicListingInput = z
  .object({
    /** Folders to list, each from its cursor; no `folderId` is the linked folder. */
    folders: z
      .array(
        z
          .object({
            folderId: z.string().min(1).max(128).optional(),
            cursor: z.string().max(4096).optional(),
          })
          .strict(),
      )
      .min(1)
      .max(PUBLIC_LISTING_LIMIT.folders),
  })
  .strict();
export const publicListingSchema = z.object({
  folders: z.array(
    z.object({
      folderId: z.string(),
      items: z.array(publicItemSchema),
      nextCursor: z.string().nullable(),
    }),
  ),
});
export type PublicListing = z.infer<typeof publicListingSchema>;

export const publicDownloadsInput = z
  .object({ itemIds: z.array(z.string().min(1).max(128)).min(1).max(PUBLIC_DOWNLOADS_LIMIT) })
  .strict();
/** A download link per file, or why that file can't be downloaded; one refusal fails no other. */
export const publicDownloadsSchema = z.object({
  items: z.array(
    z.union([
      z.object({
        itemId: z.string(),
        downloadUrl: z.string(),
        expiresAt: z.string(),
        sizeBytes: z.number(),
      }),
      z.object({
        itemId: z.string(),
        error: z.object({ code: z.string(), message: z.string() }),
      }),
    ]),
  ),
});
export type PublicDownloads = z.infer<typeof publicDownloadsSchema>;

/** An item under an encrypted link as its owner's app sees it, to keep the link's wraps current. */
export const publicLinkScanItemSchema = z.object({
  id: z.string(),
  type: z.enum(['FILE', 'FOLDER']),
  /** The name as stored; the owner's app shows it decrypted, which is what it wraps. */
  name: z.string(),
  /** The item's revision: a wrap is saved only for the revision it was made from. */
  revision: z.number(),
  /** The file's current version; null for folders. */
  versionId: z.string().nullable(),
  /** Whether the link's wrap matches this name and version. */
  current: z.boolean(),
  /** A file stored encrypted: its wrap needs the content key from `keyHeader`. */
  encrypted: z.boolean(),
  /** For a file whose wrap isn't current and whose version is encrypted: its H0E1 header. */
  keyHeader: z.string().nullable(),
});
export const publicLinkScanInput = z
  .object({
    /** Folders under the link to list, each from its cursor; may be empty for just the root. */
    folders: z
      .array(
        z
          .object({
            folderId: z.string().min(1).max(128).optional(),
            cursor: z.string().max(4096).optional(),
          })
          .strict(),
      )
      .max(PUBLIC_LISTING_LIMIT.folders),
  })
  .strict();
export const publicLinkScanSchema = z.object({
  /** The linked item itself. */
  root: publicLinkScanItemSchema,
  folders: z.array(
    z.object({
      folderId: z.string(),
      items: z.array(publicLinkScanItemSchema),
      nextCursor: z.string().nullable(),
    }),
  ),
});
export type PublicLinkScan = z.infer<typeof publicLinkScanSchema>;

const b64 = (max: number) =>
  z
    .string()
    .regex(/^[A-Za-z0-9_-]*$/)
    .max(max);
export const publicLinkWrapsInput = z
  .object({
    wraps: z
      .array(
        z
          .object({
            itemId: z.string().min(1).max(128),
            /** The item's revision this wrap was made from (see the scan). */
            revision: z.number().int(),
            nameWrap: b64(2048).min(1),
            keyWrap: b64(200).nullable(),
            head: b64(40).nullable(),
          })
          .strict(),
      )
      .min(1)
      .max(PUBLIC_DOWNLOADS_LIMIT),
  })
  .strict();
/** Wraps saved, and the items that changed meanwhile (scan them again). */
export const publicLinkWrapsSchema = z.object({ saved: z.number(), stale: z.array(z.string()) });

/** Largest logo a room keeps, as a data URL. */
export const ROOM_LOGO_MAX = 90_000;
/** Largest saved room, sealed or not. */
export const ROOM_CONTENT_MAX = 110_000;
/** Days before a room ends that its owner gets a reminder. */
export const ROOM_REMINDER_DAYS = 3;
/** How long a room's activity is kept. */
export const ROOM_ACTIVITY_DAYS = 90;
/**
 * A client room's page, saved as JSON. An encrypted link's is sealed with the link's name key, so
 * the server keeps it unread; the page checks it the same way either way.
 */
export const roomContentSchema = z
  .object({
    name: z.string().trim().min(1).max(120),
    welcome: z.string().trim().max(2000).default(''),
    /** The accent colour, #rrggbb. */
    color: z
      .string()
      .regex(/^#[0-9a-fA-F]{6}$/)
      .default('#4353d9'),
    logo: z
      .string()
      .max(ROOM_LOGO_MAX)
      .regex(/^data:image\/(png|jpeg|webp);base64,[A-Za-z0-9+/=]+$/)
      .nullable()
      .default(null),
    /** The whole address of a file request for sending files back. */
    sendBackUrl: z.string().max(512).nullable().default(null),
  })
  .strict();
export type RoomContent = z.infer<typeof roomContentSchema>;
export const roomInput = z
  .object({ content: z.string().min(1).max(ROOM_CONTENT_MAX) })
  .strict();
export const roomActivitySchema = z.object({
  items: z.array(
    z.object({
      at: z.string(),
      kind: z.enum(['OPENED', 'DOWNLOADED', 'DOWNLOADED_ALL']),
      /** Stays the same for one visitor of this room, not across rooms. */
      visitor: z.string(),
      /** The browser they used, when it could be told. */
      browser: z.string().nullable(),
      /** The file; null for an opening or a download of several files. */
      item: z.object({ id: z.string(), name: z.string() }).nullable(),
      /** Files in a Download all batch. */
      files: z.number().nullable(),
    }),
  ),
});
export type RoomActivity = z.infer<typeof roomActivitySchema>;

/** Header a visitor sends with the pass a correct password returns. */
export const PUBLIC_LINK_PASS_HEADER = 'X-Harbor-Link-Pass';
