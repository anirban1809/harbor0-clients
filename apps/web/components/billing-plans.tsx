'use client';
import { useEffect, useRef, useState } from 'react';
import { usePathname, useRouter, useSearchParams } from 'next/navigation';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { Receipt } from 'lucide-react';
import type { ApiClient } from '@harbor/api-client';
import { Alert } from './ui/alert';
import { Button } from './ui/button';
import { Card } from './ui/card';
import { Segmented } from './ui/segmented';

type Interval = 'month' | 'year';
type Plan = {
  id: string;
  name: string;
  storageBytes: number;
  priceMinorUnits: number;
  /** The first payment's price while a sale is on; the plan renews at priceMinorUnits. */
  salePriceMinorUnits?: number | null;
  billingPeriod: 'MONTH' | 'YEAR';
  /** More than 1 when the plan stacks; the price is per block. */
  maxUnits?: number;
};
type Subscription = {
  planId: string;
  /** A lifetime seat, which adds to any plan. */
  lifetime?: { seat: number; terabytes: number } | null;
  entitlement: { baseFreeBytes: number; paidBytes: number; totalQuotaBytes: number };
  subscription?: {
    interval: Interval;
    status: string;
    cancelAtPeriodEnd: boolean;
    currentPeriodEnd: string | null;
  };
};

type Sale = { percent: number; months: number; endsAt: string };

const usd = new Intl.NumberFormat('en-US', { style: 'currency', currency: 'USD' });
/** The sale's last day: `endsAt` is when that day has ended everywhere, noon UTC the day after. */
const lastDay = (endsAt: string) =>
  new Date(Date.parse(endsAt) - 86_400_000).toLocaleDateString(undefined, {
    day: 'numeric',
    month: 'long',
    timeZone: 'UTC',
  });
const day = (iso: string) =>
  new Date(iso).toLocaleDateString(undefined, { day: 'numeric', month: 'long', year: 'numeric' });

export function useBillingSubscription(api: ApiClient, enabled = true) {
  return useQuery({
    queryKey: ['billing', 'subscription'],
    queryFn: () => api.request('/v1/billing/subscription') as Promise<Subscription>,
    enabled,
  });
}

/** The badge on the storage meter: "Free plan", "500 GB plan", "500 GB plan + 2 TB lifetime". */
export const planLabel = (planId = 'free', lifetime?: { terabytes: number } | null) => {
  const plan =
    planId === 'free'
      ? 'Free plan'
      : `${planId.replace(/(\d+)(gb|tb)/i, (_, n, u) => `${n} ${u.toUpperCase()}`)} plan`;
  if (!lifetime) return plan;
  return planId === 'free'
    ? `${lifetime.terabytes} TB lifetime`
    : `${plan} + ${lifetime.terabytes} TB lifetime`;
};

/**
 * Paid plans on the Storage page. Arriving with `?plan=500gb&interval=year` (the website's
 * pricing page links here) goes straight to checkout; Polar sends people back with `?checkout=`.
 */
