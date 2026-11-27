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
import { Field } from './ui/field';
import { Input } from './ui/input';

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
  /**
   * More TB for the account's seat at one price a TB, up to `maxTerabytes` more (10 TB of lifetime
   * storage in all, so 0 once the seat holds that); null without a seat.
   */
  addOn: { amountPerTerabyte: number; maxTerabytes: number } | null;
  owned: LifetimeSeat | null;
  /** The 1–5 TB seats are open to this account (`lifetime-deal`). */
  seatsOpen: boolean;
  /** 500 GB for $50, offered only to accounts staff added because they asked (`lifetime-500`). */
  invited: { terabytes: number; storageBytes: number; amount: number } | null;
  checkoutAvailable: boolean;
};
type LifetimePrice = { terabytes: number; storageBytes: number; amount: number };
const ownedSeat = (offer: LifetimeOffer) =>
  offer.owned && !offer.owned.refundedAt ? offer.owned : undefined;
/**
 * What this account can buy: with nothing yet, the 500 GB plan when invited and the seats while
 * open. With stacking on (`ltd-stacking`), more TB for its seat, or a seat on top of its 500 GB.
 */
const choices = (offer: LifetimeOffer, stacking: boolean): LifetimePrice[] => {
  const owned = ownedSeat(offer);
  if (owned && !stacking) return [];
  // Up to the TB left to add: the dialog asks how many, starting from 1.
  if (owned?.seat)
    return offer.addOn && offer.addOn.maxTerabytes >= 1
      ? [{ terabytes: 1, storageBytes: 1e12, amount: offer.addOn.amountPerTerabyte }]
      : [];
  const seats = offer.seatsOpen && !offer.soldOut ? offer.prices : [];
  return owned ? seats : [...(offer.invited ? [offer.invited] : []), ...seats];
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
 * Accounts on the `lifetime-500` flag are also offered 500 GB for $50. Polar sends people back
 * with `?lifetime=`, and the purchase is confirmed then.
 */
export function LifetimeCard({ api }: { api: ApiClient }) {
  const seatsOn = useFeatureFlag('lifetime-deal');
  const invitedOn = useFeatureFlag('lifetime-500');
  const stacking = useFeatureFlag('ltd-stacking');
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
            ? `Thanks! You now have ${lifetimeSize(terabytes)} of lifetime storage.`
            : 'Your payment is still processing. Your lifetime storage appears here in a minute or two.',
        );
        void cache.invalidateQueries({ queryKey: ['billing'] });
        void cache.invalidateQueries({ queryKey: ['me'] });
      })
      .catch((e: Error) => setNotice(e.message));
  }, [enabled, searchParams]);

  if (!enabled || !offer.data) return null;
  const owned = ownedSeat(offer.data);
  const options = choices(offer.data, stacking);
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
        from && offer.data.checkoutAvailable ? (
          <Button variant="outline" onClick={() => setOpen(true)}>
            <Lifetime aria-hidden="true" />
            {owned
              ? 'Add storage'
              : offer.data.seatsOpen
                ? 'See lifetime seats'
                : 'See lifetime storage'}
          </Button>
        ) : (
          owned && <Badge tone="success">Active</Badge>
        )
      }
    >
      {notice && (
        <Alert tone="success" onDismiss={() => setNotice('')}>
          {notice}
        </Alert>
      )}
      {open && (
        <LifetimeDialog
          api={api}
          offer={offer.data}
          options={options}
          onClose={() => setOpen(false)}
        />
      )}
    </Card>
  );
}

