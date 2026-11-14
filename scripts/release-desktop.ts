// Releases the Mac, Windows and Linux desktop apps to harbor0.com/downloads (an S3 bucket behind the
// landing site's CloudFront, stack HarborLanding) and points the landing site's downloads at them.
//
//   npm run release:desktop                 next patch version
//   npm run release:desktop -- minor        or major, or an exact version such as 0.2.0
//   npm run release:desktop -- --dry-run    build and update the files, but commit and publish nothing
//   npm run release:desktop -- --test       a test build of the next patch version, such as 0.1.9-test
//                                           (then 0.1.9-test.2, …); takes minor, major or x.y.z too
//   npm run release:desktop -- --test --linux  a test build that also has the Linux packages
//   npm run release:desktop -- --no-windows  leave Windows out: its apps and download stay as they are
//
// Releases always have Linux packages, built in Docker: AppImage, deb and rpm for x64, and AppImage
// and deb for arm64. Linux apps don't update themselves; people install new versions by hand.
//
// The installers are built from a clean worktree of HEAD, so uncommitted work in this checkout never
// ships. Both come from this Mac: an Apple silicon DMG and an x64 NSIS installer (which also runs on
// Windows on Arm). Installed apps find the new version in the update feeds
// (downloads/latest.json, and downloads/releases.json for test builds) and update themselves. The
// version bump is committed and tagged here, the tag is pushed, the installers and feeds are
// uploaded, and then main is pushed. The landing site lives in the backend repository
// (anirban1809/harbor0-src, found by scripts/backend-dir.ts): its new download links are committed
// on that checkout's main and pushed, which deploys the landing site once its Validate passes.
// Releases are not published on GitHub; the downloads and feeds are on S3.
//
// A test build is for trying new features before they ship. It can be released from any branch,
// and is a pre-release in releases.json only: the landing site, main and the version in package.json are left
// alone (the version bump is committed only on the tag), and the backend checkout isn't needed. Production apps never update to it, since
// they only look at the latest full release; test apps update to newer test builds and to the
// production release that follows them. Install the first test build by hand from its download link.
import { execFileSync, spawn } from 'node:child_process';
import { createHash } from 'node:crypto';
import { createReadStream, existsSync } from 'node:fs';
import { copyFile, mkdir, mkdtemp, readFile, rm, stat, symlink, writeFile } from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import { backendDir } from './backend-dir';

const root = process.cwd();
const args = process.argv.slice(2);
const dryRun = args.includes('--dry-run');
const testBuild = args.includes('--test');
const linux = !testBuild || args.includes('--linux');
// Windows apps find no installer for their platform in the feed, so they stay on their version.
const windows = !args.includes('--no-windows');
const bump = args.find((arg) => !arg.startsWith('--')) ?? 'patch';
// The notarytool keychain profile electron-builder notarizes the Mac app with. Create it once with
// `xcrun notarytool store-credentials harbor0-notary --key <AuthKey.p8> --key-id … --issuer …`.
process.env.APPLE_KEYCHAIN_PROFILE ??= 'harbor0-notary';

const git = (...command: string[]) =>
  execFileSync('git', command, { cwd: root, encoding: 'utf8' }).trim();
const backend = testBuild ? undefined : backendDir();
if (!testBuild && !backend)
  throw new Error(
    'A release updates the landing site in harbor0-src: clone it next to this repository or set HARBOR_BACKEND_DIR.',
  );
const backendGit = (...command: string[]) =>
  execFileSync('git', command, { cwd: backend, encoding: 'utf8' }).trim();
const aws = (...command: string[]) =>
  execFileSync('aws', [...command, '--region', region], { cwd: root, encoding: 'utf8' }).trim();
async function run(command: string, commandArgs: string[], cwd: string) {
  await new Promise<void>((resolve, reject) => {
    const child = spawn(command, commandArgs, {
      cwd,
      stdio: 'inherit',
      env: process.env,
    });
    child.on('error', reject);
    child.on('exit', (code) =>
      code === 0 ? resolve() : reject(new Error(`${command} exited ${code}`)),
    );
  });
}

