# Google Play Console — Listing Content

Copy-paste-ready answers for every Play Console form. Fill these in once
ID verification clears. Keep in sync with `docs/PRIVACY.md` and
`docs/TERMS.md`.

## App details

| Field | Value |
|---|---|
| App name | WiFi Database |
| Default language | English (US) |
| App or game | App |
| Free or paid | Free |
| Category | Tools |
| Tags | wifi, internet, network, password finder, utilities |
| Contact email | support@clickbeans.com |
| Privacy policy URL | https://wifidb.clickbeans.com/privacy |
| Website (optional) | https://wifidb.clickbeans.com |

## Store listing text

### Short description (80 char max)

```
Find and share WiFi passwords nearby — community-powered, no ads.
```

(65 characters)

### Full description (4000 char max)

```
WiFi Database is a community-powered directory of WiFi passwords. See
which networks near you have a shared password, contribute passwords
for networks you have access to, and help the community keep them
working — all in one app, with no ads and no tracking.

FIND WIFI NEARBY
Open the app and it instantly shows which networks your phone can scan
and which ones have a password in the community database. No guessing,
no asking the barista.

SHARE WHAT YOU KNOW
Picked a network you have access to? Share the password and drop a pin
on the map. Only networks physically present in your current scan can
be shared — no manual entry, no made-up entries.

BUILT-IN MAP
Browse all shared networks on a map around you. Tap a pin to see
details, get the password, and add it to your phone.

COMMUNITY-RANKED PASSWORDS
Every password can be voted up or down by people who have tried it.
The most reliable password rises to the top; stale ones sink. Report
bad entries and they are automatically hidden after repeated reports.

ADD TO YOUR PHONE
One tap sends the password to Android's Wi-Fi suggestion system — your
phone connects automatically whenever that network is in range. You
approve it once; the app never silently joins networks.

NO ADS. NO TRACKING. NO SELLING YOUR DATA.
This is not an ad-supported app. There are no third-party analytics, no
advertising SDKs, and no data brokers. Passwords are encrypted with
AES-256 on our servers. Your location is only used while the app is
open — never in the background.

PRIVACY-FIRST DESIGN
• Your email is used only for account verification and password resets
• WiFi passwords are encrypted at rest
• Contributions carry no public author information
• Delete your account in Settings at any time
• Open-source privacy policy and terms, served by our own API

WiFi Database is a hobby project, not a startup. It exists because
sharing WiFi should be easy and community-driven — not monetized with
your personal data. If you find it useful, there is an optional
donation prompt (heavily capped) via GitHub Sponsors.

Only share networks you have permission to share. See the Terms of
Service for details: https://wifidb.clickbeans.com/terms
```

(~2,190 characters — room to spare if you want to adjust)

## Data Safety form

### Does your app collect or share any of the required user data types?

**Yes.**

### Data types collected and/or shared

#### 1. Location

| Question | Answer |
|---|---|
| Collected? | Yes |
| Shared? | Yes |
| Which? | Precise location (GPS fix captured when sharing a network) |
| Purpose | App functionality (pin shared networks on a map for other users) |
| Sold? | No |

Explanation: Location is collected in the foreground when a user shares
a WiFi network. The pinned location is visible to all verified users —
that is the core purpose of the app. No background location is ever
collected.

#### 2. Personal info — Email address

| Question | Answer |
|---|---|
| Collected? | Yes |
| Shared? | No |
| Purpose | Account management (verification, password reset, essential notices) |
| Sold? | No |

#### 3. App activity — App interactions

| Question | Answer |
|---|---|
| Collected? | Yes |
| Shared? | No |
| Purpose | Fraud prevention, security (one vote per user per password; report abuse; 90-day audit log) |
| Sold? | No |

#### 4. App info and performance — Crash logs

| Question | Answer |
|---|---|
| Collected? | Yes |
| Shared? | No |
| Purpose | Analytics (developer crash diagnostics: stack trace, device model, OS version — no account or location data) |
| Sold? | No |

### Data types NOT collected

Select "No" for every category not listed above:

- Name
- Phone number
- Photos and videos
- Audio
- Files and documents
- Contacts
- Calendar
- Health and fitness
- Financial info (donations happen on GitHub Sponsors; we never see payment details)
- Web browsing history
- Device or other IDs (no advertising ID, no Analytics ID)
- Installed apps

### Security practices (checkboxes)

| Question | Answer |
|---|---|
| Is data encrypted in transit? | Yes (HTTPS / TLS 1.2+) |
| Is data encrypted at rest? | Yes (AES-256-GCM for passwords; database at rest) |
| Can users request data deletion? | Yes (account deletion in Settings; documented in privacy policy) |

## Content rating questionnaire (IARC)

| Question | Answer |
|---|---|
| Does your app contain violence? | No |
| Does your app contain sexual content? | No |
| Does your app contain controlled substances? | No |
| Does your app contain gambling? | No |
| Does your app contain hate speech or discrimination? | No |
| Does your app contain user-generated content? | Yes |
| Can users share content with each other? | Yes |
| Does your app share the user's location with others? | Yes |
| Does your app allow user-to-user communication (chat/messaging)? | No |
| Is your app directed at children under 13? | No |
| Does your app contain advertising? | No |
| Does your app offer in-app purchases? | No |
| Does your app share user data with third parties? | No |

Expected rating: **Everyone** (possibly 13+ due to UGC and location sharing; answer the actual questionnaire as presented).

## Target audience and content

| Question | Answer |
|---|---|
| Target age group | 13 and above |
| Is the app directed at children under 13? | No |
| Does the app collect data from children under 13? | No (account requires 13+, enforced in ToS) |
| Does the app contain user-generated content? | Yes |
| Does the app have a mechanism for reporting UGC? | Yes (in-app report button; 3-strike auto-hide; audit log) |
| Does the app have a moderation policy? | Yes (Terms of Service §3: contributions that gather multiple reports are automatically hidden; operator can remove anything) |

## App access (for review)

The app requires an account to browse or share passwords. For review:

1. Open the app → tap **Sign up**
2. Enter any email address (verification is sent instantly via Brevo SMTP; use a real inbox you control)
3. Tap the verification link in the email (deep link opens the app)
4. Log in and grant location + WiFi permissions

Alternatively, provide the reviewers with a pre-made account if they
require one:

| Field | Value |
|---|---|
| Username | erikbeans@gmail.com |
| Password | _ask the owner — password was shared in earlier chat history; do NOT put it in this file_ |

## Store assets checklist

| Asset | Requirement | Status |
|---|---|---|
| App icon | 512×512 PNG, 32-bit alpha | Needs replacement (current is placeholder vector) |
| Feature graphic | 1024×500 PNG or JPG | Not yet created |
| Phone screenshots | Minimum 2, 16:9 or 9:16, min 320px, max 3840px | Take from Fold 6 via adb |
| 7-inch tablet screenshots | Optional | Skip for now |
| 10-inch tablet screenshots | Optional | Skip for now |

## Release track

New personal developer accounts must enroll 12–20 testers in **closed
testing** for **14 days** before production access is granted. Suggested
plan:

1. Once ID verification clears, create an app entry in Play Console
2. Create a **Closed testing** track; invite friends/family via email lists
3. Upload `app-release.aab` (built by CI once the 4 signing secrets are set)
4. Tag `v0.1.0` → CI builds and signs the AAB → download from GitHub Releases
5. After 14 days and enough testers, promote to Production
