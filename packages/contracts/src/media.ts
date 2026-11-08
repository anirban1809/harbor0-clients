/** Photo formats by extension, for files stored with a generic MIME type (RAW, HEIC, …). */
const photoExtensions = new Set(
  'jpg jpeg jpe jfif png gif webp avif bmp tif tiff heic heif jxl svg ico dng cr2 cr3 crw nef nrw arw srf sr2 orf rw2 raf pef srw raw rwl 3fr erf kdc mrw mos x3f iiq'.split(
    ' ',
  ),
);
const videoExtensions = new Set(
  'mp4 m4v mov qt avi mkv webm wmv asf flv f4v mpg mpeg mpe m2v m2ts mts ts 3gp 3g2 ogv vob mxf dv insv lrv'.split(
    ' ',
  ),
);
export type MediaKind = 'photo' | 'video';
const generic = (mime: string) =>
  !mime || mime === 'application/octet-stream' || mime === 'binary/octet-stream';

/** Whether a file is a photo or a video, by its MIME type or else its extension. */
export function mediaKind(item: {
  type: string;
  name: string;
  mimeType?: string | null;
}): MediaKind | null {
  if (item.type !== 'FILE') return null;
  const mime = item.mimeType?.split(';')[0].trim().toLowerCase() ?? '';
  if (mime.startsWith('image/')) return 'photo';
  if (mime.startsWith('video/')) return 'video';
  if (!generic(mime)) return null;
  const extension = item.name.toLowerCase().split('.').at(-1) ?? '';
  return photoExtensions.has(extension) ? 'photo' : videoExtensions.has(extension) ? 'video' : null;
}

/**
 * Whether the server should list a file on the Photos page: a photo or video, or an end-to-end
 * encrypted file whose type it can't see (the app checks the decrypted name).
 */
export const maybeMedia = (item: {
  type: string;
  name: string;
  mimeType?: string | null;
  encrypted?: boolean;
}) =>
  !!mediaKind(item) ||
  (item.type === 'FILE' && !!item.encrypted && generic(item.mimeType?.toLowerCase() ?? ''));
/** Most files a single request may ask for preview links to. */
export const MAX_PHOTO_LINKS = 60;
/** Most thumbnails one request may save (POST /v1/photos/thumbnails); the body stays under 1 MB. */
export const MAX_PHOTO_THUMBNAILS = 16;
/**
 * The thumbnails apps make for the Photos page: a JPEG this many pixels on its short side (or
 * the photo's own size, if smaller), at most 3 times as long on the other.
 */
export const PHOTO_THUMBNAIL_PX = 360;
export const PHOTO_THUMBNAIL_QUALITY = 0.72;
