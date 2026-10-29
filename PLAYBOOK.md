# Fire TV clean-home kit

Turns a Fire TV (stick or Fire TV Edition TV) into a clean, Apple TV-style home: no ads or tracking,
your own apps kept, everything reversible. Built and tested on an AFTDCT31 Fire TV Edition TV,
Fire OS 7.7.1.5, Oct 2026.

## What's in here

| Path | What |
|---|---|
| `setup.sh <ip> <stage>` | The whole process, one stage at a time (below). Each stage prints its proof. |
| `maintain.sh <ip>` | Nightly upkeep: re-disables bloat, keeps Glass as home, updates GitHub apps (same-key only). |
| `debloat-catalog.txt` | Every package we disable **and every one we keep on purpose**, with the reason. |
| `glass/` | Our Glass launcher fork (source). `assets/glass.apk` is the ready-built copy. |
| `assets/wallpapers/` | 16 elementary OS wallpapers (open licences). |
| `assets/icons/make_tiles.py` | Generates the graphite glyph tiles (needs Pillow). |
| `tvs/<model>-<ip>/` | Per-TV records: `protected.txt`, `plan.txt`. |
| `com.firetv.maintain.plist.template` | Schedules `maintain.sh` nightly. |
| `bin/tvctl` | Control the TV from the Mac / Siri: lock, timer, refresh, open apps, on/off, `set` options. |

## Before you start (on the TV)

1. Settings > My Fire TV > About > click the device name 7 times ("You are now a developer").
2. Settings > My Fire TV > Developer Options > **ADB debugging ON**.
3. Note the IP: Settings > My Fire TV > About > Network.
4. On the Mac: `brew install android-platform-tools jq`. To verify/install extras also:
   `brew install openjdk@17 && brew install --cask android-commandlinetools` and
   `sdkmanager --sdk_root=/opt/homebrew/share/android-commandlinetools "build-tools;35.0.0" "platforms;android-35"`.

## The process

```
./setup.sh 10.0.0.X check      # read-only: is it supported? what is it?
./setup.sh 10.0.0.X protect    # save your apps — these are never touched
./setup.sh 10.0.0.X plan       # STOP: open tvs/<model>-<ip>/plan.txt, delete lines you want to keep
./setup.sh 10.0.0.X debloat
./setup.sh 10.0.0.X home       # Glass + wallpapers + screensaver delay
./setup.sh 10.0.0.X extras     # asks one by one: Aerial Views, Jellyfin, F-Droid, Proton VPN, TizenTube
./setup.sh 10.0.0.X dns        # network-wide ad + tracker blocking (also blocks Amazon's protected metrics)
./setup.sh 10.0.0.X verify     # Home proof, disabled list, protected apps all still there
```
Accept "Allow USB debugging?" on the TV (tick Always allow) the first time.

## What to check per TV (from `check`)

- **API level** — Glass needs 28+ (Fire OS 7 = 28, Fire OS 8 = 30). Older: stop.
- **CPU ABIs** — `arm64` or 32-bit `armeabi-v7a`; extras pick the right TizenTube build from this.
- **RAM** — 1–1.5 GB sticks benefit most from the Refresh button and the debloat.
- **HDMI ports > 0** → it's a Fire TV Edition **TV**: the Inputs menu (rename with ☰) matters; Fire OS
  also drives the tuner/HDMI, so it can never be replaced.
- **Screen-off timer** — Fire OS often ships "never"; `home` sets 5 min so the screensaver starts.

## What to keep (decide per household)

Kept by default (commented in the catalog, with reasons): the Amazon launcher (removing it can leave
no home screen), **Appstore** (updates Netflix/Prime/HBO — Glass hides its tile instead), Live TV,
Alexa voice, casting/Miracast, system updates, account/profile services. Personal picks to review in
`plan.txt`: Amazon Music and Photos. Never disable anything a household member uses.

## Using Glass (remote)

- Top-left ⊞ **Apps**: a compact Mac-style dock hangs down the left edge — 5 iPhone-style rounded
  icons at a time, scrolling with the focus (the rest fade away); the focused one floats out, magnified,
  with its name beside it. Every app not already on the home screen, most used first. Apps that only
  ship a wide wordmark banner (e.g. Prime Video) get a single bold initial on their brand colour,
  automatically, including future installs. ▲▼ move, OK open, ☰ app info, Back close.