type Installer = { name: string; file: string; bytes: number; sha256: string; url: string };
const contentType = (name: string) =>
  name.endsWith('.dmg')
    ? 'application/x-apple-diskimage'
    : name.endsWith('.exe')
      ? 'application/vnd.microsoft.portable-executable'
      : name.endsWith('.deb')
        ? 'application/vnd.debian.binary-package'
        : name.endsWith('.rpm')
          ? 'application/x-rpm'
          : 'application/octet-stream';

// Uploads the installers, then the feeds that point at them, so a feed never names a missing file.
// The feeds use GitHub's release shape, which the desktop updater has always read. Re-issuing a
// version in place overwrites its installers; the invalidation makes that visible at once.
async function publishRelease(installers: Installer[], prerelease: boolean) {
  for (const { name, file, sha256 } of installers)
    for (let attempt = 1; ; attempt++) {
      try {
        execFileSync(
          'aws',
          [
            's3',
            'cp',
            file,
            `s3://${site.DesktopReleasesBucketName}/downloads/${name}`,
            '--content-type',
            contentType(name),
            '--content-disposition',
            `attachment; filename="${name}"`,
            '--cache-control',
            'public,max-age=86400',
            '--metadata',
            `sha256=${sha256},version=${version}`,
            '--region',
            region,
            '--only-show-errors',
          ],
          { cwd: root, stdio: 'inherit' },
        );
        break;
      } catch (error) {
        if (attempt === 5) throw error;
        console.log(`Uploading ${name} failed; retrying (${attempt} of 4).`);
      }
    }
  let releases: { tag_name: string }[] = [];
  try {
    releases = JSON.parse(
      aws('s3', 'cp', `s3://${site.DesktopReleasesBucketName}/downloads/releases.json`, '-'),
    );
  } catch {
    // The first release published here.
  }
  const entry = {
    tag_name: tag,
    html_url: 'https://harbor0.com/apps',
    draft: false,
    prerelease,
    published_at: new Date().toISOString(),
    assets: installers.map(({ name, bytes, sha256, url }) => ({
      name,
      size: bytes,
      digest: `sha256:${sha256}`,
      browser_download_url: url,
    })),
  };
  releases = [entry, ...releases.filter((existing) => existing.tag_name !== tag)].slice(0, 30);
  const feeds: [string, unknown][] = [['releases.json', releases]];
  if (!prerelease) feeds.push(['latest.json', entry]);
  const directory = await mkdtemp(path.join(os.tmpdir(), 'harbor0-feeds-'));
  try {
    for (const [name, body] of feeds) {
      const file = path.join(directory, name);
      await writeFile(file, JSON.stringify(body, null, 2) + '\n');
      aws(
        's3',
        'cp',
        file,
        `s3://${site.DesktopReleasesBucketName}/downloads/${name}`,
        '--content-type',
        'application/json',
        '--cache-control',
        'no-cache,max-age=0,must-revalidate',
        '--only-show-errors',
      );
    }
  } finally {
    await rm(directory, { recursive: true, force: true });
  }
  aws(
    'cloudfront',
    'create-invalidation',
    '--distribution-id',
    site.LandingDistributionId,
    '--paths',
    '/downloads/*',
  );
}

const downloads = 'https://harbor0.com/downloads';
const region = process.env.AWS_REGION ?? 'us-east-1';
// Read from the live stack: production deploys run in CI, so .cloud/landing-outputs.json can be stale.
const site = Object.fromEntries(
  (
    JSON.parse(
      aws('cloudformation', 'describe-stacks', '--stack-name', 'HarborLanding', '--output', 'json'),
    ).Stacks[0].Outputs as { OutputKey: string; OutputValue: string }[]
  ).map(({ OutputKey, OutputValue }) => [OutputKey, OutputValue]),
) as { LandingDistributionId: string; DesktopReleasesBucketName?: string };
if (!site.DesktopReleasesBucketName)
  throw new Error('The HarborLanding stack has no DesktopReleasesBucketName output.');

