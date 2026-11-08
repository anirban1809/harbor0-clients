// Turns on, for every account in the disposable test table, the flags the iOS tests exercise
// (or those named in TEST_FLAGS, comma-separated).
import { DynamoRepository, transact } from '../backend/src/repository';
import type { FlagKey } from '@harbor/contracts';
import { FLAG_PK, emptyFlag } from '../backend/src/flags';

const repo = new DynamoRepository(process.env.TABLE_NAME!, process.env.DYNAMODB_ENDPOINT!);
const flags = (process.env.TEST_FLAGS ?? 'e2ee').split(',').filter(Boolean);
await transact(repo, async (tx) => {
  for (const flag of flags) await tx.put(FLAG_PK, flag, { ...emptyFlag(flag as FlagKey), mode: 'ON' });
});
