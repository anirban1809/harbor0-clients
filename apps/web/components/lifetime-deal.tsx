'use client';
import { useEffect, useRef, useState } from 'react';
import { usePathname, useRouter, useSearchParams } from 'next/navigation';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { Infinity as Lifetime } from 'lucide-react';
import type { ApiClient } from '@harbor/api-client';
import { day, money, type LifetimeSeat } from '../lib/billing';
import { useFeatureFlag } from '../lib/feature-flags';
import { Alert } from './ui/alert';
import { Badge } from './ui/badge';
import { Button } from './ui/button';
import { Card } from './ui/card';
import { Dialog, DialogActions } from './ui/dialog';

/** What `GET /v1/billing/lifetime` returns; amounts are in cents. */
type LifetimeOffer = {
  seats: number;
  /** Null until enough seats have sold for the count to be shown. */
  seatsLeft: number | null;
  soldOut: boolean;
  /** Every seat left is in someone's checkout for now. */
  allHeld: boolean;
  maxTerabytes: number;
  prices: { terabytes: number; storageBytes: number; amount: number }[];
  currency: string;
  owned: LifetimeSeat | null;
  checkoutAvailable: boolean;
};

export function useLifetimeOffer(api: ApiClient, enabled = true) {
  return useQuery({
    queryKey: ['billing', 'lifetime'],
    queryFn: () => api.request('/v1/billing/lifetime') as Promise<LifetimeOffer>,
    enabled,
  });
}

const seatsLine = (offer: LifetimeOffer) =>
  offer.seatsLeft === null
    ? `${offer.seats} lifetime seats in this sale.`
    : `${offer.seatsLeft} of ${offer.seats} seats left.`;

/**
 * Lifetime seats on the Storage page, behind the `lifetime-deal` flag: one payment for 1–5 TB.
 * Polar sends people back with `?lifetime=`, and the seat is confirmed then.
 */
export function LifetimeCard({ api }: { api: ApiClient }) {
  const enabled = useFeatureFlag('lifetime-deal');
  const cache = useQueryClient();
  const router = useRouter();
  const pathname = usePathname();
  const searchParams = useSearchParams();
  const offer = useLifetimeOffer(api, enabled);
  const [open, setOpen] = useState(false);
  const [notice, setNotice] = useState('');
  const started = useRef(false);

  // Back from checkout: the webhook may not have arrived yet, so ask for the seat now.
  useEffect(() => {
    if (!enabled || !searchParams.get('lifetime') || started.current) return;
    started.current = true;
    router.replace(pathname);
    setNotice('Confirming your payment…');
    void api
      .request('/v1/billing/lifetime/sync', { method: 'POST', body: {} })
      .then(({ terabytes }: { terabytes: number | null }) => {
        setNotice(
          terabytes
            ? `Thanks! Your ${terabytes} TB lifetime seat is active.`
            : 'Your payment is still processing. Your lifetime storage appears here in a minute or two.',
        );
        void cache.invalidateQueries({ queryKey: ['billing'] });
        void cache.invalidateQueries({ queryKey: ['me'] });
      })
      .catch((e: Error) => setNotice(e.message));
  }, [enabled, searchParams]);

  if (!enabled || !offer.data) return null;
  const owned = offer.data.owned && !offer.data.owned.refundedAt ? offer.data.owned : undefined;
  const from = offer.data.prices[0];
  return (
    <Card
      className="panel lifetime-card"
      title="Lifetime storage"
      description={
        owned
          ? `You have lifetime seat #${owned.seat}: ${owned.terabytes} TB, bought ${day(owned.purchasedAt)}.`
          : offer.data.soldOut
            ? 'Lifetime seats are sold out.'
            : `Pay once and keep the storage for as long as harbor0 runs. From ${money(from.amount, offer.data.currency)} for ${from.terabytes} TB. ${seatsLine(offer.data)}`
      }
      action={
        owned ? (
          <Badge tone="success">Seat #{owned.seat}</Badge>
        ) : (
          !offer.data.soldOut &&
          offer.data.checkoutAvailable && (
            <Button variant="outline" onClick={() => setOpen(true)}>
              <Lifetime aria-hidden="true" />
              See lifetime seats
            </Button>
          )
        )
      }
    >
      {notice && (
        <Alert tone="success" onDismiss={() => setNotice('')}>
          {notice}
        </Alert>
      )}
      {open && <LifetimeDialog api={api} offer={offer.data} onClose={() => setOpen(false)} />}
    </Card>
  );
}

