import { execFileSync } from 'node:child_process';
import { readFile } from 'node:fs/promises';
// One switch for every deploy script and CDK app: HARBOR_ENV=staging targets a fully separate
// copy of the infrastructure (its own stacks, distributions and buckets). Unset means production,
// whose stack names and resources are unchanged.
const name = process.env.HARBOR_ENV ?? 'production';
if (name !== 'production' && name !== 'staging')
  throw new Error(`HARBOR_ENV must be "production" or "staging", not "${name}".`);
const production = name === 'production';
const suffix = production ? '' : 'Staging';

export const harborEnv = {
  name,
  production,
  stacks: {
    /** The backend, deployed from anirban1809/harbor0-src. */
    storage: `HarborStorage${suffix}`,
    web: `HarborWeb${suffix}`,
  },
  region: process.env.AWS_REGION ?? 'us-east-1',
  /** Git-ignored settings and credentials for this environment. */
  envFile: production ? '.env.cloud' : '.env.staging',
  /** Git-ignored deploy outputs; staging keeps its own so the two never overwrite each other. */
  cloudDir: production ? '.cloud' : '.cloud/staging',
  appDomain: process.env.WEB_DOMAIN || (production ? 'app.harbor0.com' : 'staging.harbor0.com'),
  /** The branch this environment is built from: production deploys automatically from main. */
  branch: production ? 'main' : 'staging',
};

const outputFiles = { web: 'web-outputs.json' };
export const outputsPath = (kind: keyof typeof outputFiles) =>
  `${harborEnv.cloudDir}/${outputFiles[kind]}`;

/** A deployed stack's outputs, read from CloudFormation. */
export function liveOutputs(stack: string): Record<string, string> {
  const described = JSON.parse(
    execFileSync(
      'aws',
      [
        'cloudformation',
        'describe-stacks',
        '--stack-name',
        stack,
        '--region',
        harborEnv.region,
        '--output',
        'json',
      ],
      { encoding: 'utf8' },
    ),
  ) as { Stacks: { Outputs?: { OutputKey: string; OutputValue: string }[] }[] };
  return Object.fromEntries(
    (described.Stacks[0].Outputs ?? []).map(({ OutputKey, OutputValue }) => [
      OutputKey,
      OutputValue,
    ]),
  );
}

/**
 * The backend deploys from harbor0-src, so its outputs always come from the live stack. The web
 * stack's come from the file the last deploy here wrote, or from the live stack without one.
 */
export async function readOutputs(kind: 'storage' | keyof typeof outputFiles) {
  if (kind === 'storage') return liveOutputs(harborEnv.stacks.storage);
  try {
    return JSON.parse(await readFile(outputsPath(kind), 'utf8'))[harborEnv.stacks[kind]];
  } catch (error) {
    if ((error as NodeJS.ErrnoException).code !== 'ENOENT') throw error;
    return liveOutputs(harborEnv.stacks[kind]);
  }
}

/**
 * Staging is built from the `staging` branch only, so what runs there is what was pushed.
 * Production's automated deploys run from main in CI; local production deploys stay unrestricted.
 */
export function assertDeployBranch() {
  if (harborEnv.production || process.env.HARBOR_ALLOW_ANY_BRANCH === 'true') return;
  const branch =
    process.env.GITHUB_REF_NAME ||
    execFileSync('git', ['rev-parse', '--abbrev-ref', 'HEAD'], { encoding: 'utf8' }).trim();
  if (branch !== harborEnv.branch)
    throw new Error(
      `${harborEnv.name} deploys from the "${harborEnv.branch}" branch; this checkout is on "${branch}". ` +
        'Set HARBOR_ALLOW_ANY_BRANCH=true to override deliberately.',
    );
}
