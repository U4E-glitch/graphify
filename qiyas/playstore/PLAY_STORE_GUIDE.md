# How to publish Qiyas on Google Play — step by step

Everything technical is already prepared in this repo. The steps below are the
ones only the account owner can do.

## 0. One-time: add the upload-key secrets to GitHub (5 minutes)

The Play bundle (.aab) must be signed with a PRIVATE key — never the keystore
committed in this repo. You received `qiyas-upload.jks`, its base64 text file,
and its password from Claude in chat. Keep the .jks and password somewhere safe
(password manager); then:

1. Open https://github.com/U4E-glitch/graphify/settings/secrets/actions
2. **New repository secret** → Name: `QIYAS_UPLOAD_KEYSTORE_B64` → Value: paste
   the full contents of `qiyas-upload-keystore-base64.txt`
3. **New repository secret** → Name: `QIYAS_UPLOAD_KEYSTORE_PASSWORD` → Value:
   the password from `qiyas-upload-password.txt`

From then on every release run also produces `qiyas-vX.Y.Z-play.aab` — that is
the file you upload to Play.

## 1. Create a Google Play developer account

- https://play.google.com/console → sign up (one-time **$25 USD** fee)
- Identity verification can take a few days for personal accounts
- New personal accounts must run a **closed test with at least 12 testers for
  14 days** before they can publish to production — plan for this

## 2. Create the app in Play Console

- **Create app** → Name `Qiyas — Glucose & BP Diary`, default language English
  (you'll add Arabic), App (not game), **Free**
- Declarations: not a news app, no ads

## 3. Complete "App content" (Policy → App content)

Use the answers in `LISTING.md`:
- Privacy policy URL (the PRIVACY.md link, or host it anywhere public)
- Data safety: **no data collected, no data shared**
- Content rating questionnaire → Utility → expect "Everyone"
- Target audience: 18 and over
- Health apps declaration: personal health diary, user-entered data,
  not a medical device
- App access: "All functionality is available without special access"

## 4. Store listing (Grow → Store presence → Main store listing)

- Paste the EN texts from `LISTING.md`; add an **Arabic (ar)** translation and
  paste the AR texts
- Upload `graphics/play_icon_512.png` and
  `graphics/feature_graphic_1024x500.png`
- Screenshots: take 4–8 on your phone (suggested set in `LISTING.md`).
  Long-press power+volume-down to capture; upload as-is.

## 5. Upload the build

- Release → **Testing → Closed testing** → Create track → Create release
- When asked about signing, accept **Play App Signing** (Google holds the app
  signing key; your `qiyas-upload.jks` is the upload key)
- Upload `qiyas-v1.0.0-play.aab` from the GitHub release
- Release notes: e.g. "First release — offline glucose & blood pressure diary
  with photo scan, weekly reports, and PDF/CSV export. English & Arabic."
- Add your 12+ testers (email list or a Google Group), start the test, and
  send them the opt-in link

## 6. After 14 days of closed testing

- Apply for production access in the console (it asks a short questionnaire
  about your testing)
- Release → **Production** → Create release → reuse the same AAB (or a newer
  one) → Submit for review
- First review typically takes 1–7 days; health-category apps are sometimes
  checked more carefully — the built-in disclaimers and the no-medication-advice
  design are exactly what reviewers look for

## 7. Each future update

1. Ask Claude to bump the version and dispatch a release (or run the
   `qiyas-release` workflow manually with the new version number)
2. Download `qiyas-vX.Y.Z-play.aab` from the GitHub release
3. Play Console → Production → Create release → upload → submit

## Important notes

- **Never lose `qiyas-upload.jks` or its password.** With Play App Signing a
  lost upload key can be reset via support, but it's a hassle.
- The sideload/Obtainium channel keeps working independently — but a phone
  cannot "switch" from the sideloaded app to the Play version without a
  reinstall (different signing identity), so pick one channel per device.
- The package name `com.dheyab.qiyas` becomes permanent on Play the moment you
  create the first release — it cannot ever be changed.