- Top bar (right): **Tools** (free RAM now, Free up memory, focus timers, Lock TV) · Inputs ·
  Wi-Fi · Customize · **Settings**: a Control Center-style drop-down of round mini buttons that open
  settings pages directly. Built per device at runtime: each button tries Fire OS links, then standard
  Android ones, and appears only if that TV lets Glass open one (Fire OS keeps Display, Preferences and
  Account for its own launcher, so on Fire TV those live under "All settings").
- Glass replaces only Amazon's home screen (`HomeActivity*`); other Amazon launcher screens such as
  MainSettingsActivity stay open.
- Home rows (Customize > Home rows): **Recent + For you** (default) = the 4 apps you opened most
  recently, then the TMDB "For you" cards; everything else is in the Apps dock. "All apps" brings back
  the full grid. Customize's header carries a small credit, "Launcher made by Shounak with Claude"
  (plain Outfit, small and clear, Apple-like; no fancy fonts).
- Separators are " | " everywhere (no dots), per the owner's taste.
- ☰ on an app: Add to dock / **Move** (◀ ▶ slide, OK drop) / Remove / App info. ☰ on an input: rename.
- Locked TV: press the 10 arrows shown to force-unlock; a mistake deals a new sequence.
- Screensaver tile = Aerial Views settings (Apple TV aerials, your photos via USB/Samba/Immich).

## Phone remote (no app, no computer needed day to day)

- On the phone (same Wi-Fi): open `http://<tv-ip>:8765` in Safari → enter the 4-digit code the TV
  shows → Share → **Add to Home Screen**. Swipe pad (swipe = arrows, tap = OK, hold = menu), Back,
  Home, Play/Pause, volume, Type (text appears on the TV as you type), Apps, Tools, Sleep.
- Each TV is its own: its own secret, its own pairing codes, its own address. A phone paired with one
  TV can't control another until paired there too.
- Served by Glass itself (`Remote.java` inside the always-on HomeButtonService).
- **Fast mode**: a tiny key helper (`Injector.java`, run as the shell user with `app_process`) presses
  keys in ~50 ms anywhere, including Amazon's menus. Fire OS blocks apps from the TV's own ADB, so
  after a TV *reboot* only a computer can restart it: `bin/remote-helper <ip>` (also run by
  `setup.sh home` and nightly `maintain.sh`).
- **Fallback** while the helper isn't running: Glass gets keys directly; Back/Home/Sleep, volume and
  play/pause always work; arrows/OK move focus in apps built from standard views (not Amazon's
  settings lists or web-based apps like TizenTube); typing fills the focused text box.
- Reserve the TV's IP in the router (DHCP reservation) so the phone's Home Screen link keeps working.

- **QR codes on the TV**: Tools > *Phone remote* shows one QR straight to the remote. Customize > the
  credit button ("Launcher made by Shounak with Claude") shows two: *Phone remote* and *Learn this TV*
  (`/guide`, an Apple-style guide to everything, served by Glass). Phones must be on the TV's Wi-Fi.

## Home screen behaviour

- Clock, date, weather (Open-Meteo, °C · °F) and the wallpaper's location caption stay pinned; the dock
  and grid rows take turns in the slot below, rows above dissolve. Names show under the focused tile.
- Tiles without TV artwork are clear "liquid glass" (`make_tiles.py`); the dock band is glass too.
- Wallpapers: 16 bundled (captions in `assets/wallpapers/captions.txt`) + a new Wikimedia Commons
  Picture of the Day most nights (wide photos only, newest 20 kept). Rotation changes at 12 AM sharp
  (or hourly): newest unseen photo first, otherwise random, no repeats until all have been shown.

