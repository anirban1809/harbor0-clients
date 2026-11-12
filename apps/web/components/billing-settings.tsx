'use client';
import { useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { ArrowUpDown, Download, HardDrive, Plus, RotateCcw, XCircle } from 'lucide-react';
import type { ApiClient } from '@harbor/api-client';
import {
  day,
  lifetimeSize,
  money,
  samePlan,
  type BillingAccount,
  type Payment,
  type Subscription,
} from '../lib/billing';
import { PaymentMethods } from './billing-cards';
import { BillingDetails } from './billing-details';
import { CancelPlanDialog, ChangePlanDialog } from './billing-plan-dialogs';
import { LifetimeSeatCard } from './lifetime-deal';
import { LoadError } from './empty-state';
import { ContentSkeleton } from './loading-states';
import { Alert } from './ui/alert';
import { Badge, type BadgeTone } from './ui/badge';
import { Button } from './ui/button';
import { Card } from './ui/card';
import { DataTable } from './ui/table';

const reasons: Record<string, string> = {
  subscription_create: 'New plan',
  subscription_cycle: 'Renewal',
  subscription_update: 'Plan change',
  purchase: 'Purchase',
};
const paymentStatus: Record<string, [string, BadgeTone]> = {
  paid: ['Paid', 'success'],
  pending: ['Pending', 'warning'],
  refunded: ['Refunded', 'neutral'],
  partially_refunded: ['Partly refunded', 'neutral'],
  void: ['Void', 'neutral'],
};

function planStatus(s: Subscription): [string, BadgeTone] {
  if (s.status === 'past_due') return ['Payment failed', 'danger'];
  if (s.status === 'trialing') return ['Trial', 'accent'];
  if (s.status !== 'active') return ['Ended', 'neutral'];
  if (s.cancelAtPeriodEnd) return [`Ends ${day(s.currentPeriodEnd)}`, 'warning'];
  return ['Active', 'success'];
}

/**
 * Settings → Billing: the plan (change, cancel, resume), cards, billing details and every payment
 * with its invoice. Everything happens here; card numbers go into Polar's embedded form.
 */
export function BillingSettings({
  api,
  freeStorage,
  freeBytes,
  usedBytes,
  onGetStorage,
}: {
  api: ApiClient;
  /** The account's free storage, formatted ("50 GB"). */
  freeStorage: string;
  freeBytes: number;
  usedBytes: number;
  onGetStorage: () => void;
}) {
  const cache = useQueryClient();
  const account = useQuery({
    queryKey: ['billing', 'account'],
    queryFn: () => api.request('/v1/billing/account') as Promise<BillingAccount>,
  });
  const [busy, setBusy] = useState<string>();
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const [dialog, setDialog] = useState<'change' | 'add' | 'cancel'>();

  const changed = (message: string) => {
    setDialog(undefined);
    setError('');
    if (message) setNotice(message);
    void cache.invalidateQueries({ queryKey: ['billing'] });
    void cache.invalidateQueries({ queryKey: ['me'] });
  };
  const act = async (key: string, work: () => Promise<unknown>, message: string) => {
    setBusy(key);
    setError('');
    setNotice('');
    try {
      await work();
      changed(message);
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(undefined);
    }
  };
  const invoice = async (payment: Payment) => {
    // Opened now, while the click still counts, so the browser doesn't block it as a pop-up.
    const tab = window.open('', '_blank');
    setBusy(payment.id);
    setError('');
    try {
      const { url } = await api.request(`/v1/billing/payments/${payment.id}/invoice`, {
        method: 'POST',
        body: {},
      });
      if (tab) tab.location.href = url;
      else window.location.assign(url);
    } catch (e) {
      tab?.close();
      setError((e as Error).message);
    } finally {
      setBusy(undefined);
    }
  };

  if (account.isPending)
    return (
      <Card className="panel" title="Plan">
        <ContentSkeleton label="Loading billing" />
      </Card>
    );
  if (account.isError || !account.data)
    return (
      <Card className="panel" title="Plan">
        <LoadError compact onRetry={() => void account.refetch()} />
      </Card>
    );
  const {
    subscription: sub,
    customer,
    payments,
    paymentMethods = [],
    changes = [],
    available,
    lifetime,
  } = account.data;
  const seat = lifetime && !lifetime.refundedAt ? lifetime : undefined;
  const live = sub && sub.status !== 'canceled' && !sub.endedAt;
  // A change waiting for renewal sets what the next payment is.
  const pendingOption = sub?.pendingChange
    ? changes.find((option) => samePlan(option, sub.pendingChange!))
    : undefined;
  const nextAmount = pendingOption?.amount ?? sub?.amount ?? 0;
  // On a plan that stacks, one more block of it, while the plan allows more.
  const addBlock = sub
    ? changes.find((option) => samePlan(option, { ...sub, units: sub.units + 1 }))
    : undefined;
  const [statusLabel, statusTone] = sub ? planStatus(sub) : ['Free', 'accent' as BadgeTone];
  const per = sub?.interval === 'year' ? 'year' : 'month';
  return (
    <>
      {notice && (
        <Alert tone="success" onDismiss={() => setNotice('')}>
          {notice}
        </Alert>
      )}
      {error && <Alert tone="error">{error}</Alert>}
      {lifetime && <LifetimeSeatCard seat={lifetime} />}
      <Card
        className="panel billing-plan"
        title={live ? `${sub.name} plan` : seat ? 'Plan' : 'Free plan'}
        description={
          live
            ? `${money(sub.amount, sub.currency)} per ${per}, plus any sales tax or VAT.`
            : seat
              ? `No plan. A plan’s storage adds to your ${lifetimeSize(seat.terabytes)} of lifetime storage.`
              : `${freeStorage} of storage with every feature included.`
        }
        action={
          <Badge tone={live ? statusTone : 'accent'}>
            {live ? statusLabel : seat ? 'None' : 'Free'}
          </Badge>
        }
      >
        {live && sub.status === 'past_due' && (
          <Alert tone="error">
            Your last payment didn’t go through. Add a card under Payment methods and make it the
            default to keep your plan.
          </Alert>
        )}
        {live && sub.pendingChange && (
          <Alert
            tone="info"
            action={
              <Button
                size="sm"
                variant="outline"
                disabled={!!busy}
                onClick={() =>
                  void act(
                    'keep',
                    () =>
                      api.request('/v1/billing/change', {
                        method: 'POST',
                        body: { planId: sub.planId, interval: sub.interval, units: sub.units },
                      }),
                    `You’ll stay on ${sub.name}.`,
                  )
                }
              >
                {busy === 'keep' ? 'Saving…' : `Keep ${sub.name}`}
              </Button>
            }
          >
            You’re moving to {sub.pendingChange.name}
            {sub.pendingChange.interval !== sub.interval &&
              ` billed ${sub.pendingChange.interval === 'year' ? 'yearly' : 'monthly'}`}{' '}
            on {day(sub.pendingChange.appliesAt)}.
          </Alert>
        )}
        {live ? (
          <dl className="details">
            <dt>Storage</dt>
            <dd>{sub.name}</dd>
            <dt>Billing</dt>
            <dd>
              {money(sub.amount, sub.currency)} / {per}
            </dd>
            <dt>Current period</dt>
            <dd>
              {day(sub.currentPeriodStart)} – {day(sub.currentPeriodEnd)}
            </dd>
            <dt>{sub.cancelAtPeriodEnd ? 'Plan ends' : 'Next payment'}</dt>
            <dd>
              {sub.cancelAtPeriodEnd
                ? `${day(sub.currentPeriodEnd)} — it won’t renew, and your storage returns to ${seat ? `your ${lifetimeSize(seat.terabytes)} of lifetime storage` : freeStorage}.`
                : `${day(sub.currentPeriodEnd)} · ${money(nextAmount, sub.currency)} plus tax${sub.pendingChange ? ` for ${sub.pendingChange.name}` : ''}`}
            </dd>
            <dt>Customer since</dt>
            <dd>{day(sub.startedAt)}</dd>
          </dl>
        ) : (
          sub && (
            <p className="muted">
              Your {sub.name} plan ended on {day(sub.endedAt ?? sub.currentPeriodEnd)}.
            </p>
          )
        )}
        <div className="settings-actions">
          {live && sub.cancelAtPeriodEnd ? (
            <Button
              disabled={!!busy}
              onClick={() =>
                void act(
                  'resume',
                  () => api.request('/v1/billing/resume', { method: 'POST', body: {} }),
                  `Your ${sub.name} plan will renew on ${day(sub.currentPeriodEnd)}.`,
                )
              }
            >
              <RotateCcw aria-hidden="true" />
              {busy === 'resume' ? 'Resuming…' : 'Resume plan'}
            </Button>
          ) : live ? (
            <>
              {addBlock && (
                <Button onClick={() => setDialog('add')} disabled={!!busy}>
                  <Plus aria-hidden="true" />
                  Add {addBlock.storageBytes / addBlock.units / 1e12} TB
                </Button>
              )}
              <Button variant="outline" onClick={() => setDialog('change')} disabled={!!busy}>
                <ArrowUpDown aria-hidden="true" />
                Change plan
              </Button>
              <Button variant="ghost" onClick={() => setDialog('cancel')} disabled={!!busy}>
                <XCircle aria-hidden="true" />
                Cancel plan
              </Button>
            </>
          ) : (
            <Button onClick={onGetStorage} disabled={!available}>
              <HardDrive aria-hidden="true" />
              Get more storage
            </Button>
          )}
        </div>
      </Card>

      {(live || paymentMethods.length > 0) && (
        <PaymentMethods api={api} methods={paymentMethods} onChanged={changed} />
      )}
      {customer && <BillingDetails api={api} customer={customer} onChanged={changed} />}

      <Card
        className="panel"
        title="Payments"
        description="Your charges, newest first. Download an invoice for your records."
      >
        {payments.length === 0 ? (
          <p className="muted">No payments yet.</p>
        ) : (
          <DataTable label="Payments" className="payments-table">
            <thead>
              <tr>
                <th>Date</th>
                <th>Description</th>
                <th>Amount</th>
                <th aria-label="Invoice" />
              </tr>
            </thead>
            <tbody>
              {payments.map((payment) => {
                // A negative total is money owed back (unused time on a plan that changed).
                const [label, tone]: [string, BadgeTone] =
                  payment.total < 0
                    ? ['Credit', 'neutral']
                    : (paymentStatus[payment.status] ?? [payment.status, 'neutral']);
                return (
                  <tr key={payment.id}>
                    <td>{day(payment.createdAt)}</td>
                    <td>
                      <strong>{payment.description}</strong>
                      <small className="muted">
                        {reasons[payment.billingReason] ?? 'Payment'}
                        {payment.periodStart &&
                          payment.periodEnd &&
                          ` · ${day(payment.periodStart)} – ${day(payment.periodEnd)}`}
                      </small>
                    </td>
                    <td>
                      <span className="payment-amount">
                        <strong>{money(payment.total, payment.currency)}</strong>
                        <Badge tone={tone}>{label}</Badge>
                      </span>
                      {payment.tax > 0 && (
                        <small className="muted">
                          incl. {money(payment.tax, payment.currency)} tax
                        </small>
                      )}
                      {payment.discount > 0 && (
                        <small className="muted">
                          {money(payment.discount, payment.currency)} discount
                        </small>
                      )}
                      {payment.refunded > 0 && (
                        <small className="muted">
                          {money(payment.refunded, payment.currency)} refunded
                        </small>
                      )}
                    </td>
                    <td>
                      {payment.status !== 'pending' && payment.status !== 'void' && (
                        <Button
                          size="sm"
                          variant="ghost"
                          onClick={() => void invoice(payment)}
                          disabled={!!busy}
                          aria-label={`Download invoice${payment.invoiceNumber ? ` ${payment.invoiceNumber}` : ''} for ${day(payment.createdAt)}`}
                        >
                          <Download aria-hidden="true" />
                          {busy === payment.id ? 'Preparing…' : 'Invoice'}
                        </Button>
                      )}
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </DataTable>
        )}
      </Card>
      {live && (dialog === 'change' || dialog === 'add') && (
        <ChangePlanDialog
          api={api}
          open
          onClose={() => setDialog(undefined)}
          onChanged={changed}
          subscription={sub}
          options={changes}
          usedBytes={usedBytes}
          start={dialog === 'add' ? addBlock : undefined}
        />
      )}
      {live && dialog === 'cancel' && (
        <CancelPlanDialog
          api={api}
          open
          onClose={() => setDialog(undefined)}
          onCancelled={changed}
          subscription={sub}
          freeStorage={freeStorage}
          freeBytes={freeBytes}
          usedBytes={usedBytes}
        />
      )}
    </>
  );
}
