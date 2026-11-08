/** What `GET /v1/billing/account` returns; amounts are in minor units (cents). */
export type Interval = 'month' | 'year';
export type Payment = {
  id: string;
  createdAt: string;
  description: string;
  status: string;
  billingReason: string;
  subtotal: number;
  discount: number;
  tax: number;
  total: number;
  refunded: number;
  currency: string;
  invoiceNumber: string | null;
  periodStart: string | null;
  periodEnd: string | null;
};
export type Subscription = {
  planId: string | null;
  /** Blocks of the plan; 2 TB and 5 TB stack, so 2 × 5 TB is 10 TB. */
  units: number;
  name: string;
  interval: Interval;
  status: string;
  amount: number;
  currency: string;
  startedAt: string | null;
  currentPeriodStart: string | null;
  currentPeriodEnd: string | null;
  cancelAtPeriodEnd: boolean;
  canceledAt: string | null;
  endedAt: string | null;
  pendingChange: {
    planId: string;
    units: number;
    interval: Interval;
    name: string;
    appliesAt: string;
  } | null;
};
export type BillingAddress = {
  line1: string;
  line2: string;
  city: string;
  state: string;
  postalCode: string;
  country: string;
};
export type PaymentMethod = {
  id: string;
  brand: string | null;
  last4: string | null;
  expMonth: number | null;
  expYear: number | null;
  wallet: string | null;
  isDefault: boolean;
};
export type PlanOption = {
  planId: string;
  units: number;
  name: string;
  interval: Interval;
  storageBytes: number;
  amount: number;
  /** `now` charges the prorated difference today; `renewal` waits for the period to end. */
  kind: 'current' | 'now' | 'renewal' | 'unavailable';
  estimate: number | null;
  reason: string | null;
};
export type BillingAccount = {
  available: boolean;
  subscription: Subscription | null;
  customer: {
    name: string | null;
    email: string;
    country: string | null;
    address: string | null;
    taxId: string | null;
    billingAddress: BillingAddress;
  } | null;
  payments: Payment[];
  paymentMethods: PaymentMethod[];
  changes: PlanOption[];
};

/** Whether two plan choices are the same plan, period and number of blocks. */
export const samePlan = (
  a: { planId: string | null; interval: Interval; units: number },
  b: { planId: string | null; interval: Interval; units: number },
) => a.planId === b.planId && a.interval === b.interval && a.units === b.units;

export const money = (minor: number, currency = 'USD') =>
  new Intl.NumberFormat(undefined, { style: 'currency', currency }).format(minor / 100);
export const day = (iso: string | null) =>
  iso
    ? new Date(iso).toLocaleDateString(undefined, {
        day: 'numeric',
        month: 'short',
        year: 'numeric',
      })
    : '—';
export const countryName = (code: string | null) => {
  if (!code) return null;
  try {
    return new Intl.DisplayNames(undefined, { type: 'region' }).of(code) ?? code;
  } catch {
    return code;
  }
};

// Codes Intl names that aren't countries an address can be in.
const notCountries = new Set(['EU', 'EZ', 'UN', 'QO', 'XA', 'XB', 'ZZ']);
let countries: { code: string; name: string }[] | undefined;
/** Every country, by name, for the billing address form. */
export function countryOptions() {
  if (countries) return countries;
  const names = new Intl.DisplayNames(undefined, { type: 'region', fallback: 'none' });
  const letters = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ';
  countries = [];
  for (const a of letters)
    for (const b of letters) {
      const code = a + b;
      const name = names.of(code);
      if (name && name !== code && !notCountries.has(code)) countries.push({ code, name });
    }
  return countries.sort((x, y) => x.name.localeCompare(y.name));
}
