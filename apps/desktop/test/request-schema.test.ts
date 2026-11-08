import { describe, expect, it } from 'vitest';
import { linkReadSchema, rendererRequestSchema } from '../src/request-schema';

describe('renderer request permissions', () => {
  it('allows My Drive to read synced folders with the default GET method', () => {
    expect(rendererRequestSchema.parse({ path: '/v1/sync/folders' })).toEqual({
      path: '/v1/sync/folders',
      method: 'GET',
    });
    expect(
      rendererRequestSchema.safeParse({ path: '/v1/sync/folders?cursor=next', method: 'GET' })
        .success,
    ).toBe(true);
    expect(rendererRequestSchema.safeParse({ path: '/v1/sync/status?ids=file' }).success).toBe(
      true,
    );
    expect(
      rendererRequestSchema.safeParse({ path: '/v1/sync/status?ids=file', method: 'POST' }).success,
    ).toBe(false);
    expect(
      rendererRequestSchema.safeParse({ path: '/v1/drive/folders/root/children' }).success,
    ).toBe(true);
    expect(
      rendererRequestSchema.safeParse({
        path: '/v1/drive/items/file',
        method: 'PATCH',
        body: { name: 'Notes' },
      }).success,
    ).toBe(true);
  });
  it('lets the shared Photos and public link pages reach their routes', () => {
    for (const path of ['/v1/photos?limit=300', '/v1/links', '/v1/links/allowance'])
      expect(rendererRequestSchema.safeParse({ path }).success).toBe(true);
    for (const path of ['/v1/photos/links', '/v1/photos/thumbnails', '/v1/links'])
      expect(rendererRequestSchema.safeParse({ path, method: 'POST' }).success).toBe(true);
  });
  it('reads links from storage hosts only', () => {
    expect(
      linkReadSchema.safeParse({ url: 'https://abc.r2.cloudflarestorage.com/bucket/key?sig=1' })
        .success,
    ).toBe(true);
    for (const url of [
      'http://abc.r2.cloudflarestorage.com/key',
      'https://evil.test/.r2.cloudflarestorage.com/key',
      'https://r2.cloudflarestorage.com.evil.test/key',
      'http://127.0.0.1:3000/v1/users/me',
      'file:///etc/passwd',
    ])
      expect(linkReadSchema.safeParse({ url }).success).toBe(false);
  });
  it('does not grant the renderer sync mutations or unrelated endpoints', () => {
    for (const method of ['POST', 'PUT', 'PATCH', 'DELETE'])
      expect(rendererRequestSchema.safeParse({ path: '/v1/sync/folders', method }).success).toBe(
        false,
      );
    for (const path of [
      '/v1/sync/operations',
      '/v1/sync/items/file/acknowledge',
      '/v1/sync/items/file/request-content',
      '/v1/sync/checkpoints',
      '/v1/sync/changes',
      '/v1/sync/folders/child',
      '/v1/auth/session',
      '/v1/drive/../sync/operations',
      '/v1/drive/items#x',
      'https://example.test/v1/drive',
    ])
      expect(rendererRequestSchema.safeParse({ path }).success).toBe(false);
  });
});
