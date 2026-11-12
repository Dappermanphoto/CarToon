# CarToon

**Spotify, YouTube, now-playing controls and OBD-II gauges for Android Auto.**
Made by [@SlowCD7](https://www.instagram.com/slowcd7/).

CarToon runs on your phone and shows up on your car's Android Auto screen as a parked app. It
works while the car is parked; Android Auto closes it when you start driving.

> CarToon is a hobby project. It is not affiliated with or endorsed by Google, Spotify, YouTube,
> Samsung or any car maker.

---

## Contents
1. [Features](#features)
2. [Requirements](#requirements)
3. [Install](#install)
4. [First-time setup](#first-time-setup)
5. [Using CarToon](#using-cartoon)
6. [Troubleshooting](#troubleshooting)
7. [Build it yourself](#build-it-yourself)
8. [Credits and privacy](#credits-and-privacy)

---

## Features

| Tab | What it does |
|---|---|
| **Playing** | Big now-playing card for whatever is playing in Spotify, YouTube Music and other music apps, with controls and a wavy seek bar. |
| **Spotify** | Full Spotify control (Premium needed): play/pause, skip, seek, shuffle, repeat off/all/one, like songs, back/forward 15 s. Browse your **playlists, albums and liked songs** as cover-art grids, **search** Spotify, see **Up next**, and open Spotify's "For you" picks. The screen takes on the color of the album cover. |
| **YouTube** | Search YouTube with an on-screen keyboard or voice, plus trending videos. |
| **OBD** | Live gauges from a Bluetooth LE OBD-II adapter: digital cluster, graph, list and a voltage view. Trouble-code hub with plain-English descriptions and a web lookup. A built-in **demo car** works without an adapter. |
| **Profile** | Your name, a photo of your vehicle and its year, make, model, sub-model and engine. |
| **Settings** | Themes, custom wallpapers, gauge colors, low-voltage warning, polling speed, driving lock, and the keys for YouTube and Spotify. |

Other touches: Material You colors from your wallpaper, Night / Day / Custom themes, custom
wallpaper (separate portrait and landscape photos with brightness, vignette and tint),
auto-sizing gauge labels, and a low-voltage warning that shows on every screen.

## Requirements
- An Android phone on **Android 15 or newer** (needed for parked apps on Android Auto).
- A car or head unit with **Android Auto**.
- **Spotify Premium** for the Spotify tab.
- For OBD: a **Bluetooth LE** ELM327-style adapter (designed with the Vgate iCar Pro BLE) and a
  1996-or-newer car (OBD-II).

---

## Install

CarToon isn't on the Play Store, so you install it yourself ("sideloading").

### Step 1: Download the app
On your **phone**, open the [Releases](../../releases) page and download the latest
`CarToon-vX.X.apk`.

### Step 2: Install it
1. Open the downloaded file.
2. When Android asks, tap **Settings** and turn on **Allow from this source**, then go back and tap **Install**.
3. **Samsung phones:** if the install is blocked, turn off **Auto Blocker** first
   (Settings → Security and privacy → Auto Blocker). You can turn it back on afterwards.
4. **Play Protect** may warn that the app is unknown. Tap **More details → Install anyway**.

### Step 3: Let Android Auto show it
1. On the phone open **Settings → Apps → Android Auto → Additional settings in the app**.
2. Scroll to the bottom and tap **Version** about 10 times, then **OK** to turn on developer mode.
3. Tap **⋮ (top right) → Developer settings** and check **Unknown sources**.
4. Unplug and reconnect your phone to the car. CarToon appears in the Android Auto app list.

Android Auto updates sometimes switch **Unknown sources** off again. If CarToon disappears,
repeat step 3.

### Updating
Download the newer APK from Releases and install it over the old one. Your settings are kept.

---

## First-time setup

Do these in CarToon's **Settings**, on your phone.

### YouTube search (optional)
1. Go to [console.cloud.google.com](https://console.cloud.google.com) and create a project.
2. Search for **YouTube Data API v3** and click **Enable**.
3. Go to **Credentials → Create credentials → API key**.
4. Recommended: edit the key and restrict it to **YouTube Data API v3**.
5. Paste the key into **Settings → YouTube**.

The free quota allows roughly 100 searches a day. Treat the key like a password.

### Spotify (optional, Premium needed)
Spotify only lets an app talk to its player if you register the app, so you make your own free
one. Nothing is shared with anyone else.

1. Go to [developer.spotify.com/dashboard](https://developer.spotify.com/dashboard) and click **Create app**.
2. Fill it in:
   - **Redirect URI:** `cartoon://callback`
   - **APIs used:** check **Android** (and **Web API** if it's offered)
3. Open the app's settings → **Android packages** and add:
   - **Package name:** `com.slowcd7.cartoon`
   - **SHA1 fingerprint:** copy it from CarToon → **Settings → Spotify** (there's a Copy button).
4. Under **User Management**, add the email of your Spotify account.
5. Paste the app's **Client ID** into CarToon → **Settings → Spotify**.
6. Open the **Spotify** tab and tap **Sign in with Spotify**. Approve the permissions.

**Good to know**
- Spotify's rules for new apps ("development mode") require the app's owner to have Premium and
  allow up to 5 users.
- Your library access (playlists, albums, liked songs, search, Up next) lasts about an hour. When
  it runs out, the tab shows **Sign in with Spotify** again. Playback controls keep working.
- If you updated from an older CarToon, sign in again once to approve the newer permissions.

### OBD adapter (optional)
1. Plug the adapter into the car's OBD-II port and turn the ignition on.
2. **Settings → OBD adapter → Scan**, allow the Bluetooth permission, and pick your adapter
   (a Vgate iCar Pro usually shows as *IOS-Vlink*).
3. Open the **OBD** tab and tap **Connect**.

No adapter? Tap **Try the demo car** on the OBD tab. It simulates a 1994 Honda Accord, including
trouble codes and voltage dips, so you can try every screen.

---

## Using CarToon

### Spotify
- On a phone held upright, a compact player sits on top. Tap it to open the full player, and tap
  **Show library** to come back. On the car screen the full player sits beside the library.
- In the player, use the round buttons for like, back 15 s, shuffle, repeat and forward 15 s.
  The seek bar waves while music plays and goes flat when paused; tap or drag it to seek.
- The library tabs are **Playlists, Albums, Liked songs, Search, Up next** and **For you**.
  Tap a cover to play it. **Hold a song** in Liked songs or Search to add it to the queue.
- Playlists, Albums, Liked songs, Search and Up next need the Spotify sign-in described above.

### YouTube
Type a search with the on-screen keyboard (or tap the microphone). Tap a video to watch it.

### OBD
- **Codes:** reads stored and pending trouble codes. Tap a code to open it. The name comes from
  [obd-codes.com](https://www.obd-codes.com) when you're online and is remembered afterwards;
  otherwise a built-in description is used. The **Look up** button at the bottom of the screen opens a
  web search for that code and your vehicle (from the Profile tab). Use **Clear** only after the
  problem is fixed. It also resets your emissions readiness monitors.
- **Cluster:** digital gauges. **Hold a gauge** to change which sensor it shows or rename it.
- **Graph / List:** the same sensors as a live chart or a list.
- **Voltage:** battery and charging voltage, with a warning if it drops below your limit.
- Older cars answer slowly. In Settings you can make fast sensors (RPM, speed) update more often.
- Notes: oil pressure isn't part of standard OBD-II, and cars from around 2001 only have basic
  oxygen sensors, so there is no true air-fuel ratio.

### Profile
Add your name, a photo of your vehicle and its details. The vehicle makes the **Look up** searches
more specific. It's stored only on your phone.

### Wallpaper
**Settings → Wallpaper.** Choose one **portrait** photo (used on the phone) and one **landscape**
photo (used on the car screen). If you choose only one, it's used for both. Adjust brightness,
vignette and a color tint with the sliders. Choose photos on the phone; you can fine-tune the
sliders from the car screen.

### Driving lock
**Settings → Driving lock.** When your OBD adapter (or the demo car) reports that the car is
moving, the YouTube tab and video playback are replaced by a "Parked only" screen until you've
stopped for a few seconds. Android Auto itself may also hide the app while driving.

---

## Troubleshooting

| Problem | Try this |
|---|---|
| The install is blocked | Turn off Samsung **Auto Blocker**, and allow installs from your browser or Files app. |
| CarToon isn't in the car's app list | Re-check **Unknown sources** in Android Auto's developer settings, then reconnect the phone. |
| Spotify: "Explicit user authorization is required" | Tap **Sign in with Spotify**. Check the package name, the SHA1 and your email under User Management in the dashboard. |
| Spotify library tab shows an error | Sign in again (access lasts about an hour). Make sure your account is added under User Management. |
| Spotify: "Up next" can't load | It needs something playing in Spotify; start a song and tap **Refresh**. |
| YouTube search says no key | Add your YouTube API key in Settings. |
| OBD scan finds nothing | Ignition on, adapter plugged in, Bluetooth permission allowed; unplug and replug the adapter. |
| Gauges update slowly | Normal on older cars; raise the polling options in Settings. |
| Codes won't clear | Clear with the ignition on and the engine off. If the fault is still there, the code returns. |

---

## Build it yourself

You need [Android Studio](https://developer.android.com/studio) and [Git](https://git-scm.com).

1. **Get the code:** `git clone https://github.com/Dappermanphoto/CarToon.git`, then in Android Studio
   choose **File → Open** and pick the folder. Let Gradle sync finish.
2. **Run on your phone:** turn on **Developer options → USB debugging** on the phone, plug it in,
   pick the phone and the **mobile** configuration at the top, and press **Run**.
3. **Try it on a car screen without a car:** install the *Android Auto Desktop Head Unit* from the
   Android Studio SDK Manager (SDK Tools tab), start the head unit server in Android Auto's
   developer settings on the phone, then run the DHU on your PC.
4. The Spotify App Remote library is already included in `mobile/libs`.

Your own keys never go into the code. They are typed into the app's Settings.

## Credits and privacy
- Built with Jetpack Compose, Media3, the Spotify App Remote and Auth libraries, android-youtube-player,
  and Material icons (Apache 2.0).
- Trouble-code names come from [obd-codes.com](https://www.obd-codes.com). Only the short title is
  read and cached on your phone. Please visit the site for the full articles.
- Your YouTube key, Spotify Client ID, profile, wallpapers and settings are stored only on your phone.
  CarToon has no server and sends no analytics. The network requests it makes are to YouTube,
  Spotify, obd-codes.com and the photos Spotify provides.
- OBD tools can read and clear codes on your car. Use them at your own risk, and never operate the
  app while driving.
