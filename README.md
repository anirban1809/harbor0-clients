# harbor0 clients

The apps people use for [harbor0](https://harbor0.com): the web app (app.harbor0.com), the desktop app
(macOS, Windows, Linux) and the iOS and Android apps. The backend (API, infrastructure, the landing
site and the public link pages) lives in the private repository `anirban1809/harbor0-src`; the staff
console in `anirban1809/harbor0-admin`.

| Path                  | What                                                                          |
| --------------------- | ----------------------------------------------------------------------------- |
| `apps/web`            | Next.js 16 web app, a static export served from S3 behind CloudFront          |
| `apps/desktop`        | Electron desktop app: sync, backups, local WebDAV drive                       |
| `apps/ios`            | SwiftUI app (`Harbor0.xcodeproj`)                                             |
| `apps/android`        | Kotlin/Compose app                                                            |
| `packages/contracts`  | Schemas and types shared with the backend (a copy; see below)                 |
| `packages/api-client` | API client, session proxy, E2EE and previews used by the web and desktop apps |
| `infra/web.ts`        | The web app's hosting stack (`HarborWeb`)                                     |
| `infra/github.ts`     | The AWS role GitHub Actions deploys the web app with                          |
| `scripts/`            | Deploys, desktop releases and UI checks                                       |

## Development

Node 24. The apps talk to a local copy of the backend, so clone `harbor0-src` next to this
repository (as `../harbor0-src`, or anywhere with `HARBOR_BACKEND_DIR` set) and set it up as its README
describes (`npm install`, `docker compose up -d`, `cp .env.example .env`).

```bash
npm install
```

```bash
npm run backend:link
```

```bash
npm run local:setup
```

```bash
npm run dev:api
```

```bash
npm run dev:web
```

`backend:link` links `./backend` and `apps/backend` (both git-ignored) to that checkout. The
desktop integration tests and a few checks run the backend in-process from there; without the link
`npm test` skips them and `npm run typecheck` leaves them out. The devDependencies include the
backend's runtime packages for the same reason.

`npm run check` runs lint, typecheck and the unit tests. `npm run dev:desktop` starts the desktop app
against the local API.

## Code shared with the backend

The backend owns `packages/contracts` and the OpenAPI document; this repository keeps copies. After
pulling backend changes the apps need:

```bash
npm run sync:backend
```

It copies `packages/contracts` and `docs/openapi.json` from the backend checkout and regenerates
`packages/api-client/src/generated.ts`. `npm run sync:backend -- --check` fails when the copies are
out of date; CI runs it when it can read the backend. This repository owns `packages/api-client`;
the backend keeps a copy for its Lambda's session proxy and its sites, and the sync script reports
where that copy differs.

## Deploying

The web app deploys to production from `main` once the Validate workflow passes
(`.github/workflows/deploy-production.yml`), and to staging by hand from the `staging` branch. The
deploy reads the API URL from the backend's live `HarborStorage` stack, so deploy the backend first
when the two change together.

One-time setup:

1. Deploy the role: `npx cdk deploy HarborClientsGitHubProduction --app 'npx tsx infra/github.ts'`
   (and `HARBOR_ENV=staging … HarborClientsGitHubStaging` for staging).
2. Set the repository variables `PRODUCTION_AWS_ROLE_ARN` (its `DeployRoleArn` output),
   `PRODUCTION_WEB_ORIGIN` and `PRODUCTION_WEB_CERT_ARN`; for staging, the `STAGING_` versions
   plus `STAGING_WEB_DOMAIN`.
3. Optionally add a `BACKEND_REPO_TOKEN` secret (read access to `harbor0-src`), which turns on the
   contracts check and the integration job in CI.

From a machine with AWS credentials, `npm run cloud:deploy-web` (or `:assets` for files only)
does the same with `.env.cloud` (`AWS_REGION`, `WEB_ORIGIN`, `WEB_CERT_ARN`).

## Desktop releases

`npm run release:desktop` builds and uploads the installers to harbor0.com/downloads, tags the
release here and updates the landing site's download links in the backend checkout, committing and
pushing them on its `main`. See the comment at the top of `scripts/release-desktop.ts`.
