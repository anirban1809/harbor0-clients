import { App, Stack, CfnOutput, Duration, aws_iam as iam } from 'aws-cdk-lib';
import { harborEnv } from './environment';
// The role GitHub Actions assumes to deploy this repository's part of one environment: the web
// app's hosting stack and files. Only workflow runs on that environment's branch of this
// repository can assume it (OIDC, no stored AWS keys): production ← main (automatic, after
// Validate passes), staging ← staging (manual runs only). The backend and its own role deploy from
// anirban1809/harbor0-src. Deploy once per environment, by hand:
//   npx cdk deploy HarborClientsGitHubProduction --app 'npx tsx infra/github.ts'
//   HARBOR_ENV=staging npx cdk deploy HarborClientsGitHubStaging --app 'npx tsx infra/github.ts'
// then set the DeployRoleArn output as the PRODUCTION_/STAGING_AWS_ROLE_ARN repository variable.
const repository = 'anirban1809/harbor0-clients';
// GitHub names the repository either plainly or, with immutable subjects turned on, by
// login@id/name@id, which a re-created repository with the same name can't match. Both are
// accepted until the repository's subject format is settled; a rename needs this updated.
const subjectRepositories = [repository, 'anirban1809@43294429/harbor0-clients@REPOSITORY_ID'];
const branch = harborEnv.branch;
const label = harborEnv.production ? 'Production' : 'Staging';
const app = new App();
const stack = new Stack(app, `HarborClientsGitHub${label}`);
const provider = iam.OpenIdConnectProvider.fromOpenIdConnectProviderArn(
  stack,
  'GitHub',
  `arn:${stack.partition}:iam::${stack.account}:oidc-provider/token.actions.githubusercontent.com`,
);
const role = new iam.Role(stack, `${label}Deploy`, {
  description: `GitHub Actions deploys of the harbor0 ${harborEnv.name} web app from ${repository}@${branch}`,
  maxSessionDuration: Duration.hours(1),
  assumedBy: new iam.WebIdentityPrincipal(provider.openIdConnectProviderArn, {
    StringEquals: {
      'token.actions.githubusercontent.com:aud': 'sts.amazonaws.com',
      'token.actions.githubusercontent.com:sub': subjectRepositories.map(
        (subject) => `repo:${subject}:ref:refs/heads/${branch}`,
      ),
    },
  }),
});
// CDK deploys through the bootstrap roles (asset publishing, CloudFormation, lookups).
role.addToPolicy(
  new iam.PolicyStatement({
    actions: ['sts:AssumeRole', 'sts:TagSession'],
    resources: [`arn:${stack.partition}:iam::${stack.account}:role/cdk-hnb659fds-*`],
  }),
);
// The deploy reads the backend's API URL from its stack's outputs.
role.addToPolicy(
  new iam.PolicyStatement({
    actions: ['cloudformation:DescribeStacks'],
    resources: [harborEnv.stacks.storage, harborEnv.stacks.web].map(
      (name) =>
        `arn:${stack.partition}:cloudformation:${stack.region}:${stack.account}:stack/${name}/*`,
    ),
  }),
);
// Static uploads go only to this environment's web bucket (CDK names it after its stack, so
// `harborweb-*` never matches staging's `harborwebstaging-*`).
const bucket = harborEnv.production ? 'harborweb-*' : 'harborwebstaging-*';
role.addToPolicy(
  new iam.PolicyStatement({
    actions: ['s3:ListBucket', 's3:GetObject', 's3:PutObject', 's3:DeleteObject'],
    resources: [`arn:${stack.partition}:s3:::${bucket}`, `arn:${stack.partition}:s3:::${bucket}/*`],
  }),
);
role.addToPolicy(
  new iam.PolicyStatement({
    actions: [
      'cloudfront:CreateInvalidation',
      'cloudfront:GetInvalidation',
      'acm:DescribeCertificate',
    ],
    resources: ['*'],
  }),
);
new CfnOutput(stack, 'DeployRoleArn', { value: role.roleArn });