export function BillingPlans({ api }: { api: ApiClient }) {
  const cache = useQueryClient();
  const router = useRouter();
  const pathname = usePathname();
  const searchParams = useSearchParams();
  const [interval, setPeriod] = useState<Interval>(
    searchParams.get('interval') === 'month' ? 'month' : 'year',
  );
  const [busy, setBusy] = useState<string>();
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const plans = useQuery({
    queryKey: ['billing', 'plans'],
    queryFn: () =>
      api.request('/v1/billing/plans') as Promise<{
        items: Plan[];
        checkoutAvailable: boolean;
        sale?: Sale | null;
      }>,
  });
  const current = useBillingSubscription(api);
  const started = useRef(false);

  const checkout = async (planId: string, period: Interval, units = 1) => {
    setBusy(planId);
    setError('');
    try {
      const { checkoutUrl } = await api.request('/v1/billing/checkout', {
        method: 'POST',
        body: { planId, interval: period, units },
      });
      window.location.assign(checkoutUrl);
    } catch (e) {
      setError((e as Error).message);
      setBusy(undefined);
    }
  };

  // Back from checkout: the webhook may not have arrived yet, so ask for the plan now.
  useEffect(() => {
    if (!searchParams.get('checkout') || started.current) return;
    started.current = true;
    router.replace(pathname);
    setNotice('Confirming your payment…');
    void api
      .request('/v1/billing/sync', { method: 'POST', body: {} })
      .then(({ planId }: { planId: string }) => {
        setNotice(
          planId === 'free'
            ? 'Your payment is still processing. Your new storage appears here in a minute or two.'
            : 'Thanks! Your new plan is active.',
        );
        void cache.invalidateQueries({ queryKey: ['billing'] });
        void cache.invalidateQueries({ queryKey: ['me'] });
      })
      .catch((e: Error) => setNotice(e.message));
  }, [searchParams]);

  // From the website's pricing page: start checkout for the chosen plan.
  useEffect(() => {
    const planId = searchParams.get('plan');
    if (!planId || started.current || !plans.data || !current.data) return;
    started.current = true;
    router.replace(pathname);
    // Without checkout the card already says paid plans aren't available.
    if (!plans.data.checkoutAvailable) return;
    if (current.data.planId !== 'free')
      setError('You already have a plan. Change it from Settings → Billing.');
    else if (plans.data.items.some((p) => p.id === planId)) {
      const max = plans.data.items.find((p) => p.id === planId)?.maxUnits ?? 1;
      const units = Math.min(max, Math.max(1, Math.floor(Number(searchParams.get('units')) || 1)));
      void checkout(planId, searchParams.get('interval') === 'month' ? 'month' : 'year', units);
    }
  }, [searchParams, plans.data, current.data]);

  const paid = (plans.data?.items ?? []).filter(
    (p) => p.id !== 'free' && p.billingPeriod === (interval === 'year' ? 'YEAR' : 'MONTH'),
  );
  const active = current.data?.planId !== 'free' ? current.data : undefined;
  const sub = active?.subscription;
  const sale = plans.data?.checkoutAvailable ? plans.data.sale : undefined;

  return (
    <Card
      className="panel billing-plans"
      title={active ? 'Your plan' : 'Get more storage'}
      description={
        active && sub
          ? sub.cancelAtPeriodEnd && sub.currentPeriodEnd
            ? `Cancelled — your storage stays until ${day(sub.currentPeriodEnd)}.`
            : sub.currentPeriodEnd
              ? `Renews ${sub.interval === 'year' ? 'yearly' : 'monthly'} on ${day(sub.currentPeriodEnd)}.`
              : undefined
          : sale
            ? `Black Friday: ${sale.percent}% off ${interval === 'year' ? 'the first year' : `the first ${sale.months} months`}, until ${lastDay(sale.endsAt)}. Every plan gets every feature.`
            : 'Every plan gets every feature. Plans differ only in storage.'
      }
      action={
        active ? (
          <Button variant="outline" onClick={() => router.push('/settings?tab=billing')}>
            <Receipt aria-hidden="true" />
            Billing & invoices
          </Button>
        ) : (
          <Segmented
            label="Billing period"
            value={interval}
            onValueChange={setPeriod}
            options={[
              { value: 'month', label: 'Monthly' },
              { value: 'year', label: 'Annual' },
            ]}
          />
        )
      }
    >
      {notice && <Alert tone="success">{notice}</Alert>}
      {error && <Alert tone="error">{error}</Alert>}
      {!active && plans.data && !plans.data.checkoutAvailable && (
        <Alert role="none">Paid plans are not available yet.</Alert>
      )}
      {!active && plans.data?.checkoutAvailable && (
        <div className="plan-options">
          {paid.map((plan) => (
            <div className="plan-option" key={plan.id}>
              <strong>{plan.name}</strong>
              <span className="muted">
                {plan.salePriceMinorUnits != null && (
                  <>
                    <s>{usd.format(plan.priceMinorUnits / 100)}</s>{' '}
                    <strong className="plan-sale">
                      {usd.format(plan.salePriceMinorUnits / 100)}
                    </strong>{' '}
                  </>
                )}
                {plan.salePriceMinorUnits == null && usd.format(plan.priceMinorUnits / 100)} /{' '}
                {interval === 'year' ? 'year' : 'month'}
                {(plan.maxUnits ?? 1) > 1 &&
                  ` · stacks up to ${plan.maxUnits} × (${((plan.storageBytes * plan.maxUnits!) / 1e12).toLocaleString()} TB)`}
              </span>
              <Button
                size="sm"
                variant="outline"
                disabled={!!busy}
                onClick={() => void checkout(plan.id, interval)}
              >
                {busy === plan.id ? 'Opening checkout…' : 'Choose'}
              </Button>
            </div>
          ))}
        </div>
      )}
    </Card>
  );
}
