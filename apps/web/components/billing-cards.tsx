'use client';
import { useEffect, useRef, useState } from 'react';
import { CreditCard, Lock, Plus } from 'lucide-react';
import type { ApiClient } from '@harbor/api-client';
import type { PaymentMethod } from '../lib/billing';
import { Alert } from './ui/alert';
import { Badge } from './ui/badge';
import { Button } from './ui/button';
import { Card } from './ui/card';
import { Dialog } from './ui/dialog';
import { Skeleton } from './ui/skeleton';

// The message type Polar's card form posts to the page that embeds it.
const POLAR_MESSAGE = 'POLAR_PAYMENT_METHOD';
const formErrors: Record<string, string> = {
  unauthorized: 'The card form timed out. Open it again.',
  processing_failed: 'The card was declined or couldn’t be confirmed. Try another card.',
};

const brandName = (method: PaymentMethod) => {
  const brand = method.brand ?? 'Card';
  const names: Record<string, string> = {
    visa: 'Visa',
    mastercard: 'Mastercard',
    amex: 'American Express',
    discover: 'Discover',
    jcb: 'JCB',
    diners: 'Diners Club',
    unionpay: 'UnionPay',
  };
  return names[brand] ?? brand.charAt(0).toUpperCase() + brand.slice(1);
};
const expired = (method: PaymentMethod) => {
  if (!method.expYear || !method.expMonth) return false;
  const now = new Date();
  return (
    method.expYear < now.getFullYear() ||
    (method.expYear === now.getFullYear() && method.expMonth < now.getMonth() + 1)
  );
};

/** The shape of Polar's card form while it loads: payment types, fields and the save button. */
function CardFormSkeleton() {
  return (
    <div className="card-form-skeleton" role="status" aria-label="Loading the card form">
      <span className="sr-only">Loading the secure card form…</span>
      <div className="card-form-skeleton-row">
        <Skeleton className="skeleton-tile" />
        <Skeleton className="skeleton-tile" />
      </div>
      <Skeleton className="skeleton-label" />
      <Skeleton className="skeleton-input" />
      <div className="card-form-skeleton-row">
        <div>
          <Skeleton className="skeleton-label" />
          <Skeleton className="skeleton-input" />
        </div>
        <div>
          <Skeleton className="skeleton-label" />
          <Skeleton className="skeleton-input" />
        </div>
      </div>
      <Skeleton className="skeleton-label" />
      <Skeleton className="skeleton-input" />
      <Skeleton className="skeleton-text" />
      <Skeleton className="skeleton-text" data-short />
      <Skeleton className="skeleton-button" />
    </div>
  );
}

type FormSession = { url: string; origin: string };

/**
 * Polar's card form in an iframe, in a dialog. The card number goes to Polar (and Stripe), never
 * to harbor0; the form posts `loaded`, `resize`, `confirmed`, `success` and `error` messages back.
 * It stays hidden behind a skeleton until it has loaded and reported its height.
 */
function AddCardDialog({
  api,
  open,
  onClose,
  onAdded,
}: {
  api: ApiClient;
  open: boolean;
  onClose: () => void;
  onAdded: () => void;
}) {
  const frame = useRef<HTMLIFrameElement>(null);
  const [session, setSession] = useState<FormSession>();
  const [attempt, setAttempt] = useState(0);
  const [height, setHeight] = useState(0);
  const [loaded, setLoaded] = useState(false);
  const [confirming, setConfirming] = useState(false);
  const [error, setError] = useState('');
  const ready = loaded && height > 0;

  // A fresh one-hour session each time the dialog opens or the person retries.
  useEffect(() => {
    if (!open) return;
    let current = true;
    setSession(undefined);
    setLoaded(false);
    setHeight(0);
    setConfirming(false);
    setError('');
    api
      .request('/v1/billing/cards/form', { method: 'POST', body: {} })
      .then((next: FormSession) => current && setSession(next))
      .catch((e: Error) => current && setError(e.message));
    return () => {
      current = false;
    };
  }, [open, attempt]);

  // Polar refuses to be framed on a host its settings don't list, and the browser only says so
  // in the console; without `loaded` the form would never appear.
  useEffect(() => {
    if (!session || loaded) return;
    const timer = setTimeout(
      () =>
        setError(
          'The card form didn’t load. Try again, or contact support if this keeps happening.',
        ),
      15_000,
    );
    return () => clearTimeout(timer);
  }, [session, loaded]);

  useEffect(() => {
    if (!session) return;
    const listener = (event: MessageEvent) => {
      if (event.origin !== session.origin || event.source !== frame.current?.contentWindow) return;
      const message = event.data as {
        type?: string;
        event?: string;
        height?: number;
        code?: string;
      };
      if (message?.type !== POLAR_MESSAGE) return;
      if (message.event === 'loaded') {
        setLoaded(true);
        setError('');
      } else if (message.event === 'resize' && typeof message.height === 'number')
        setHeight(Math.max(0, Math.ceil(message.height)));
      else if (message.event === 'confirmed') setConfirming(true);
      else if (message.event === 'success') onAdded();
      else if (message.event === 'error') {
        setConfirming(false);
        setError(formErrors[message.code ?? ''] ?? 'The card couldn’t be added. Try again.');
      }
    };
    window.addEventListener('message', listener);
    return () => window.removeEventListener('message', listener);
  }, [session, onAdded]);

  const src = (() => {
    if (!session) return undefined;
    const url = new URL(session.url);
    url.searchParams.set('embed', 'true');
    url.searchParams.set('mode', 'inline');
    url.searchParams.set('embed_origin', window.location.origin);
    if (document.documentElement.classList.contains('dark')) url.searchParams.set('theme', 'dark');
    return url.toString();
  })();
  // A form that never loaded can be retried; a declined card can be corrected in place.
  const failedToLoad = !!error && !loaded;

  return (
    <Dialog
      open={open}
      // While the card is being confirmed with the bank, closing would lose the result.
      onOpenChange={(value) => !value && !confirming && onClose()}
      title="Add a card"
      description="It becomes your default card, used for renewals and upgrades."
      className="add-card-dialog"
    >
      <div className="card-form">
        {error && (
          <Alert
            tone="error"
            action={
              failedToLoad && (
                <Button size="sm" variant="outline" onClick={() => setAttempt((n) => n + 1)}>
                  Try again
                </Button>
              )
            }
          >
            {error}
          </Alert>
        )}
        {!failedToLoad && (
          // Padded panel in Polar's own background colour, so the form sits inset, not edge to edge.
          <div className="card-form-frame">
            {!ready && <CardFormSkeleton />}
            {src && (
              <iframe
                ref={frame}
                key={src}
                title="Card details"
                src={src}
                data-ready={ready || undefined}
                style={ready ? { height } : undefined}
                allow={`payment 'self' ${session!.origin}; publickey-credentials-get 'self' ${session!.origin}`}
              />
            )}
          </div>
        )}
        <p className="muted card-form-note">
          <Lock aria-hidden="true" />
          Card details go straight to Polar, our payment provider. harbor0 never sees them.
        </p>
      </div>
    </Dialog>
  );
}

