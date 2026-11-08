import { z } from 'zod';

const existingRoutes =
  /^\/v1\/(users|drive|uploads|downloads|transfers|shares|search|devices|notifications|billing|backups|photos|links)(\/|\?|$)/;
const syncShareRoute = /^\/v1\/sync\/shares(?:\/[^/?]+\/(?:respond|status))?(?:\?.*)?$/;
const syncFolderRoute = /^\/v1\/sync\/(folders|status)(?:\?.*)?$/;

// The same storage hosts the window's Content-Security-Policy may show (see build.ts).
const storageLink = (link: string) => {
  try {
    const url = new URL(link);
    return (
      (url.protocol === 'https:' && url.hostname.endsWith('.r2.cloudflarestorage.com')) ||
      url.origin === 'http://127.0.0.1:9100'
    );
  } catch {
    return false;
  }
};
export const linkReadSchema = z
  .object({
    url: z.string().max(4096).refine(storageLink, 'Not a storage link.'),
    /** Stored end-to-end encrypted: decrypt it with the account's keys. */
    open: z.boolean().optional(),
  })
  .strict();
// A thumbnail to encrypt; photos-page.tsx keeps those it saves under this size.
export const thumbnailSealSchema = z
  .object({
    bytes: z.instanceof(Uint8Array).refine((bytes) => bytes.length <= 256 * 1024),
  })
  .strict();

export const rendererRequestSchema = z
  .object({
    path: z
      .string()
      .max(4096)
      .refine(
        (path) =>
          (existingRoutes.test(path) || syncFolderRoute.test(path) || syncShareRoute.test(path)) &&
          !path.includes('..') &&
          !path.includes('\\') &&
          !path.includes('#'),
        'Invalid API path.',
      ),
    method: z.enum(['GET', 'POST', 'PATCH', 'PUT', 'DELETE']).default('GET'),
    body: z.unknown().optional(),
  })
  .strict()
  .refine(
    (input) => !syncFolderRoute.test(input.path) || input.method === 'GET',
    'Synced folders can only be read from the file browser.',
  );
