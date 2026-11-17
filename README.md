# RemoteApp

Remote view + full control (tap/swipe/type/open/close) of a whitelisted set
of apps on your own phone, from a second device.

## How it fits together

```
Phone (this app)              Signaling server (Node.js)        Remote viewer (web page)
-----------------              ---------------------------        ------------------------
ScreenCaptureService  <--WS-->  server.js (pairing/relay)  <--WS-->  remote-viewer/index.html
  - MediaProjection                                                  - shows video
  - WebRTC video track                                                - sends tap/swipe/type
  - WebRTC data channel  <--------------- WebRTC peer-to-peer -------------->  over the data channel
RemoteControlAccessibilityService
  - injects taps/swipes/text, gated by RemoteCommandProcessor
WhitelistManager
  - the only apps that can ever be launched/closed/controlled
```

## Getting the APK

1. Push this whole folder to a GitHub repo.
2. GitHub Actions (`.github/workflows/build-apk.yml`) builds automatically on
   push to `main`, or run it manually from the Actions tab.
3. Download `remoteapp-debug-apk` from the finished workflow run — that's
   your installable APK. It's a **debug** build, meant for your own device;
   you'll need to allow "install unknown apps" for your browser/file manager.

## Deploying the signaling server

The server in `signaling-server/` needs to run somewhere that keeps a
WebSocket connection open — a small VPS, Fly.io, Render, Railway, etc.
Classic shared PHP-style hosting won't work for this.

```
cd signaling-server
npm install
node server.js
```

Use `wss://` (not `ws://`) once it's behind TLS, which most of those hosts
give you automatically.

## Using the remote viewer

Host `remote-viewer/index.html` anywhere (even a GitHub Pages branch), or
just open it locally while testing. Enter the same signaling server URL and
a pairing code on both the phone and the viewer page to connect them.

## One-time setup on the phone (can't be automated — this is intentional on Android's part)

1. Install the APK, open it.
2. Tap **Enable Accessibility Service** → turn on "RemoteApp" in the system
   Accessibility settings. This is what allows tap/swipe/type injection.
3. Tap **Grant Usage Access** → allow "RemoteApp" in Usage Access settings.
   This is how the app checks which app is in the foreground, so control
   commands never fire outside the apps you whitelisted.
4. Tap **Choose Whitelisted Apps** and pick the apps you want remotely
   launchable/controllable.
5. Enter the signaling server URL + a pairing code, tap **Start Sharing**,
   approve the system screen-capture prompt.

## What's simplified / needs your testing

This is a complete, working scaffold, not a polished production app. A few
things worth knowing before you rely on it:

- **WebRTC library version**: `io.github.webrtc-sdk:android` is pinned to a
  recent version at the time of writing. If Gradle can't resolve it, check
  for the latest version and update `app/build.gradle`.
- **TURN server**: only a public STUN server is configured. If your phone
  and viewer are on different networks with strict NATs, the WebRTC
  connection may fail to establish — you'd then need a TURN server (e.g.
  coturn) and add it to the `iceServers` list in both `ScreenCaptureService.kt`
  and `remote-viewer/index.html`.
- **"Close app"**: Android does not let a normal app force-close another
  app. `killBackgroundProcesses` is best-effort — most apps close cleanly,
  some may linger or relaunch themselves.
- **FLAG_SECURE**: if you ever whitelist an app that sets this flag (mainly
  banking/payment apps), its video will show as black. There's no app-level
  way around this — it's enforced by Android itself.
- **No authentication beyond the pairing code**: anyone who has your
  signaling server URL and guesses/learns the pairing code could join a
  session. For real use, add a per-session random code (not a fixed one)
  and consider a shared secret/token check in `server.js`.

## Project layout

```
app/                      Android app module (Kotlin + Jetpack Compose)
signaling-server/         Node.js WebSocket relay
remote-viewer/            Web page for the remote/viewing device
.github/workflows/        CI that builds the APK for you
```
