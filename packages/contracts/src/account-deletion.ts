import { z } from 'zod';

/** Why the holder is deleting their account, in the order the apps list them. */
export const ACCOUNT_DELETION_REASONS = {
  TOO_EXPENSIVE: 'It costs too much',
  NOT_ENOUGH_STORAGE: 'I need more free storage',
  MISSING_FEATURE: 'A feature I need is missing',
  HARD_TO_USE: 'It’s hard to use',
  UNRELIABLE: 'Uploads, sync or backups didn’t work well',
  SWITCHING: 'I’m moving to another service',
  NOT_NEEDED: 'I don’t need it anymore',
  OTHER: 'Something else',
} as const;
export type AccountDeletionReasonCode = keyof typeof ACCOUNT_DELETION_REASONS;
export const ACCOUNT_DELETION_DETAIL_MAX = 1000;

/** `detail` is free text; it is required for OTHER and optional otherwise. */
export const accountDeletionReasonSchema = z
  .object({
    code: z.enum(
      Object.keys(ACCOUNT_DELETION_REASONS) as [
        AccountDeletionReasonCode,
        ...AccountDeletionReasonCode[],
      ],
    ),
    detail: z.string().trim().max(ACCOUNT_DELETION_DETAIL_MAX).optional(),
  })
  .strict()
  .refine((r) => r.code !== 'OTHER' || !!r.detail, {
    message: 'Tell us a little about why you’re leaving.',
    path: ['detail'],
  });
export type AccountDeletionReason = z.infer<typeof accountDeletionReasonSchema>;
