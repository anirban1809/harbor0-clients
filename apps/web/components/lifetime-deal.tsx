'use client';
import { useEffect, useRef, useState } from 'react';
import { usePathname, useRouter, useSearchParams } from 'next/navigation';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { Infinity as Lifetime } from 'lucide-react';
import type { ApiClient } from '@harbor/api-client';
import { day, lifetimeSize, money, type LifetimeSeat } from '../lib/billing';
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
  /** The 1–5 TB seats are open to this account (`lifetime-deal`). */
  seatsOpen: boolean;
  /** 500 GB for $50, offered only to accounts staff added because they asked (`lifetime-500`). */
  invited: { terabytes: number; storageBytes: number; amount: number } | null;
  checkoutAvailable: boolean;
};
type LifetimePrice = LifetimeOffer['prices'][number];
/** The sizes this account can buy: the 500 GB plan when invited, then the seats while open. */
const choices = (offer: LifetimeOffer): LifetimePrice[] => [
  ...(offer.invited ? [offer.invited] : []),
  ...(offer.seatsOpen && !offer.soldOut ? offer.prices : []),
];
const kind = (terabytes: number) => (terabytes < 1 ? 'lifetime storage' : 'lifetime seat');

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
 * Accounts on the `lifetime-500` flag are also offered 500 GB for $50. Polar sends people back
 * with `?lifetime=`, and the purchase is confirmed then.
 */
export function LifetimeCard({ api }: { api: ApiClient }) {
  const seatsOn = useFeatureFlag('lifetime-deal');
  const invitedOn = useFeatureFlag('lifetime-500');
  const enabled = seatsOn || invitedOn;
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
            ? `Thanks! Your ${lifetimeSize(terabytes)} of ${kind(terabytes)} is active.`
            : 'Your payment is still processing. Your lifetime storage appears here in a minute or two.',
        );
        void cache.invalidateQueries({ queryKey: ['billing'] });
        void cache.invalidateQueries({ queryKey: ['me'] });
      })
      .catch((e: Error) => setNotice(e.message));
  }, [enabled, searchParams]);

  if (!enabled || !offer.data) return null;
  const owned = offer.data.owned && !offer.data.owned.refundedAt ? offer.data.owned : undefined;
  const options = choices(offer.data);
  const from = options[0];
  // Invited with seats closed, or seats closed and nothing bought: nothing to show.
  if (!owned && !offer.data.seatsOpen && !offer.data.invited) return null;
  return (
    <Card
      className="panel lifetime-card"
      title="Lifetime storage"
      description={
        owned
          ? owned.terabytes < 1
            ? `You have ${lifetimeSize(owned.terabytes)} of lifetime storage, bought ${day(owned.purchasedAt)}.`
            : `You have a lifetime seat: ${lifetimeSize(owned.terabytes)}, bought ${day(owned.purchasedAt)}.`
          : !from
            ? 'Lifetime seats are sold out.'
            : `Pay once and keep the storage for as long as harbor0 runs. ${options.length > 1 ? 'From' : 'It’s'} ${money(from.amount, offer.data.currency)} for ${lifetimeSize(from.terabytes)}.${offer.data.seatsOpen ? ` ${seatsLine(offer.data)}` : ''}`
      }
      action={
        owned ? (
          <Badge tone="success">Active</Badge>
        ) : (
          from &&
          offer.data.checkoutAvailable && (
            <Button variant="outline" onClick={() => setOpen(true)}>
              <Lifetime aria-hidden="true" />
              {offer.data.seatsOpen ? 'See lifetime seats' : 'See lifetime storage'}
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
  const options = choices(offer);
  const [terabytes, setTerabytes] = useState(options[0].terabytes);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const chosen = options.find((p) => p.terabytes === terabytes)!;
  const extra = offer.prices[1] ? offer.prices[1].amount - offer.prices[0].amount : 0;
  // The 500 GB plan holds no seat, so seats in other checkouts don't block it.
  const held = offer.allHeld && chosen.terabytes >= 1;
  const seatsOpen = offer.seatsOpen && !offer.soldOut;

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
      description={`One payment, every feature, for as long as harbor0 runs. ${[
        offer.invited &&
          `${money(offer.invited.amount, offer.currency)} for ${lifetimeSize(offer.invited.terabytes)}.`,
        seatsOpen &&
          `${money(offer.prices[0].amount, offer.currency)} for 1 TB, then ${money(extra, offer.currency)} for each extra TB, up to ${offer.maxTerabytes} TB. ${seatsLine(offer)}`,
      ]
        .filter(Boolean)
        .join(' ')}`}
    >
      <div className="form">
        {error && <Alert tone="error">{error}</Alert>}
        {seatsOpen && offer.allHeld && (
          <Alert role="none">
            The last seats are in other people’s checkouts. If they don’t finish, the seats open
            again within 30 minutes.
          </Alert>
        )}
        <fieldset className="choice-list plan-choices">
          <legend className="sr-only">Storage</legend>
          {options.map((price) => (
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
                  {lifetimeSize(price.terabytes)} · {money(price.amount, offer.currency)}
                </strong>
                <span className="muted">One payment</span>
              </span>
            </label>
          ))}
        </fieldset>
        <ul className="muted lifetime-terms">
          <li>One lifetime purchase per account. It can’t be moved to another account.</li>
          <li>The storage adds to any plan you have, now or later.</li>
          <li>Version history and trash count toward it, as on every plan.</li>
          <li>Discount codes don’t apply. Sales tax or VAT may be added at checkout.</li>
        </ul>
        <DialogActions>
          <Button type="button" variant="outline" disabled={busy} onClick={onClose}>
            Close
          </Button>
          <Button type="button" disabled={busy || held} onClick={() => void checkout()}>
            {busy
              ? 'Opening checkout…'
              : `Buy ${lifetimeSize(chosen.terabytes)} for ${money(chosen.amount, offer.currency)}`}
          </Button>
        </DialogActions>
      </div>
    </Dialog>
  );
}

/** Settings → Billing: the account's lifetime seat or 500 GB plan, when it has one. */
export function LifetimeSeatCard({ seat }: { seat: LifetimeSeat }) {
  return (
    <Card
      className="panel billing-plan"
      title={`Lifetime ${lifetimeSize(seat.terabytes)}`}
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
        <dt>Storage</dt>
        <dd>{lifetimeSize(seat.terabytes)}</dd>
        <dt>Bought</dt>
        <dd>{day(seat.purchasedAt)}</dd>
      </dl>
    </Card>
  );
}
