'use client';
import { useEffect, useRef, useState, type ClipboardEvent } from 'react';
import Link from 'next/link';
import { useRouter } from 'next/navigation';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { ArrowLeft, ImagePlus, LifeBuoy, Plus, X } from 'lucide-react';
import type { Transport } from '@harbor/api-client';
import {
  SUPPORT_ATTACHMENT_MAX_BYTES,
  SUPPORT_ATTACHMENT_TYPES,
  SUPPORT_ATTACHMENTS_MAX,
  SUPPORT_MESSAGE_MAX,
  SUPPORT_SUBJECT_MAX,
  SUPPORT_TICKET_KINDS,
  SUPPORT_TICKET_STATUSES,
  supportTicketDone,
  type SupportAttachment,
  type SupportTicket,
  type SupportTicketDetail,
  type SupportTicketKind,
} from '@harbor/contracts';
import { fileDate } from '../lib/file-metadata';
import { EmptyState, LoadError } from './empty-state';
import { ContentSkeleton } from './loading-states';
import { Alert } from './ui/alert';
import { Badge, type BadgeTone } from './ui/badge';
import { Button } from './ui/button';
import { Card } from './ui/card';
import { Radio } from './ui/checkbox';
import { Dialog, DialogActions } from './ui/dialog';
import { Field } from './ui/field';
import { Input, Textarea } from './ui/input';
import { DataTable } from './ui/table';

const kinds = Object.keys(SUPPORT_TICKET_KINDS) as SupportTicketKind[];
const kindHints: Record<SupportTicketKind, string> = {
  QUESTION: 'How something works, your account, or storage.',
  ISSUE: 'Something isn’t working the way it should.',
  FEATURE: 'Something you’d like harbor0 to do.',
};
const statusTone: Record<SupportTicket['status'], BadgeTone> = {
  OPEN: 'accent',
  IN_PROGRESS: 'warning',
  RESOLVED: 'success',
  CLOSED: 'neutral',
};
export const supportHref = (id?: string) =>
  id ? `/support?${new URLSearchParams({ id })}` : '/support';

type Picked = { file: File; preview: string };
const imageTypes: readonly string[] = SUPPORT_ATTACHMENT_TYPES;

/** Screenshots chosen for a message, before it's sent: added from a picker or pasted. */
function useScreenshots() {
  const [picked, setPicked] = useState<Picked[]>([]);
  const [problem, setProblem] = useState('');
  const latest = useRef(picked);
  latest.current = picked;
  useEffect(() => () => latest.current.forEach((p) => URL.revokeObjectURL(p.preview)), []);
  const add = (files: File[]) => {
    setProblem('');
    const images = files.filter((file) => {
      if (!imageTypes.includes(file.type)) {
        setProblem(`“${file.name}” isn’t a PNG, JPEG, WebP or GIF image.`);
        return false;
      }
      if (file.size > SUPPORT_ATTACHMENT_MAX_BYTES) {
        setProblem(`“${file.name}” is over 10 MB.`);
        return false;
      }
      return true;
    });
    setPicked((current) => {
      const room = SUPPORT_ATTACHMENTS_MAX - current.length;
      if (images.length > room) setProblem(`Add up to ${SUPPORT_ATTACHMENTS_MAX} screenshots.`);
      return [
        ...current,
        ...images.slice(0, Math.max(0, room)).map((file) => ({
          file,
          preview: URL.createObjectURL(file),
        })),
      ];
    });
  };
  const remove = (index: number) =>
    setPicked((current) => {
      URL.revokeObjectURL(current[index].preview);
      return current.filter((_, i) => i !== index);
    });
  const clear = () => {
    picked.forEach((p) => URL.revokeObjectURL(p.preview));
    setPicked([]);
  };
  /** Images pasted into the message box join the screenshots. */
  const onPaste = (event: ClipboardEvent) => {
    const files = [...event.clipboardData.files].filter((file) => file.type.startsWith('image/'));
    if (!files.length) return;
    event.preventDefault();
    // Pasted screenshots arrive as “image.png”; number them so they can be told apart.
    add(
      files.map(
        (file, i) =>
          new File([file], `Screenshot ${picked.length + i + 1}.${file.type.split('/')[1]}`, {
            type: file.type,
          }),
      ),
    );
  };
  return { picked, problem, add, remove, clear, onPaste };
}