## Paintings screensaver (art mode)
`setup.sh <ip> paintings` puts 15 clear, detailed public-domain paintings (Art Institute of Chicago, CC0; see
`assets/paintings/SOURCES.txt`) on the TV and lets Glass switch screensavers. Then Customize shows
**Screensaver** (Aerial Views | Paintings) and **Change painting** (10 min to once a day).
**Tools > Paintings** (and the phone remote's Tools, `tvctl paintings`) puts the paintings up now and keeps
the screen on until any button; same frame, schedule and drift. Needs only the paintings, not the permission.
Customize itself is a dock like Apps: iPhone Settings-style icons hanging from the Customize
button, the focused one magnified with its name and choice; Left/Right/OK change it.
The whole screen becomes a framed painting: wooden frame with a gold edge at the screen's edges,
the painting filling everything inside it (wide works only, so just a little is trimmed top and
bottom), dimmed to an even gentle brightness. Drawn at the panel's own resolution (4K), not
Fire OS's 1080p app size. Against burn-in it changes on that schedule and drifts under 1%.
Only wide paintings: the screen is 16:9. Storage: ~60 MB; Android refuses app installs below
~10% free on /data (`adb shell pm trim-caches 2G` frees cache safely).

## "For you" row (TMDB)

Two rows of well-rated, popular movies and shows on the services installed (Netflix, Prime Video,
Apple TV+, Max), alternating services, refreshed every 12 h, region from the weather lookup.
Needs a free TMDB key: themoviedb.org → Settings → API (type Personal, URL `http://localhost`).
Save it once: `umask 077; printf '%s' 'YOUR_KEY' > ~/.tmdb-key` — `setup.sh home` copies it into Glass.
Toggle: Customize → Suggestions (TMDB). Data from TMDB; not endorsed or certified by TMDB.

## Nightly upkeep does

Re-disable bloat · keep Glass as home + screensaver · update TizenTube/Jellyfin/Aerial Views (same
signing key only) · clear app caches when < 600 MB free · add the Picture of the Day.

- Leaves the screensaver alone when it is Aerial Views or Glass Paintings.
## Siri & iPhone

- `bin/tvctl` examples: `tvctl lock 60`, `tvctl timer`, `tvctl break`, `tvctl refresh`, `tvctl open netflix`,
  `tvctl off`, `tvctl set clockSize 0`. Echo the TV's IP into `~/.tv-ip` for other TVs.
- Mac Siri: Shortcuts app > new shortcut > "Run Shell Script": `<this folder>/bin/tvctl lock 60`,
  name it "Lock the TV" → "Hey Siri, lock the TV".
- iPhone: enable Mac Remote Login (System Settings > General > Sharing), then an iPhone shortcut with
  "Run Script over SSH" to the Mac running the same command. Or use Amazon's Fire TV app as a remote.
- There is deliberately no remote unlock: unlocking stays the 10-arrow challenge on the TV.

## Lessons learned (Fire OS gotchas)

- Fire OS handles **Home** before any app; Glass's accessibility service relaunches it when the Amazon
  home appears, behind a pre-created black cover. Android 9 also blocks app launches for 5 s after
  Home — `alarm_manager_constants min_futurity=0` + an AlarmManager launch gets around it.
- `pm disable-user` on some packages → "protected package": Fire OS refuses; listed in the catalog.
- One `pm` command per `adb shell` call — chained ones can silently stop after the first.
- Screensaver starts from `system screen_off_timeout` (not `contextual_screen_off_timeout`);
  Fire OS 8.1+ also needs `secure amazon_ambient_enabled 0` + reboot.
- No cross-app "Continue watching" for third-party launchers on Fire OS (Amazon-private API).
- DRM apps (Netflix) show black in `screencap` — not a bug.
- After a reboot Fire OS may show "Unable to Detect Your Remote"; OK dismisses it.
- zsh: never name a shell variable `path` (it's tied to `$PATH`).
- Glass is a **debug** build on purpose: `run-as` lets the kit push wallpapers into it.
- ~4 GB storage fills up: `adb shell pm trim-caches 4G` frees app caches (installs fail below ~10% free).
- `adb shell` inside a `while read` loop eats the loop's input — always add `</dev/null`.
- Netflix's ad tier serves ads from Netflix's own servers: DNS blocking can't remove them.
- If settings "change by themselves" while testing, someone may be using the remote at the same time.
- Not possible on Fire OS 7: replacing Fire OS (locked bootloader); cross-app "Continue watching".
- Netflix/Prime/etc. offer no public deep links: a "For you" card opens the right app, not the title.

## Rebuild Glass (after editing `glass/`)

```
cd glass/launcher
echo "sdk.dir=/opt/homebrew/share/android-commandlinetools" > local.properties
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home ./gradlew assembleDebug
```
`setup.sh home` and `update` use a build of `glass/` only when it is newer than `assets/glass.apk`. Per-app tile, name and glow overrides
live in `glass/launcher/app/src/main/res/values/config.xml` (`tile_overrides`).

## Undo

`./setup.sh <ip> undo` re-enables everything in `plan.txt` and gives Home back to Amazon.
One package: `adb shell pm enable --user 0 <package>`. Stuck lock (last resort):
`adb shell run-as com.ivantrj.glasslauncher rm shared_prefs/lock.xml` (and, if Bedtime lock is on,
`shared_prefs/look.xml`, which resets Customize) then reboot the TV.

## Nightly upkeep

See `com.firetv.maintain.plist.template` (one schedule per TV). Logs:
`~/Library/Logs/fire-tv-maintain-<ip>.log`.
