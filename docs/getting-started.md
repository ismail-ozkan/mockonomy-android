# Getting Started — Connecting Your App to Mockonomy

> Everything in this guide was **verified on a real device**
> (Samsung Galaxy A72, Android 14 / API 34). The in-app setup guide shows the same steps.

Mockonomy serves mock responses on `http://127.0.0.1:<port>`, on the same device as the
app you are testing. There is one thing you have to do in your app before it can connect:
allow cleartext HTTP to loopback.

---

## The problem

Since Android 9 (API 28) **cleartext HTTP is blocked by default**. Mockonomy serves over
`http://127.0.0.1:8080`, so an app built with the default configuration cannot connect to
it.

## The error you will see

```
java.net.UnknownServiceException
CLEARTEXT communication to 127.0.0.1 not permitted by network security policy
```

If you see this error, **nothing is wrong with the server**. Mockonomy is running; your
app's network policy is blocking the request.

> Quick check: if `adb shell curl http://127.0.0.1:8080/ping` works but your app does not,
> this is definitely the problem. `adb shell` is not an app, so the network policy does not
> apply to it.

---

## The fix

### 1. Add a network security configuration file

`app/src/main/res/xml/network_security_config.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<network-security-config>
    <domain-config cleartextTrafficPermitted="true">
        <domain includeSubdomains="false">127.0.0.1</domain>
        <domain includeSubdomains="false">localhost</domain>
    </domain-config>
</network-security-config>
```

### 2. Reference it from the manifest

In `app/src/main/AndroidManifest.xml`, on the `<application>` tag:

```xml
<application
    android:networkSecurityConfig="@xml/network_security_config"
    ... >
```

### 3. Rebuild and reinstall the app

The configuration is packaged at build time; restarting the app is not enough.

---

## Notes

**Only loopback is opened.** The exception above only covers `127.0.0.1` and `localhost`.
The rest of your app's traffic keeps requiring HTTPS.

**Do not use `android:usesCleartextTraffic="true"`.** It is tempting because it is a single
line, but it allows **all** cleartext traffic in the app. The approach above, which only
opens loopback, is the right one.

**Put it in the debug build only.** The production build does not need this configuration.
If you put the file under `app/src/debug/res/xml/`, the release build is not affected.

**If you already have a configuration**, do not create a new one; add the `domain-config`
block above to your existing `network-security-config`.

**On an emulator `127.0.0.1` is the emulator's.** The emulator's loopback belongs to the
emulator, not to the host machine. As long as Mockonomy runs on the same emulator, this is
fine.

---

## Verify

1. Start a server in Mockonomy and check the address in the notification
2. Request `http://127.0.0.1:8080/ping` from your app (the example environment has this route)
3. The expected response:

```
HTTP 200
{"status":"ok"}
```

Still not working?
- Is the **port** in the notification the same as the port in your request?
- Was your app rebuilt and reinstalled?
- Is Mockonomy's notification still visible (the server may have stopped)?
- Open **Setup guide** in the app and run **Test connection** — it checks each step and
  tells you what to fix.

---

## Reference app

The `test-client/` module in this repository is a minimal app that applies this guide. Its
configuration file and manifest line can be seen there as they are; it is kept as a
permanent manual test fixture.
