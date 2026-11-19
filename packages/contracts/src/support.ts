import { z } from 'zod';

/**
 * Support requests people open from the web app: a question, a problem with harbor0, or a
 * feature they'd like. Each is a thread between the account holder and harbor0 support, who
 * answer from the management console. Both sides are emailed when the other one writes or the
 * status changes.
 */

/** What a request is about, in the order the app offers them. */
export const SUPPORT_TICKET_KINDS = {
  QUESTION: 'General question',
  ISSUE: 'Problem with harbor0',
  FEATURE: 'Feature request',
} as const;
export type SupportTicketKind = keyof typeof SUPPORT_TICKET_KINDS;
export const supportTicketKind = z.enum(
  Object.keys(SUPPORT_TICKET_KINDS) as [SupportTicketKind, ...SupportTicketKind[]],
);

export const SUPPORT_TICKET_STATUSES = {
  OPEN: 'Open',
  IN_PROGRESS: 'In progress',
  RESOLVED: 'Resolved',
  CLOSED: 'Closed',
} as const;
export type SupportTicketStatus = keyof typeof SUPPORT_TICKET_STATUSES;
export const supportTicketStatus = z.enum(
  Object.keys(SUPPORT_TICKET_STATUSES) as [SupportTicketStatus, ...SupportTicketStatus[]],
);
/** Statuses that end a request; writing to one opens it again. */
export const supportTicketDone = (status: SupportTicketStatus) =>
  status === 'RESOLVED' || status === 'CLOSED';

export const SUPPORT_SUBJECT_MAX = 140;
export const SUPPORT_MESSAGE_MAX = 5000;
/** Requests one account may have open (not resolved or closed) at once. */
export const SUPPORT_OPEN_TICKETS_MAX = 10;
/** Requests one account may open in a day, since each one emails support. */
export const SUPPORT_TICKETS_PER_DAY = 10;
/** Messages one account may add to its requests in a day. */
export const SUPPORT_MESSAGES_PER_DAY = 50;

/** Screenshots: images only, a few per message. */
export const SUPPORT_ATTACHMENT_TYPES = [
  'image/png',
  'image/jpeg',
  'image/webp',
  'image/gif',
] as const;
export const SUPPORT_ATTACHMENTS_MAX = 5;
export const SUPPORT_ATTACHMENT_MAX_BYTES = 10 * 1024 * 1024;
/** Screenshots one account may upload in a day. */
export const SUPPORT_ATTACHMENTS_PER_DAY = 50;
export const supportAttachmentType = z.enum(SUPPORT_ATTACHMENT_TYPES);

export const supportAttachmentSchema = z.object({
  id: z.string(),
  name: z.string(),
  contentType: supportAttachmentType,
  sizeBytes: z.number(),
  /** Opens the image for about an hour; read the request again for a fresh one. */
  url: z.string(),
});
export type SupportAttachment = z.infer<typeof supportAttachmentSchema>;
export const supportAttachmentInput = z
  .object({
    name: z.string().trim().min(1).max(200),
    contentType: supportAttachmentType,
    sizeBytes: z
      .number()
      .int()
      .min(1)
      .max(SUPPORT_ATTACHMENT_MAX_BYTES, 'Screenshots can be up to 10 MB.'),
  })
  .strict();
/** Where the browser sends the image's bytes (one PUT), then names `id` when it writes. */
export const supportAttachmentUploadSchema = z.object({ id: z.string(), uploadUrl: z.string() });
const attachments = z
  .array(z.string().regex(/^[0-9A-Z]{16}$/))
  .max(SUPPORT_ATTACHMENTS_MAX, `Add up to ${SUPPORT_ATTACHMENTS_MAX} screenshots.`)
  .optional();

export const supportAuthor = z.enum(['USER', 'STAFF']);
export type SupportAuthor = z.infer<typeof supportAuthor>;

export const supportMessageSchema = z.object({
  id: z.string(),
  at: z.string(),
  author: supportAuthor,
  body: z.string(),
  attachments: z.array(supportAttachmentSchema),
});
export type SupportMessage = z.infer<typeof supportMessageSchema>;

export const supportTicketSchema = z.object({
  /** Short and readable, shown as the request's number. */
  id: z.string(),
  kind: supportTicketKind,
  subject: z.string(),
  status: supportTicketStatus,
  createdAt: z.string(),
  updatedAt: z.string(),
  /** Who wrote last, so the app can show when support has answered. */
  lastMessageBy: supportAuthor,
  messageCount: z.number(),
});
export type SupportTicket = z.infer<typeof supportTicketSchema>;
export const supportTicketListSchema = z.object({ items: z.array(supportTicketSchema) });
export const supportTicketDetailSchema = z.object({
  ticket: supportTicketSchema,
  /** Oldest first; the first one is the request itself. */
  messages: z.array(supportMessageSchema),
});
export type SupportTicketDetail = z.infer<typeof supportTicketDetailSchema>;

const subject = z.string().trim().min(1, 'Add a subject.').max(SUPPORT_SUBJECT_MAX);
const body = z
  .string()
  .trim()
  .min(1, 'Write a message.')
  .max(SUPPORT_MESSAGE_MAX, `Keep it under ${SUPPORT_MESSAGE_MAX} characters.`);
export const supportTicketInput = z
  .object({
    kind: supportTicketKind,
    subject,
    body,
    /** Uploaded screenshots to add, by the ids their uploads were given. */
    attachments,
    /** The browser the request was sent from, to help with problems; shown only to support. */
    userAgent: z.string().max(400).optional(),
  })
  .strict();
export type SupportTicketInput = z.infer<typeof supportTicketInput>;
export const supportMessageInput = z.object({ body, attachments }).strict();
