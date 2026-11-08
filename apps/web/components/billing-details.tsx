'use client';
import { useState, type FormEvent } from 'react';
import { Pencil } from 'lucide-react';
import type { ApiClient } from '@harbor/api-client';
import { countryName, countryOptions, type BillingAccount } from '../lib/billing';
import { Alert } from './ui/alert';
import { Button } from './ui/button';
import { Card } from './ui/card';
import { Field } from './ui/field';
import { Input } from './ui/input';
import { Select } from './ui/select';

type Customer = NonNullable<BillingAccount['customer']>;

/** The name and address printed on invoices, edited here and saved with Polar. */
export function BillingDetails({
  api,
  customer,
  onChanged,
}: {
  api: ApiClient;
  customer: Customer;
  onChanged: (message: string) => void;
}) {
  const [editing, setEditing] = useState(false);
  const [values, setValues] = useState({ name: customer.name ?? '', ...customer.billingAddress });
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const set = (key: keyof typeof values) => (event: { target: { value: string } }) =>
    setValues((current) => ({ ...current, [key]: event.target.value }));

  const save = async (event: FormEvent) => {
    event.preventDefault();
    setBusy(true);
    setError('');
    try {
      const { line2, state, ...required } = values;
      await api.request('/v1/billing/details', {
        method: 'PATCH',
        body: {
          ...required,
          ...(line2.trim() ? { line2 } : {}),
          ...(state.trim() ? { state } : {}),
        },
      });
      setEditing(false);
      onChanged('Billing details saved. New invoices use them.');
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  };

  return (
    <Card
      className="panel"
      title="Billing details"
      description="Shown on your invoices. Polar handles payments and tax as our merchant of record."
      action={
        !editing && (
          <Button
            variant="outline"
            onClick={() => {
              setValues({ name: customer.name ?? '', ...customer.billingAddress });
              setError('');
              setEditing(true);
            }}
          >
            <Pencil aria-hidden="true" />
            Edit
          </Button>
        )
      }
    >
      {editing ? (
        <form className="form billing-details-form" onSubmit={(event) => void save(event)}>
          {error && <Alert tone="error">{error}</Alert>}
          <Field label="Name or company">
            <Input value={values.name} onChange={set('name')} autoComplete="name" required />
          </Field>
          <Field label="Address">
            <Input
              value={values.line1}
              onChange={set('line1')}
              autoComplete="address-line1"
              required
            />
          </Field>
          <Field label="Address line 2 (optional)">
            <Input value={values.line2} onChange={set('line2')} autoComplete="address-line2" />
          </Field>
          <div className="field-row">
            <Field label="City">
              <Input
                value={values.city}
                onChange={set('city')}
                autoComplete="address-level2"
                required
              />
            </Field>
            <Field label="State or region (optional)">
              <Input value={values.state} onChange={set('state')} autoComplete="address-level1" />
            </Field>
          </div>
          <div className="field-row">
            <Field label="Postal code">
              <Input
                value={values.postalCode}
                onChange={set('postalCode')}
                autoComplete="postal-code"
                required
              />
            </Field>
            <Field label="Country">
              <Select value={values.country} onChange={set('country')} required block>
                <option value="">Choose a country</option>
                {countryOptions().map(({ code, name }) => (
                  <option key={code} value={code}>
                    {name}
                  </option>
                ))}
              </Select>
            </Field>
          </div>
          <p className="muted">
            Changing the country can change the tax on future payments. Invoices already made keep
            their old details.
          </p>
          <div className="settings-actions">
            <Button type="submit" disabled={busy}>
              {busy ? 'Saving…' : 'Save details'}
            </Button>
            <Button
              type="button"
              variant="outline"
              disabled={busy}
              onClick={() => setEditing(false)}
            >
              Cancel
            </Button>
          </div>
        </form>
      ) : (
        <dl className="details">
          <dt>Name</dt>
          <dd>{customer.name ?? '—'}</dd>
          <dt>Billing email</dt>
          <dd>{customer.email}</dd>
          <dt>Address</dt>
          <dd>
            {[customer.address, countryName(customer.country)].filter(Boolean).join(', ') || '—'}
          </dd>
          {customer.taxId && (
            <>
              <dt>Tax ID</dt>
              <dd>{customer.taxId}</dd>
            </>
          )}
        </dl>
      )}
    </Card>
  );
}
