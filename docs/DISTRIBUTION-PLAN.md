# Distribution plan: GitHub (free) + Google Play (free with Pro)

Agreed plan for publishing Resona two ways. Nothing here is done yet; work
through it in order. The order matters: done out of order, installed copies
stop getting updates.

## The shape of it

- **Source code:** this repository, made **private** (last step, below).
- **Public repository** (suggested name `Shadow4-18/Resona`): only releases,
  the website (look links, privacy policy), the gallery, and a license.
- **Two builds from the same code** (Gradle product flavors):
  - **GitHub build:** everything unlocked, the in-app updater, and a
    **"Support the creator"** button (donation link). Free forever.
  - **Play build:** free download with a one-time **Pro** unlock and a
    tip jar, through Google Play Billing; no updater; no outside donation
    link (Play doesn't allow one).

## Part 1: Move to a private source repository + public release repository

1. **Create the public repository** (e.g. `Shadow4-18/Resona`). Add:
   - a `LICENSE`: free to download and use; no redistribution or
     modification (all rights reserved otherwise);
   - a short README with the download link and the donation link;
   - the website (`site/`) via GitHub Pages: look links
     (`/look/#...`) and the privacy policy;
   - the gallery: `gallery/looks.json`, its `gallery` release, and the
     issue-based submission workflow (`.github/workflows/gallery-submissions.yml`
     and `.github/scripts/gallery_submission.py`).
2. **Release one last version from this repository** that points
   everything at the new one:
   - the updater's release URL (`Updater.java`, `releases/latest`);
   - the gallery list URL (`Gallery.java`);
   - the look-link web address (`LookCode.java`), still accepting the old
     `shadow4-18.github.io/Reactive-Wallpaper/look/` links in the app;
   - "Share", "About" and anything else naming the old repository.
   Everyone who updates moves over automatically.
3. **Publish releases from the private repository to the public one.**
   Needs a fine-grained access token (contents: read and write on the
   public repository only), saved as a secret in the private repository;
   the release step uploads `Resona.apk` there.
4. **Mind the build minutes.** Private repositories get 2,000 free Actions
   minutes a month; the emulator tests take about 12 minutes a run. Run
   the full device tests only for releases and on request, and keep the
   quick build and unit tests on every push.
5. **Make this repository private** once most people have updated (give
   it a couple of weeks). Copies that never update simply stop seeing
   updates. The license covers everything from the move on; code already
   downloaded while public can't be taken back.

## Part 2: Two builds

- Product flavors `github` and `play` in `app/build.gradle`.
- `github`: `Updater` and the update banner; "Support the creator" button
  in About (and maybe a gentle one-time thank-you prompt after a week of
  use); every Pro feature unlocked.
- `play`: no `Updater`, no `REQUEST_INSTALL_PACKAGES` permission, no
  update banner, no donation link; Google Play Billing; Pro gating.
- Different version names are fine (e.g. `3.9.0` and `3.9.0-play`), same
  version code line.

## Part 3: Google Play

### Pricing (decided)

- **Free download, no ads, no subscription.**
- **Pro: about $2.99, one-time** (launch price; can rise once it has a
  rating). Occasional sales.
- **Tip jar:** one-time "support" purchases at $1.99 / $4.99 / $9.99, with
  an optional "Supporters" list in About.
- **Free tier** (enough to love it and review it): visualizers, picture
  glow, sparkles, subject in front, weather, saved looks, sharing, the
  gallery, time-of-day looks, battery saver.
- **Pro:** characters that move (skeleton, blinking), 3D depth from the
  picture, subject tracking in videos/GIFs, shader effects and effect
  mixes, clips with sound, the favorite-looks widget. (Adjust as needed.)
- Google's fee: 15% of the first $1M a year.

### Code to add

- Google Play Billing (one Pro product, three tip products), with restore
  on reinstall and a "Pro" badge in the editor.
- Gating: Pro features show with a small lock and a short "Pro" sheet
  explaining what it unlocks; nothing breaks for looks shared from Pro
  users (they open, the locked parts show a preview or prompt).
- Android App Bundle (`.aab`) output and Play App Signing.

### Store and policy work (the old #22)

- **Developer account:** $25 one-time, identity verification. Selling
  needs a payments profile; as a trader, the name and address may be
  shown on the listing in some regions (e.g. the EU).
- **Privacy policy** page on the public site: what's on the phone only
  (pictures, looks, audio is analyzed live and never recorded or sent),
  what goes over the network (update checks in the GitHub build; the
  gallery; weather from Open-Meteo with a rounded location, only when
  Live weather is on).
- **Permission declarations** in the Play Console, each with why:
  - record audio (the visualizer listens to what's playing);
  - notification access (the playing song's cover; notifications are never read);
  - screen capture / media projection foreground service (clips with the music's sound);
  - approximate location (sunrise times and Live weather, optional);
  - vibration, internet.
- **Data safety form** matching the privacy policy.
- **Store listing:** title, short and full description, icon, feature
  graphic, phone screenshots (the CI renders are a good start), a short
  video if possible, content rating questionnaire, target audience.
- **Closed testing first:** new personal developer accounts must run a
  closed test with at least 12 testers for 14 days before going to
  production. The test-build habit we already have fits this.

## Decisions still open

- Public repository name (`Resona` suggested).
- Donation platform and link (Ko-fi, Buy Me a Coffee, GitHub Sponsors,
  PayPal...).
- Exact free/Pro split and the launch price.
- When to make this repository private.