const desktopPackage = 'apps/desktop/package.json';
const lockfile = 'package-lock.json';
const landingPage = 'apps/landing/app/apps/page.tsx';
const released = [desktopPackage, lockfile];

// --- Checks -----------------------------------------------------------------------------------
if (!testBuild) {
  if (git('rev-parse', '--abbrev-ref', 'HEAD') !== 'main') throw new Error('Release from main.');
  const dirty = git('status', '--porcelain', '--', ...released);
  if (dirty) throw new Error(`Commit or discard the changes to these files first:\n${dirty}`);
  if (backendGit('rev-parse', '--abbrev-ref', 'HEAD') !== 'main')
    throw new Error(`Switch ${backend} to main: the landing site deploys from there.`);
  const landingDirty = backendGit('status', '--porcelain', '--', landingPage);
  if (landingDirty)
    throw new Error(`Commit or discard the changes to ${landingPage} in ${backend} first.`);
}
if (!dryRun) git('fetch', 'origin', '--tags');
if (!dryRun && !testBuild) {
  // Local commits not yet pushed are fine; they ship with the release.
  for (const [run, where] of [
    [git, 'this checkout'],
    [backendGit, backend],
  ] as const) {
    run('fetch', 'origin', 'main');
    try {
      run('merge-base', '--is-ancestor', 'origin/main', 'HEAD');
    } catch {
      throw new Error(`origin/main has commits ${where} lacks; pull first.`);
    }
  }
}

const current = JSON.parse(await readFile(desktopPackage, 'utf8')).version as string;
const release = (() => {
  if (/^\d+\.\d+\.\d+$/.test(bump)) return bump;
  const [major, minor, patch] = current.split('.').map(Number);
  if (bump === 'major') return `${major + 1}.0.0`;
  if (bump === 'minor') return `${major}.${minor + 1}.0`;
  if (bump === 'patch') return `${major}.${minor}.${patch + 1}`;
  throw new Error(`Unknown version "${bump}": use patch, minor, major or x.y.z.`);
})();
// Test builds of one release are numbered: x.y.z-test, then x.y.z-test.2, x.y.z-test.3, …
const version = (() => {
  if (!testBuild) return release;
  if (git('tag', '--list', `v${release}`)) throw new Error(`v${release} is already released.`);
  const builds = git('tag', '--list', `v${release}-test*`)
    .split('\n')
    .map((tag) => /^v\d+\.\d+\.\d+-test(?:\.(\d+))?$/.exec(tag))
    .filter((match) => match !== null)
    .map((match) => Number(match[1] ?? 1));
  return builds.length ? `${release}-test.${Math.max(...builds) + 1}` : `${release}-test`;
})();
const tag = `v${version}`;
if (git('tag', '--list', tag)) throw new Error(`${tag} already exists.`);
console.log(
  `Releasing harbor0 desktop ${current} → ${version}${testBuild ? ' (test build)' : ''}${dryRun ? ' (dry run)' : ''}`,
);

async function setVersion(directory: string) {
  const file = path.join(directory, desktopPackage);
  const pkg = JSON.parse(await readFile(file, 'utf8'));
  pkg.version = version;
  await writeFile(file, JSON.stringify(pkg, null, 2) + '\n');
  const lockPath = path.join(directory, lockfile);
  const lock = JSON.parse(await readFile(lockPath, 'utf8'));
  lock.packages['apps/desktop'].version = version;
  await writeFile(lockPath, JSON.stringify(lock, null, 2) + '\n');
}