/** Uploads each screenshot straight to storage and returns the ids a message names. */
async function uploadScreenshots(request: Transport, picked: Picked[]) {
  return Promise.all(
    picked.map(async ({ file }) => {
      const { id, uploadUrl }: { id: string; uploadUrl: string } = await request(
        '/v1/support/attachments',
        {
          method: 'POST',
          body: {
            name: file.name.slice(0, 200) || 'Screenshot',
            contentType: file.type,
            sizeBytes: file.size,
          },
        },
      );
      const sent = await fetch(uploadUrl, { method: 'PUT', body: file }).catch(() => null);
      if (!sent?.ok) throw new Error(`“${file.name}” couldn’t be uploaded. Try again.`);
      return id;
    }),
  );
}

function ScreenshotPicker({
  shots,
  disabled,
}: {
  shots: ReturnType<typeof useScreenshots>;
  disabled: boolean;
}) {
  const input = useRef<HTMLInputElement>(null);
  return (
    <div className="support-shots">
      {shots.picked.length > 0 && (
        <ul className="support-shot-list" aria-label="Screenshots to send">
          {shots.picked.map((p, i) => (
            <li key={p.preview} className="support-shot">
              <img src={p.preview} alt={p.file.name} />
              <Button
                type="button"
                variant="ghost"
                size="icon-sm"
                className="support-shot-remove"
                aria-label={`Remove ${p.file.name}`}
                disabled={disabled}
                onClick={() => shots.remove(i)}
              >
                <X aria-hidden="true" />
              </Button>
            </li>
          ))}
        </ul>
      )}
      <div className="support-shot-add">
        <Button
          type="button"
          variant="outline"
          size="sm"
          disabled={disabled || shots.picked.length >= SUPPORT_ATTACHMENTS_MAX}
          onClick={() => input.current?.click()}
        >
          <ImagePlus aria-hidden="true" />
          Add screenshots
        </Button>
        <span className="muted">
          Or paste them into the message. Up to {SUPPORT_ATTACHMENTS_MAX}, 10 MB each.
        </span>
        <input
          ref={input}
          type="file"
          accept={SUPPORT_ATTACHMENT_TYPES.join(',')}
          multiple
          hidden
          onChange={(event) => {
            shots.add([...(event.target.files ?? [])]);
            event.target.value = '';
          }}
        />
      </div>
      {shots.problem && <Alert tone="error">{shots.problem}</Alert>}
    </div>
  );
}

function Screenshots({ items }: { items: SupportAttachment[] }) {
  if (!items.length) return null;
  return (
    <ul className="support-shot-list" aria-label="Screenshots">
      {items.map((a) => (
        <li key={a.id} className="support-shot">
          <a href={a.url} target="_blank" rel="noreferrer noopener" title={a.name}>
            <img src={a.url} alt={a.name} loading="lazy" />
          </a>
        </li>
      ))}
    </ul>
  );
}

function StatusBadge({ ticket }: { ticket: SupportTicket }) {
  // Support has answered and the request is still going: the holder's turn.
  if (ticket.lastMessageBy === 'STAFF' && !supportTicketDone(ticket.status))
    return <Badge tone="success">Support replied</Badge>;
  return <Badge tone={statusTone[ticket.status]}>{SUPPORT_TICKET_STATUSES[ticket.status]}</Badge>;
}

/** The Support page: every request the account opened, or one request's thread. */
export function SupportPage({
  request,
  ticketId,
}: {
  request: Transport;
  ticketId: string | null;
}) {
  const [composing, setComposing] = useState(false);
  return (
    <>
      {ticketId ? (
        <SupportThread request={request} id={ticketId} />
      ) : (
        <SupportList request={request} onNew={() => setComposing(true)} />
      )}
      {composing && <NewRequestDialog request={request} onClose={() => setComposing(false)} />}
    </>
  );
}