/** Pick a size and go to Polar's payment page; the size can't change there. */
function LifetimeDialog({
  api,
  offer,
  onClose,
}: {
  api: ApiClient;
  offer: LifetimeOffer;
  onClose: () => void;
}) {
  const [terabytes, setTerabytes] = useState(1);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const chosen = offer.prices.find((p) => p.terabytes === terabytes)!;
  const extra = offer.prices[1] ? offer.prices[1].amount - offer.prices[0].amount : 0;

  const checkout = async () => {
    setBusy(true);
    setError('');
    try {
      const { checkoutUrl } = await api.request('/v1/billing/lifetime/checkout', {
        method: 'POST',
        body: { terabytes },
      });
      window.location.assign(checkoutUrl);
    } catch (e) {
      setError((e as Error).message);
      setBusy(false);
    }
  };

  return (
    <Dialog
      open
      onOpenChange={(value) => !value && !busy && onClose()}
      title="Lifetime storage"
      description={`One payment, every feature, for as long as harbor0 runs. ${money(offer.prices[0].amount, offer.currency)} for 1 TB, then ${money(extra, offer.currency)} for each extra TB, up to ${offer.maxTerabytes} TB. ${seatsLine(offer)}`}
    >
      <div className="form">
        {error && <Alert tone="error">{error}</Alert>}
        {offer.allHeld && (
          <Alert role="none">
            The last seats are in other people’s checkouts. If they don’t finish, the seats open
            again within 30 minutes.
          </Alert>
        )}
        <fieldset className="choice-list plan-choices">
          <legend className="sr-only">Storage</legend>
          {offer.prices.map((price) => (
            <label className="choice" key={price.terabytes}>
              <input
                type="radio"
                className="radio"
                name="lifetime-size"
                value={price.terabytes}
                disabled={busy}
                checked={terabytes === price.terabytes}
                onChange={() => setTerabytes(price.terabytes)}
              />
              <span>
                <strong>
                  {price.terabytes} TB · {money(price.amount, offer.currency)}
                </strong>
                <span className="muted">One payment</span>
              </span>
            </label>
          ))}
        </fieldset>
        <ul className="muted lifetime-terms">
          <li>One seat per account. It can’t be moved to another account.</li>
          <li>The storage adds to any plan you have, now or later.</li>
          <li>Version history and trash count toward it, as on every plan.</li>
          <li>Discount codes don’t apply. Sales tax or VAT may be added at checkout.</li>
        </ul>
        <DialogActions>
          <Button type="button" variant="outline" disabled={busy} onClick={onClose}>
            Close
          </Button>
          <Button type="button" disabled={busy || offer.allHeld} onClick={() => void checkout()}>
            {busy
              ? 'Opening checkout…'
              : `Buy ${chosen.terabytes} TB for ${money(chosen.amount, offer.currency)}`}
          </Button>
        </DialogActions>
      </div>
    </Dialog>
  );
}

/** Settings → Billing: the account's lifetime seat, when it has one. */
export function LifetimeSeatCard({ seat }: { seat: LifetimeSeat }) {
  return (
    <Card
      className="panel billing-plan"
      title={`Lifetime ${seat.terabytes} TB`}
      description={
        seat.refundedAt
          ? `Refunded on ${day(seat.refundedAt)}. Its storage was removed from your account.`
          : 'Paid once. The storage stays for as long as harbor0 runs, and adds to any plan.'
      }
      action={
        <Badge tone={seat.refundedAt ? 'neutral' : 'success'}>
          {seat.refundedAt ? 'Refunded' : 'Active'}
        </Badge>
      }
    >
      <dl className="details">
        <dt>Seat</dt>
        <dd>#{seat.seat}</dd>
        <dt>Storage</dt>
        <dd>{seat.terabytes} TB</dd>
        <dt>Bought</dt>
        <dd>{day(seat.purchasedAt)}</dd>
      </dl>
    </Card>
  );
}
