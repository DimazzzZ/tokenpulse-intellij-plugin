# 🐛 Debugging Guide

This document describes how to debug the TokenPulse plugin in both development and production.

## 🔍 Log Locations

### Production
IntelliJ's `idea.log` contains plugin messages. You can find it via **Help → Show Log in Finder/Explorer**.

- **macOS**: `~/Library/Logs/JetBrains/IntelliJIdea{version}/idea.log`
- **Windows**: `%APPDATA%\JetBrains\IntelliJIdea{version}\log\idea.log`
- **Linux**: `~/.cache/JetBrains/IntelliJIdea{version}/log/idea.log`

### Development
When running via `./gradlew runIde`, logs are printed to the terminal and stored in the sandbox directory.

## 🛠️ Enabling Debug Logging

To see detailed logs for TokenPulse, go to **Help → Diagnostic Tools → Debug Log Settings...** and add:
```
org.zhavoronkov.tokenpulse:DEBUG
```

## 🚨 Common Debug Scenarios

### 1. Account Refresh Failures
Check logs for `HttpClientService` or `BalanceRefreshService`.
- **401 Unauthorized**: Mismatched API key or expired token.
- **404 Not Found**: Incorrect API endpoint (check provider implementation).

### 2. Status Bar Not Updating
Check if `BalanceUpdatedTopic` is firing.
- Ensure `BalanceUpdatedListener` is properly subscribed in `TokenPulseStatusBarWidget`.

### 3. Settings Persistence
Settings are stored in `options/tokenpulse.xml`.
- In the development sandbox: `build/idea-sandbox/config/options/tokenpulse.xml`.
- In production: Check the `options` folder in your IDE's config directory.

## 🔧 Useful Commands

```bash
# Monitor TokenPulse logs in real-time
tail -f idea.log | grep TokenPulse
```