/** Pick a size and go to Polar's payment page; the size can't change there. */
function LifetimeDialog({
  api,
  offer,
  options,
  onClose,
}: {
  api: ApiClient;
  offer: LifetimeOffer;
  /** What the account can buy (`choices`); never empty. */
  options: LifetimePrice[];
  onClose: () => void;
}) {
  const owned = ownedSeat(offer);
  // More TB for an existing seat: no seat to hold, one price a TB.
  const addingOn = !!owned?.seat;
  const [terabytes, setTerabytes] = useState(options[0].terabytes);
  // What's typed in the add-on's TB box, which may be empty or out of range while typing.
  const [typed, setTyped] = useState('1');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const most = offer.addOn?.maxTerabytes ?? 0;
  const count = Number(typed);
  const valid = !addingOn || (Number.isInteger(count) && count >= 1 && count <= most);
  const chosen = addingOn
    ? { terabytes: valid ? count : 1, amount: (valid ? count : 1) * offer.addOn!.amountPerTerabyte }
    : options.find((p) => p.terabytes === terabytes)!;
  const extra = offer.prices[1] ? offer.prices[1].amount - offer.prices[0].amount : 0;
  // The 500 GB plan holds no seat, so seats in other checkouts don't block it.
  const held = !addingOn && offer.allHeld && chosen.terabytes >= 1;
  const seatsOpen = !addingOn && offer.seatsOpen && !offer.soldOut;

  const checkout = async () => {
    setBusy(true);
    setError('');
    try {
      const { checkoutUrl } = await api.request('/v1/billing/lifetime/checkout', {
        method: 'POST',
        body: { terabytes: chosen.terabytes },
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
      title={addingOn ? 'Add lifetime storage' : 'Lifetime storage'}
      description={
        addingOn
          ? `Add up to ${most} TB more to your ${lifetimeSize(owned!.terabytes)} lifetime seat, at ${money(offer.addOn!.amountPerTerabyte, offer.currency)} for each TB. One payment, kept for as long as harbor0 runs.`
          : `One payment, every feature, for as long as harbor0 runs. ${[
              !owned &&
                offer.invited &&
                `${money(offer.invited.amount, offer.currency)} for ${lifetimeSize(offer.invited.terabytes)}.`,
              seatsOpen &&
                `${money(offer.prices[0].amount, offer.currency)} for 1 TB, then ${money(extra, offer.currency)} for each extra TB, up to ${offer.maxTerabytes} TB. ${seatsLine(offer)}`,
            ]
              .filter(Boolean)
              .join(' ')}`
      }
    >
      <div className="form">
        {error && <Alert tone="error">{error}</Alert>}
        {seatsOpen && offer.allHeld && (
          <Alert role="none">
            The last seats are in other people’s checkouts. If they don’t finish, the seats open
            again within 30 minutes.
          </Alert>
        )}
        {addingOn ? (
          <Field
            label="Storage to add, in TB"
            hint={
              valid
                ? `${money(chosen.amount, offer.currency)}, one payment`
                : `Enter a whole number of TB, from 1 to ${most}.`
            }
          >
            <Input
              type="number"
              inputMode="numeric"
              min={1}
              max={most}
              step={1}
              value={typed}
              disabled={busy}
              aria-invalid={!valid}
              onChange={(e) => setTyped(e.target.value)}
            />
          </Field>
        ) : (
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
        )}
        <ul className="muted lifetime-terms">
          {addingOn ? (
            <li>It adds to your seat and stays with this account. It can’t be moved.</li>
          ) : (
            <li>
              One lifetime seat per account, and you can add storage to it later. It can’t be moved
              to another account.
            </li>
          )}
          <li>The storage adds to any plan you have, now or later.</li>
          <li>Version history and trash count toward it, as on every plan.</li>
          <li>Discount codes don’t apply. Sales tax or VAT may be added at checkout.</li>
        </ul>
        <DialogActions>
          <Button type="button" variant="outline" disabled={busy} onClick={onClose}>
            Close
          </Button>
          <Button type="button" disabled={busy || held || !valid} onClick={() => void checkout()}>
            {busy
              ? 'Opening checkout…'
              : !valid
                ? 'Add storage'
                : `${addingOn ? 'Add' : 'Buy'} ${lifetimeSize(chosen.terabytes)} for ${money(chosen.amount, offer.currency)}`}
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
