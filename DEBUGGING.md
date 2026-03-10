# 🐛 Debugging Guide

This document describes how to debug the TokenPulse β plugin in both development and production.

## 🔍 Log Locations

### Production
IntelliJ's `idea.log` contains plugin messages. You can find it via **Help → Show Log in Finder/Explorer**.

- **macOS**: `~/Library/Logs/JetBrains/IntelliJIdea{version}/idea.log`
- **Windows**: `%APPDATA%\JetBrains\IntelliJIdea{version}\log\idea.log`
- **Linux**: `~/.cache/JetBrains/IntelliJIdea{version}/log/idea.log`

### Development
When running via `./gradlew runIde`, logs are printed to the terminal and stored in the sandbox directory.

## 🛠️ Enabling Debug Logging

### Method 1: VM Options (Recommended)
1. Go to **Help → Edit Custom VM Options**
2. Add these lines:
   ```
   -Dtokenpulse.debug=true
   -Didea.log.debug.categories=org.zhavoronkov.tokenpulse
   ```
3. Restart IntelliJ IDEA

### Method 2: Registry / Debug Log Settings
1. Go to **Help → Diagnostic Tools → Debug Log Settings...**
2. Add:
   ```
   org.zhavoronkov.tokenpulse:DEBUG
   ```
3. Restart IntelliJ IDEA

## 🕵️ Diagnostic Traces

When troubleshooting network or connection issues (like Nebius timeouts), look for `[TRACE]` entries in the log. Each operation has a unique `traceId`.

Example trace block:
```
[TokenPulse][TRACE] traceId=abc12345 provider=NEBIUS account=acc-1 stage=start Starting balance fetch
[TokenPulse][TRACE] traceId=abc12345 provider=NEBIUS account=acc-1 stage=session_validate data={appSession=true, csrfCookie=true, csrfToken=true, parentId=true}
[TokenPulse][TRACE] traceId=abc12345 provider=NEBIUS account=acc-1 stage=strategy_start data={strategy=Standard}
[TokenPulse][TRACE] traceId=abc12345 provider=NEBIUS account=acc-1 stage=strategy_fail data={error=SocketTimeoutException, message=Read timed out}
```

### HTTP Transport Tracing
For even deeper network analysis (DNS, TLS, Proxy), enable HTTP tracing by adding this VM option:
```
-Dtokenpulse.httpTrace=true
```
This adds `[HTTP-TRACE]` logs showing granular request lifecycles.

## 📊 Debug Categories

The plugin uses the following logger categories:
- `org.zhavoronkov.tokenpulse.service` - Balance refresh, coordinator, HTTP client
- `org.zhavoronkov.tokenpulse.provider` - Provider clients (Nebius, OpenRouter, Cline)
- `org.zhavoronkov.tokenpulse.settings` - Settings persistence, credentials
- `org.zhavoronkov.tokenpulse.ui` - UI dialogs, widgets, notifications

## 🔍 Monitoring Commands (macOS/Linux)

```bash
# Monitor all TokenPulse activity
tail -f idea.log | grep TokenPulse

# Filter for Nebius-specific errors
tail -f idea.log | grep -i nebius

# Filter for debug/trace messages
tail -f idea.log | grep -E "\[DEBUG\]|\[TRACE\]"
```
