'use client';
import { useState } from 'react';
import { Trash2 } from 'lucide-react';
import {
  ACCOUNT_DELETION_DETAIL_MAX,
  ACCOUNT_DELETION_REASONS,
  type AccountDeletionReason,
  type AccountDeletionReasonCode,
} from '@harbor/contracts';
import { Alert } from './ui/alert';
import { Button } from './ui/button';
import { Card } from './ui/card';
import { Dialog, DialogActions } from './ui/dialog';
import { Field } from './ui/field';
import { Input, Textarea } from './ui/input';
import { Radio } from './ui/checkbox';

const reasonCodes = Object.keys(ACCOUNT_DELETION_REASONS) as AccountDeletionReasonCode[];

/** Settings card that deletes the account after the email is retyped, asking why first. */
export function DeleteAccount({
  email,
  onDelete,
}: {
  email: string;
  onDelete: (email: string, reason?: AccountDeletionReason) => Promise<unknown>;
}) {
  const [open, setOpen] = useState(false);
  const [typed, setTyped] = useState('');
  const [code, setCode] = useState<AccountDeletionReasonCode>();
  const [detail, setDetail] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const matches = typed.trim().toLowerCase() === email.toLowerCase();
  // A reason is optional, but “Something else” needs a few words to be worth asking.
  const explained = code !== 'OTHER' || !!detail.trim();
  return (
    <Card
      className="panel"
      title="Delete account"
      description="You’ll be signed out everywhere right away. Your files, backups and transfers are kept for 30 days and then permanently deleted."
    >
      <Button
        variant="danger"
        onClick={() => {
          setTyped('');
          setCode(undefined);
          setDetail('');
          setError('');
          setOpen(true);
        }}
      >
        <Trash2 />
        Delete account
      </Button>
      <Dialog
        open={open}
        onOpenChange={(value) => {
          if (!value && !busy) setOpen(false);
        }}
        title="Delete your account?"
        description="This can’t be undone. You’ll be signed out on every device, and your data will be permanently deleted after 30 days."
      >
        <form
          className="form"
          onSubmit={(event) => {
            event.preventDefault();
            if (!matches || !explained || busy) return;
            setBusy(true);
            setError('');
            const said = detail.trim();
            onDelete(typed.trim(), code && { code, ...(said ? { detail: said } : {}) })
              .catch((e: Error) => setError(e.message))
              .finally(() => setBusy(false));
          }}
        >
          {error && <Alert tone="error">{error}</Alert>}
          <fieldset className="choice-list" data-compact="true">
            <legend>Why are you leaving? (optional)</legend>
            {reasonCodes.map((value) => (
              <label key={value} className="choice">
                <Radio
                  name="reason"
                  value={value}
                  checked={code === value}
                  disabled={busy}
                  onChange={() => setCode(value)}
                />
                <span>{ACCOUNT_DELETION_REASONS[value]}</span>
              </label>
            ))}
          </fieldset>
          {code && (
            <Field label={code === 'OTHER' ? 'Tell us more' : 'Anything else to add? (optional)'}>
              <Textarea
                name="detail"
                rows={3}
                maxLength={ACCOUNT_DELETION_DETAIL_MAX}
                value={detail}
                disabled={busy}
                required={code === 'OTHER'}
                autoFocus={code === 'OTHER'}
                onChange={(event) => setDetail(event.target.value)}
              />
            </Field>
          )}
          <Field label={`Type ${email} to confirm`}>
            <Input
              name="email"
              type="email"
              autoComplete="off"
              spellCheck={false}
              value={typed}
              onChange={(event) => setTyped(event.target.value)}
              required
            />
          </Field>
          <DialogActions>
            <Button type="button" variant="outline" disabled={busy} onClick={() => setOpen(false)}>
              Go back
            </Button>
            <Button type="submit" variant="danger" disabled={!matches || !explained || busy}>
              {busy ? 'Deleting…' : 'Delete account'}
            </Button>
          </DialogActions>
        </form>
      </Dialog>
    </Card>
  );
}