/** Saved cards: add one, choose which is charged, remove the rest. */
export function PaymentMethods({
  api,
  methods,
  onChanged,
}: {
  api: ApiClient;
  methods: PaymentMethod[];
  onChanged: (message: string) => void;
}) {
  const [adding, setAdding] = useState(false);
  const [busy, setBusy] = useState<string>();
  const [error, setError] = useState('');
  const run = async (key: string, work: () => Promise<unknown>, message: string) => {
    setBusy(key);
    setError('');
    try {
      await work();
      onChanged(message);
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(undefined);
    }
  };
  return (
    <Card
      className="panel"
      title="Payment methods"
      description="The default card pays for renewals and upgrades."
      action={
        <Button variant="outline" onClick={() => setAdding(true)} disabled={!!busy}>
          <Plus aria-hidden="true" />
          Add card
        </Button>
      }
    >
      {error && <Alert tone="error">{error}</Alert>}
      {methods.length === 0 && <p className="muted">No saved cards.</p>}
      {methods.length > 0 && (
        <ul className="card-list">
          {methods.map((method) => (
            <li key={method.id} className="list-row">
              <CreditCard aria-hidden="true" />
              <div className="list-row-text">
                <strong>
                  {brandName(method)} ending {method.last4 ?? '••••'}
                  {method.isDefault && <Badge tone="accent">Default</Badge>}
                  {expired(method) && <Badge tone="danger">Expired</Badge>}
                </strong>
                <small className="muted">
                  {method.expMonth && method.expYear
                    ? `Expires ${String(method.expMonth).padStart(2, '0')}/${method.expYear}`
                    : 'Saved card'}
                  {method.wallet && ` · ${method.wallet.replace(/_/g, ' ')}`}
                </small>
              </div>
              {!method.isDefault && (
                <Button
                  size="sm"
                  variant="outline"
                  disabled={!!busy}
                  onClick={() =>
                    void run(
                      `default-${method.id}`,
                      () =>
                        api.request(`/v1/billing/cards/${method.id}/default`, {
                          method: 'POST',
                          body: {},
                        }),
                      `${brandName(method)} ending ${method.last4} is now your default card.`,
                    )
                  }
                >
                  {busy === `default-${method.id}` ? 'Saving…' : 'Make default'}
                </Button>
              )}
              <Button
                size="sm"
                variant="ghost"
                disabled={!!busy}
                onClick={() =>
                  void run(
                    `remove-${method.id}`,
                    () => api.request(`/v1/billing/cards/${method.id}`, { method: 'DELETE' }),
                    `${brandName(method)} ending ${method.last4} was removed.`,
                  )
                }
              >
                {busy === `remove-${method.id}` ? 'Removing…' : 'Remove'}
              </Button>
            </li>
          ))}
        </ul>
      )}
      <AddCardDialog
        api={api}
        open={adding}
        onClose={() => setAdding(false)}
        onAdded={() => {
          setAdding(false);
          onChanged('Card added. It’s now your default card.');
        }}
      />
    </Card>
  );
}
