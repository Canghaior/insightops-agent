# Public Beta console configuration

This procedure stores production credentials without committing or printing them. The first
workflow intentionally leaves `PUBLIC_BETA_ENABLED`, `IDENTITY_MAIL_ENABLED`,
`TENCENT_SES_ENABLED`, and `TURNSTILE_ENABLED` set to `false`.

## 1. Tencent Cloud CAM

Create a custom policy named `InsightOpsSesSendOnly` with this JSON:

```json
{
  "version": "2.0",
  "statement": [
    {
      "effect": "allow",
      "action": [
        "ses:SendEmail",
        "ses:GetSendEmailStatus"
      ],
      "resource": ["*"]
    }
  ]
}
```

Create a normal CAM sub-user named `insightops-ses-sender`. Enable programming access only;
do not enable console login. Associate only `InsightOpsSesSendOnly`. On the sub-user's
**API key** tab, create one key and save its `SecretId` and `SecretKey`. The SecretKey is
displayed only once.

## 2. Cloudflare Turnstile

Open the existing `InsightOps Registration` widget and verify:

- hostname: `insightops.canghaior.com`
- mode: Managed
- pre-clearance: disabled

Copy the widget site key and secret key. Do not rotate the secret unless it may have been
exposed.

## 3. Public identity

Choose a public display name and a monitored contact mailbox. The contact mailbox must accept
incoming mail; do not use `no-reply@mail.canghaior.com` as the contact mailbox.

Recommended initial values:

- display name: `InsightOps Agent 运营者`
- contact address: a dedicated mailbox such as `support@canghaior.com`

## 4. GitHub production environment

Open the repository, then **Settings > Environments > production > Environment secrets**.
Create these six secrets:

| Secret | Value |
| --- | --- |
| `TENCENT_SES_SECRET_ID` | CAM sub-user SecretId |
| `TENCENT_SES_SECRET_KEY` | CAM sub-user SecretKey |
| `TURNSTILE_SITE_KEY` | Existing widget site key |
| `TURNSTILE_SECRET_KEY` | Existing widget secret key |
| `PUBLIC_OPERATOR_NAME` | Public display name |
| `PUBLIC_CONTACT_EMAIL` | Monitored public contact mailbox |

Never paste these values into an issue, commit, workflow input, action log, or chat message.

After all six secrets exist, open **Actions > Configure public Beta production prerequisites >
Run workflow**. Enter `CONFIGURE-PUBLIC-BETA-DISABLED` as the confirmation and run it.

Successful completion means the masked GitHub secrets were transferred over SSH and written
atomically to the server-side `.env.prod`, file permissions remain `0600`, production preflight
passed, and public registration remains closed.

## 5. Release gate after template approval

Do not activate the adapters until Tencent Cloud SES templates `58078`, `58079`, and `58080`
all show **Approved**. The activation sequence is:

1. enable mail, Tencent SES, and Turnstile while keeping the database registration switch off;
2. deploy and verify the public registration readiness endpoint;
3. use dedicated real mailboxes to test registration verification, password reset, and a
   workspace invitation;
4. enable the database registration switch from the admin page;
5. immediately test one final public registration and confirm the 100-user cap.