function SupportList({ request, onNew }: { request: Transport; onNew: () => void }) {
  const tickets = useQuery<{ items: SupportTicket[] }>({
    queryKey: ['support-tickets'],
    queryFn: () => request('/v1/support/tickets'),
  });
  if (tickets.isPending) return <ContentSkeleton label="Loading support requests" />;
  if (tickets.isError && !tickets.data) return <LoadError onRetry={() => void tickets.refetch()} />;
  if (!tickets.data.items.length)
    return (
      <EmptyState
        icon={<LifeBuoy />}
        title="No support requests"
        description="Ask a question, tell us about a problem, or suggest a feature. We answer here and by email."
        actions={
          <Button onClick={onNew}>
            <Plus aria-hidden="true" />
            New request
          </Button>
        }
      />
    );
  return (
    <div className="support-page">
      <div className="support-toolbar">
        <p className="muted">We answer here and by email, usually within a day.</p>
        <Button onClick={onNew}>
          <Plus aria-hidden="true" />
          New request
        </Button>
      </div>
      <DataTable className="files-table" label="Support requests">
        <caption className="sr-only">
          Your support requests with their status and last activity.
        </caption>
        <thead>
          <tr>
            <th scope="col" className="file-name-column">
              <div className="file-heading-name">
                <span>Request</span>
              </div>
            </th>
            <th scope="col" className="file-size-column">
              Status
            </th>
            <th scope="col" className="file-modified-column">
              Updated
            </th>
          </tr>
        </thead>
        <tbody>
          {tickets.data.items.map((ticket) => {
            const updated = fileDate(ticket.updatedAt);
            return (
              <tr className="file-entry-row" key={ticket.id}>
                <td>
                  <div className="file-entry-identity">
                    <Link className="file-entry-open" href={supportHref(ticket.id)}>
                      <span className="file-entry-icon" data-kind="document">
                        <LifeBuoy aria-hidden="true" />
                      </span>
                      <span className="file-entry-label">
                        <strong>{ticket.subject}</strong>
                        <span className="support-meta">
                          {SUPPORT_TICKET_KINDS[ticket.kind]} · #{ticket.id}
                        </span>
                      </span>
                    </Link>
                  </div>
                </td>
                <td className="file-size-column">
                  <StatusBadge ticket={ticket} />
                </td>
                <td className="file-modified-column">
                  <span className="file-date-detail" title={updated.full}>
                    {updated.date}
                  </span>
                </td>
              </tr>
            );
          })}
        </tbody>
      </DataTable>
    </div>
  );
}

function SupportThread({ request, id }: { request: Transport; id: string }) {
  const queries = useQueryClient();
  const [reply, setReply] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const shots = useScreenshots();
  const thread = useQuery<SupportTicketDetail>({
    queryKey: ['support-ticket', id],
    queryFn: () => request(`/v1/support/tickets/${encodeURIComponent(id)}`),
    // Support may answer while the page is open.
    refetchInterval: 60_000,
  });
  const back = (
    <Link className="support-back" href={supportHref()}>
      <ArrowLeft aria-hidden="true" />
      All requests
    </Link>
  );
  if (thread.isPending) return <ContentSkeleton label="Loading support request" />;
  if (thread.isError && !thread.data)
    return (
      <div className="support-page">
        {back}
        <LoadError onRetry={() => void thread.refetch()} />
      </div>
    );
  const { ticket, messages } = thread.data;
  const done = supportTicketDone(ticket.status);
  const act = async (path: string, body?: { body: string }) => {
    setBusy(true);
    setError('');
    try {
      const attachments =
        body && shots.picked.length ? await uploadScreenshots(request, shots.picked) : [];
      const next: SupportTicketDetail = await request(path, {
        method: 'POST',
        body: body && { ...body, ...(attachments.length ? { attachments } : {}) },
      });
      queries.setQueryData(['support-ticket', id], next);
      void queries.invalidateQueries({ queryKey: ['support-tickets'] });
      return true;
    } catch (e) {
      setError((e as Error).message);
      return false;
    } finally {
      setBusy(false);
    }
  };
  return (
    <div className="support-page">
      {back}
      <div className="support-heading">
        <h2>{ticket.subject}</h2>
        <div className="support-badges">
          <StatusBadge ticket={ticket} />
          <Badge>{SUPPORT_TICKET_KINDS[ticket.kind]}</Badge>
          <span className="muted">#{ticket.id}</span>
        </div>
      </div>
      <ol className="support-thread" aria-label="Messages">
        {messages.map((message) => {
          const at = fileDate(message.at);
          return (
            <li key={message.id} className="support-message" data-author={message.author}>
              <div className="support-message-head">
                <strong>{message.author === 'STAFF' ? 'harbor0 support' : 'You'}</strong>
                <span className="muted" title={at.full}>
                  {at.date} {at.time}
                </span>
              </div>
              <p>{message.body}</p>
              <Screenshots items={message.attachments} />
            </li>
          );
        })}
      </ol>
      <Card className="support-reply">
        <form
          className="form"
          onSubmit={(event) => {
            event.preventDefault();
            if (!reply.trim() || busy) return;
            void act(`/v1/support/tickets/${encodeURIComponent(id)}/messages`, {
              body: reply,
            }).then((sent) => {
              if (!sent) return;
              setReply('');
              shots.clear();
            });
          }}
        >
          {error && <Alert tone="error">{error}</Alert>}
          <Field
            label="Write a reply"
            hint={done ? 'This request has ended. Writing on it opens it again.' : undefined}
          >
            <Textarea
              name="reply"
              rows={4}
              maxLength={SUPPORT_MESSAGE_MAX}
              value={reply}
              disabled={busy}
              onPaste={shots.onPaste}
              onChange={(event) => setReply(event.target.value)}
            />
          </Field>
          <ScreenshotPicker shots={shots} disabled={busy} />
          <div className="support-reply-actions">
            {!done && (
              <Button
                type="button"
                variant="outline"
                disabled={busy}
                onClick={() => void act(`/v1/support/tickets/${encodeURIComponent(id)}/close`)}
              >
                Close request
              </Button>
            )}
            <Button type="submit" disabled={busy || !reply.trim()}>
              {busy ? 'Sending…' : 'Send'}
            </Button>
          </div>
        </form>
      </Card>
    </div>
  );
}