// --- Build from a clean worktree of HEAD ------------------------------------------------------
const targets = [
  {
    key: 'mac',
    platform: 'macOS',
    architecture: 'arm64',
    name: `harbor0-${version}-mac-arm64.dmg`,
    build: ['--mac', 'dmg', '--arm64'],
  },
  ...(windows
    ? [
        {
          key: 'windows',
          platform: 'Windows',
          architecture: 'x64',
          name: `harbor0-${version}-win-x64.exe`,
          build: ['--win', 'nsis', '--x64'],
        },
      ]
    : []),
  // One Linux build per architecture makes all of its packages.
  ...(linux
    ? [
        {
          key: 'linuxAppImage',
          platform: 'Linux (AppImage)',
          architecture: 'x64',
          name: `harbor0-${version}-linux-x86_64.AppImage`,
          build: ['--linux', 'AppImage', 'deb', 'rpm', '--x64'],
        },
        {
          key: 'linuxDeb',
          platform: 'Linux (deb)',
          architecture: 'x64',
          name: `harbor0-${version}-linux-amd64.deb`,
          build: null,
        },
        {
          key: 'linuxRpm',
          platform: 'Linux (rpm)',
          architecture: 'x64',
          name: `harbor0-${version}-linux-x86_64.rpm`,
          build: null,
        },
        // arm64 is for Ubuntu and Debian on ARM machines, such as VMs on Apple silicon Macs.
        {
          key: 'linuxAppImageArm64',
          platform: 'Linux (AppImage)',
          architecture: 'arm64',
          name: `harbor0-${version}-linux-arm64.AppImage`,
          build: ['--linux', 'AppImage', 'deb', '--arm64'],
        },
        {
          key: 'linuxDebArm64',
          platform: 'Linux (deb)',
          architecture: 'arm64',
          name: `harbor0-${version}-linux-arm64.deb`,
          build: null,
        },
      ]
    : []),
];
const worktree = await mkdtemp(path.join(os.tmpdir(), 'harbor0-release-'));
try {
  git('worktree', 'add', '--detach', worktree, 'HEAD');
  // Reuse the installed dependencies, and the deploy outputs the build reads its API URL from.
  await symlink(path.join(root, 'node_modules'), path.join(worktree, 'node_modules'));
  // npm makes the desktop's own node_modules only when a dependency can't be hoisted; a link to a
  // missing one breaks electron-builder.
  if (existsSync(path.join(root, 'apps/desktop/node_modules')))
    await symlink(
      path.join(root, 'apps/desktop/node_modules'),
      path.join(worktree, 'apps/desktop/node_modules'),
    );
  // The API URL comes from the backend's live stack; the web URL from the last web deploy here,
  // or its live stack.
  await mkdir(path.join(worktree, '.cloud'), { recursive: true });
  await copyFile(
    path.join(root, '.cloud/web-outputs.json'),
    path.join(worktree, '.cloud/web-outputs.json'),
  ).catch(() => {});
  await setVersion(worktree);
  await mkdir(path.join(root, 'apps/desktop/release'), { recursive: true });
  for (const target of targets) {
    if (target.build)
      await run('npx', ['tsx', 'scripts/package-desktop-release.ts', ...target.build], worktree);
    // A bundle whose signature doesn't seal its resources is reported as "damaged" once downloaded,
    // and one Gatekeeper won't accept (not Developer ID signed, or not notarized and stapled) can't
    // be opened at all, so neither ships.
    // electron-builder notarizes only the app, so the disk image is notarized and stapled here,
    // before it is hashed.
    if (target.key === 'mac') {
      const app = 'apps/desktop/release/mac-arm64/harbor0.app';
      const dmg = `apps/desktop/release/${target.name}`;
      const profile = process.env.APPLE_KEYCHAIN_PROFILE!;
      for (const check of [
        ['codesign', '--verify', '--deep', '--strict', app],
        ['spctl', '--assess', '--type', 'execute', '--verbose', app],
        ['xcrun', 'stapler', 'validate', app],
        ['xcrun', 'notarytool', 'submit', dmg, '--keychain-profile', profile, '--wait'],
        ['xcrun', 'stapler', 'staple', dmg],
        ['spctl', '--assess', '--type', 'open', '--context', 'context:primary-signature', dmg],
      ])
        execFileSync(check[0], check.slice(1), { cwd: worktree, stdio: 'inherit' });
    }
    await copyFile(
      path.join(worktree, 'apps/desktop/release', target.name),
      path.join(root, 'apps/desktop/release', target.name),
    );
  }
  // A test build's version lives only on its tag: commit the bump in the worktree, off any branch.
  if (testBuild && !dryRun) {
    execFileSync('git', ['add', desktopPackage, lockfile], { cwd: worktree });
    execFileSync('git', ['commit', '-m', `chore(desktop): test build ${tag}`], { cwd: worktree });
    execFileSync('git', ['tag', '-a', tag, '-m', `harbor0 desktop test build ${version}`], {
      cwd: worktree,
    });
  }
} finally {
  git('worktree', 'remove', '--force', worktree);
  await rm(worktree, { recursive: true, force: true });
}

