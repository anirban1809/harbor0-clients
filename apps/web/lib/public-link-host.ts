import { createContext, useContext, useMemo } from 'react';
import type { Transport } from '@harbor/api-client';
import type { PublicLink } from '@harbor/contracts';
import { newEncryptedLink, shareableUrl, syncLinkWraps, type WrapProgress } from './e2ee-links';
import { useVault } from './e2ee-vault';

/**
 * Where a public link's encryption runs and how its address is copied. The web keeps the account's
 * keys in this tab; the desktop app keeps them in its main process and provides its own host.
 */
export type PublicLinkHost = {
  /** New links are encrypted: the account is encrypted, unlocked and not turning off. */
  encrypted: boolean;
  /** The account's keys are at hand, so encrypted links' addresses can be shown. */
  unlocked: boolean;
  /** A new encrypted link's secret, wrapped for the server to keep. */
  newSecret: () => Promise<{ secretWrap: string }>;
  /** The address to share; an encrypted link's carries its secret. */
  url: (link: PublicLink) => Promise<string | null>;
  /** Wraps whatever under an encrypted link isn't wrapped for it yet. */
  wrap: (
    request: Transport,
    link: PublicLink,
    onProgress?: (progress: WrapProgress) => void,
  ) => Promise<WrapProgress>;
  /** Copies text where the page's clipboard can't; false when refused. */
  copy?: (text: string) => Promise<boolean>;
};

export const PublicLinkHostContext = createContext<PublicLinkHost | null>(null);

/** The provided host, or this tab's own vault. */
export function usePublicLinkHost(): PublicLinkHost {
  const provided = useContext(PublicLinkHostContext);
  const { enabled, decrypting, keys } = useVault();
  const own = useMemo<PublicLinkHost>(
    () => ({
      encrypted: enabled && !decrypting,
      unlocked: !!keys,
      newSecret: async () => (await newEncryptedLink(keys!)).e2ee,
      url: (link) => shareableUrl(link, keys),
      wrap: (request, link, onProgress) => syncLinkWraps(request, keys!, link, onProgress),
    }),
    [enabled, decrypting, keys],
  );
  return provided ?? own;
}