function NewRequestDialog({ request, onClose }: { request: Transport; onClose: () => void }) {
  const router = useRouter();
  const queries = useQueryClient();
  const [kind, setKind] = useState<SupportTicketKind>('QUESTION');
  const [subject, setSubject] = useState('');
  const [body, setBody] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const shots = useScreenshots();
  return (
    <Dialog
      open
      onOpenChange={(value) => {
        if (!value && !busy) onClose();
      }}
      title="New support request"
      description="We’ll email you when we answer. You can also follow it on this page."
    >
      <form
        className="form"
        onSubmit={(event) => {
          event.preventDefault();
          if (busy) return;
          setBusy(true);
          setError('');
          uploadScreenshots(request, shots.picked)
            .then((attachments) =>
              request('/v1/support/tickets', {
                method: 'POST',
                body: {
                  kind,
                  subject,
                  body,
                  ...(attachments.length ? { attachments } : {}),
                  userAgent: navigator.userAgent.slice(0, 400),
                },
              }),
            )
            .then((created: SupportTicketDetail) => {
              queries.setQueryData(['support-ticket', created.ticket.id], created);
              void queries.invalidateQueries({ queryKey: ['support-tickets'] });
              onClose();
              router.push(supportHref(created.ticket.id));
            })
            .catch((e: Error) => {
              setError(e.message);
              setBusy(false);
            });
        }}
      >
        {error && <Alert tone="error">{error}</Alert>}
        <fieldset className="choice-list" data-compact="true">
          <legend>What is it about?</legend>
          {kinds.map((value) => (
            <label key={value} className="choice">
              <Radio
                name="kind"
                value={value}
                checked={kind === value}
                disabled={busy}
                onChange={() => setKind(value)}
              />
              <span>
                {SUPPORT_TICKET_KINDS[value]}
                <small className="muted support-kind-hint">{kindHints[value]}</small>
              </span>
            </label>
          ))}
        </fieldset>
        <Field label="Subject">
          <Input
            name="subject"
            maxLength={SUPPORT_SUBJECT_MAX}
            value={subject}
            disabled={busy}
            onChange={(event) => setSubject(event.target.value)}
            required
          />
        </Field>
        <Field
          label="Message"
          hint={
            kind === 'ISSUE'
              ? 'What you did, what happened, and what you expected. Say which app and device it was on.'
              : undefined
          }
        >
          <Textarea
            name="body"
            rows={6}
            maxLength={SUPPORT_MESSAGE_MAX}
            value={body}
            disabled={busy}
            onPaste={shots.onPaste}
            onChange={(event) => setBody(event.target.value)}
            required
          />
        </Field>
        <ScreenshotPicker shots={shots} disabled={busy} />
        <DialogActions>
          <Button type="button" variant="outline" disabled={busy} onClick={onClose}>
            Cancel
          </Button>
          <Button type="submit" disabled={busy || !subject.trim() || !body.trim()}>
            {busy ? 'Sending…' : 'Send request'}
          </Button>
        </DialogActions>
      </form>
    </Dialog>
  );
}