const installers = [];
for (const target of targets) {
  const file = path.join(root, 'apps/desktop/release', target.name);
  const bytes = (await stat(file)).size;
  const hasher = createHash('sha256');
  for await (const chunk of createReadStream(file)) hasher.update(chunk);
  installers.push({
    ...target,
    file,
    bytes,
    sha256: hasher.digest('hex'),
    url: `${downloads}/${target.name}`,
  });
}

if (testBuild) {
  if (dryRun) {
    console.log(`\nDry run: built ${installers.map((i) => i.file).join(' and ')}.`);
    console.log('Nothing was tagged or published.');
    process.exit(0);
  }
  git('push', 'origin', tag);
  await publishRelease(installers, true);
  for (const { url } of installers) {
    const response = await fetch(url, { method: 'HEAD', redirect: 'follow' });
    if (!response.ok) throw new Error(`The release download ${url} returned ${response.status}.`);
  }
  console.log(`\nReleased test build ${tag}.`);
  for (const { platform, url } of installers) console.log(`${platform}: ${url}`);
  process.exit(0);
}

// --- Point the landing site at the new installers ---------------------------------------------
await setVersion(root);
const landingFile = path.join(backend!, landingPage);
let page = await readFile(landingFile, 'utf8');
for (const { key, bytes, url, sha256 } of installers) {
  const updated = page.replace(
    new RegExp(`const ${key}: Download \\| null = (?:null|\\{[\\s\\S]*?\\n\\});`),
    `const ${key}: Download | null = {\n  version: '${version}',\n  size: '${Math.round(bytes / 1e6)} MB',\n  url: '${url}',\n  sha256: '${sha256}',\n};`,
  );
  if (updated === page)
    throw new Error(`Could not find the \`${key}\` download in ${landingPage}.`);
  page = updated;
}
await writeFile(landingFile, page);
const manifest = {
  version,
  publishedAt: new Date().toISOString(),
  installers: installers.map(({ platform, architecture, bytes, sha256, url }) => ({
    platform,
    architecture,
    signed: false,
    sha256,
    bytes,
    url,
  })),
};

if (dryRun) {
  console.log(
    `\nDry run: built ${installers.map((i) => i.file).join(' and ')} and updated ${released.join(', ')} and ${landingFile}.`,
  );
  console.log(
    `Nothing was committed or published. Revert with: git checkout -- ${released.join(' ')} && git -C ${backend} checkout -- ${landingPage}`,
  );
  process.exit(0);
}

// --- Commit, tag, release, deploy -------------------------------------------------------------
git('commit', '-m', `chore(desktop): release ${tag}`, '--', ...released);
git('tag', '-a', tag, '-m', `harbor0 desktop ${version}`);
// The tag goes first so the download exists before the landing site links to it.
git('push', 'origin', tag);
await publishRelease(installers, false);
for (const { url } of installers) {
  const response = await fetch(url, { method: 'HEAD', redirect: 'follow' });
  if (!response.ok) throw new Error(`The release download ${url} returned ${response.status}.`);
}
git('push', 'origin', 'main');
backendGit('commit', '-m', `chore(landing): link desktop ${tag}`, '--', landingPage);
backendGit('push', 'origin', 'main');
await mkdir('.cloud', { recursive: true });
await writeFile('.cloud/desktop-release.json', JSON.stringify(manifest, null, 2) + '\n');

console.log(`\nReleased ${tag}.`);
for (const { platform, url } of installers) console.log(`${platform}: ${url}`);
console.log(`The landing site deploys from ${backend}'s main once its Validate passes.`);
