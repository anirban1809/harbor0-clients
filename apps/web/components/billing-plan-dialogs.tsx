'use client';
import { useState } from 'react';
import type { ApiClient } from '@harbor/api-client';
import {
  day,
  money,
  samePlan,
  type Interval,
  type PlanOption,
  type Subscription,
} from '../lib/billing';
import { Alert } from './ui/alert';
import { Badge } from './ui/badge';
import { Button } from './ui/button';
import { Dialog, DialogActions } from './ui/dialog';
import { Field } from './ui/field';
import { Textarea } from './ui/input';
import { Segmented } from './ui/segmented';
import { Select } from './ui/select';

const gb = (bytes: number) => `${(bytes / 1e9).toFixed(1)} GB`;

/**
 * Pick another plan, or more or fewer blocks of a plan that stacks; the server says whether it
 * applies now (prorated) or at renewal.
 */
export function ChangePlanDialog({
  api,
  open,
  onClose,
  onChanged,
  subscription,
  options,
  usedBytes,
  start,
}: {
  api: ApiClient;
  open: boolean;
  onClose: () => void;
  onChanged: (message: string) => void;
  subscription: Subscription;
  options: PlanOption[];
  usedBytes: number;
  /** Opens with this choice made, e.g. one more block of the current plan. */
  start?: PlanOption;
}) {
  const [interval, setPeriod] = useState<Interval>(start?.interval ?? subscription.interval);
  const [chosen, setChosen] = useState<PlanOption | undefined>(start);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const shown = options.filter((option) => option.interval === interval);
  // One row per plan; a plan that stacks offers its blocks below the row once chosen.
  const rows = shown.filter(
    (option, i) => shown.findIndex((other) => other.planId === option.planId) === i,
  );
  const blocksOf = (planId: string) => shown.filter((option) => option.planId === planId);
  const renews = day(subscription.currentPeriodEnd);
  const pending = subscription.pendingChange;
  const isPending = (option: PlanOption) => !!pending && samePlan(pending, option);

  const describe = (option: PlanOption) => {
    if (option.kind === 'current')
      return pending ? `Keep it instead of moving to ${pending.name}` : 'Your plan';
    if (option.kind === 'unavailable') return option.reason ?? 'Not available';
    if (option.storageBytes < usedBytes) return `You’re storing ${gb(usedBytes)}`;
    if (option.kind === 'now')
      return option.estimate
        ? `Starts today · about ${money(option.estimate)} now, plus tax`
        : 'Starts today';
    return `Starts ${renews}, when your current period ends`;
  };
  const selectable = (option: PlanOption) =>
    option.kind !== 'unavailable' &&
    option.storageBytes >= usedBytes &&
    (option.kind !== 'current' || !!pending) &&
    !isPending(option);
  // A row starts at the blocks you have (or are moving to), else one block.
  const rowOption = (planId: string) => {
    const blocks = blocksOf(planId);
    if (chosen?.planId === planId && chosen.interval === interval) return chosen;
    return blocks.find(isPending) ?? blocks.find((o) => o.kind === 'current') ?? blocks[0];
  };
  const pick = (planId: string) => {
    const blocks = blocksOf(planId);
    const start = rowOption(planId);
    setChosen(selectable(start) ? start : (blocks.find(selectable) ?? start));
  };

  const confirm = async () => {
    if (!chosen) return;
    setBusy(true);
    setError('');
    try {
      const { applies } = await api.request('/v1/billing/change', {
        method: 'POST',
        body: { planId: chosen.planId, interval: chosen.interval, units: chosen.units },
      });
      onChanged(
        applies === 'now'
          ? `You’re now on ${chosen.name}. The extra space is ready to use.`
          : applies === 'kept'
            ? `You’ll stay on ${chosen.name}.`
            : `You’ll move to ${chosen.name} on ${renews}. Until then nothing changes.`,
      );
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  };

  return (
    <Dialog
      open={open}
      onOpenChange={(value) => !value && !busy && onClose()}
      title="Change plan"
      description="More space starts right away and you pay only the difference for the rest of this period. A smaller plan, fewer blocks or monthly billing start when your current period ends. 2 TB and 5 TB stack: add blocks now or any time later."
    >
      <div className="form">
        {error && <Alert tone="error">{error}</Alert>}
        <Segmented
          label="Billing period"
          value={interval}
          onValueChange={(value) => {
            setPeriod(value);
            setChosen(undefined);
          }}
          options={[
            { value: 'month', label: 'Monthly' },
            { value: 'year', label: 'Annual' },
          ]}
        />
        <fieldset className="choice-list plan-choices">
          <legend className="sr-only">Plans</legend>
          {rows.map((row) => {
            const option = rowOption(row.planId);
            const blocks = blocksOf(row.planId);
            const enabled = blocks.some(selectable);
            const picked = chosen?.planId === row.planId && chosen.interval === interval;
            const key = `${row.planId}-${row.interval}`;
            return (
              <label className="choice" key={key} data-disabled={!enabled || undefined}>
                <input
                  type="radio"
                  className="radio"
                  name="plan"
                  value={key}
                  disabled={!enabled || busy}
                  checked={picked}
                  onChange={() => pick(row.planId)}
                />
                <span>
                  <strong>
                    {option.name} · {money(option.amount)} / {option.interval}
                    {option.kind === 'current' && <Badge tone="accent">Current</Badge>}
                    {isPending(option) && pending && (
                      <Badge tone="warning">From {day(pending.appliesAt)}</Badge>
                    )}
                  </strong>
                  <span className="muted">{describe(option)}</span>
                  {blocks.length > 1 && picked && (
                    <Select
                      aria-label={`Blocks of ${row.name}`}
                      value={String(option.units)}
                      disabled={busy}
                      onChange={(event) =>
                        setChosen(blocks.find((b) => b.units === Number(event.target.value)))
                      }
                    >
                      {blocks.map((block) => (
                        <option key={block.units} value={block.units} disabled={!selectable(block)}>
                          {block.units} × {row.name} = {block.storageBytes / 1e12} TB ·{' '}
                          {money(block.amount)} / {block.interval}
                        </option>
                      ))}
                    </Select>
                  )}
                </span>
              </label>
            );
          })}
        </fieldset>
        {chosen && !selectable(chosen) && <p className="muted">{describe(chosen)}</p>}
        {chosen?.kind === 'now' && (
          <p className="muted">
            Your card on file is charged now. The exact amount, with tax, appears under Payments.
          </p>
        )}
        <DialogActions>
          <Button type="button" variant="outline" disabled={busy} onClick={onClose}>
            Close
          </Button>
          <Button
            type="button"
            disabled={!chosen || !selectable(chosen) || busy}
            onClick={() => void confirm()}
          >
            {busy
              ? 'Changing…'
              : chosen?.kind === 'now'
                ? `Upgrade to ${chosen.name}`
                : chosen?.kind === 'current'
                  ? `Keep ${chosen.name}`
                  : chosen
                    ? `Switch on ${renews}`
                    : 'Choose a plan'}
          </Button>
        </DialogActions>
      </div>
    </Dialog>
  );
}

const reasons = [
  ['too_expensive', 'It costs too much'],
  ['unused', 'I don’t use it enough'],
  ['missing_features', 'It’s missing something I need'],
  ['switched_service', 'I’m moving to another service'],
  ['too_complex', 'It’s too hard to use'],
  ['low_quality', 'It doesn’t work well enough'],
  ['customer_service', 'I had a problem with support'],
  ['other', 'Something else'],
] as const;

/** Cancel at the end of the paid period, with an optional reason. */
export function CancelPlanDialog({
  api,
  open,
  onClose,
  onCancelled,
  subscription,
  freeStorage,
  freeBytes,
  usedBytes,
}: {
  api: ApiClient;
  open: boolean;
  onClose: () => void;
  onCancelled: (message: string) => void;
  subscription: Subscription;
  freeStorage: string;
  freeBytes: number;
  usedBytes: number;
}) {
  const [reason, setReason] = useState('');
  const [comment, setComment] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const ends = day(subscription.currentPeriodEnd);
  const cancel = async () => {
    setBusy(true);
    setError('');
    try {
      await api.request('/v1/billing/cancel', {
        method: 'POST',
        body: { ...(reason ? { reason } : {}), ...(comment.trim() ? { comment } : {}) },
      });
      onCancelled(`Your plan is cancelled. You keep ${subscription.name} until ${ends}.`);
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  };
  return (
    <Dialog
      open={open}
      onOpenChange={(value) => !value && !busy && onClose()}
      title="Cancel your plan?"
      description={`You keep ${subscription.name} until ${ends} and won’t be charged again. After that your storage goes back to ${freeStorage}.`}
    >
      <form
        className="form"
        onSubmit={(event) => {
          event.preventDefault();
          void cancel();
        }}
      >
        {error && <Alert tone="error">{error}</Alert>}
        {usedBytes > freeBytes && (
          <Alert tone="warning">
            You’re storing {gb(usedBytes)}. Nothing is deleted, but after {ends} new uploads pause
            until you’re under {freeStorage}.
          </Alert>
        )}
        {subscription.pendingChange && (
          <p className="muted">
            Your switch to {subscription.pendingChange.name} on{' '}
            {day(subscription.pendingChange.appliesAt)} is dropped too.
          </p>
        )}
        <Field label="Why are you cancelling? (optional)">
          <Select value={reason} onChange={(event) => setReason(event.target.value)} block>
            <option value="">Choose a reason</option>
            {reasons.map(([value, label]) => (
              <option key={value} value={value}>
                {label}
              </option>
            ))}
          </Select>
        </Field>
        <Field label="Anything we could do better? (optional)">
          <Textarea
            rows={3}
            maxLength={1000}
            value={comment}
            onChange={(event) => setComment(event.target.value)}
          />
        </Field>
        <DialogActions>
          <Button type="button" variant="outline" disabled={busy} onClick={onClose}>
            Keep my plan
          </Button>
          <Button type="submit" variant="danger" disabled={busy}>
            {busy ? 'Cancelling…' : 'Cancel plan'}
          </Button>
        </DialogActions>
      </form>
    </Dialog>
  );
}
