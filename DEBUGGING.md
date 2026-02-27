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

Alternatively, you can enable debug logging via system property:
```
-Dtokenpulse.debug=true
```

### Debug Categories

The plugin uses the following logger categories:
- `org.zhavoronkov.tokenpulse.service` - Balance refresh, coordinator, HTTP client
- `org.zhavoronkov.tokenpulse.provider` - Provider clients (Nebius, OpenRouter, Cline)
- `org.zhavoronkov.tokenpulse.settings` - Settings persistence, credentials
- `org.zhavoronkov.tokenpulse.ui` - UI dialogs, widgets, notifications

### Debug Output Format

Debug messages are prefixed with `[DEBUG]`:
```
[TokenPulse][DEBUG] Starting balance fetch for account abc123 (NEBIUS)
[TokenPulse][DEBUG] Parsed Nebius session for account abc123: appSession=✓, csrfCookie=✓, csrfToken=✓, parentId=✓
[TokenPulse][DEBUG] Making Nebius request for account abc123: POST /api-mfe/billing/gateway/root/billingActs/getCurrentTrial
[TokenPulse][DEBUG] Nebius response for account abc123: 200 OK (1234ms)
```

Error messages include full stack traces:
```
[TokenPulse] ERROR Nebius network error for account abc123
java.net.ConnectException: Connection refused
    at okhttp3.internal.connection.RealConnection.initiateRealSocket(RealConnection.kt:112)
    ...
```

## 🚨 Common Debug Scenarios

### 1. Account Refresh Failures
Check logs for `BalanceRefreshService` and provider clients.

**Enable debug logging** to see detailed diagnostics:
```
[TokenPulse][DEBUG] Starting refreshAll (force=false)
[TokenPulse][DEBUG] Found 2 enabled accounts to refresh
[TokenPulse][DEBUG] refreshAccount called: accountId=abc123, force=false
[TokenPulse][DEBUG] Starting balance fetch for account abc123 (NEBIUS)
[TokenPulse][DEBUG] Parsed Nebius session for account abc123: appSession=✓, csrfCookie=✓, csrfToken=✓, parentId=✓
[TokenPulse][DEBUG] Making Nebius request for account abc123: POST /api-mfe/billing/gateway/root/billingActs/getCurrentTrial
[TokenPulse][DEBUG] Nebius response for account abc123: 401 Unauthorized (234ms)
[TokenPulse] WARN Nebius authentication failed for account abc123: 401 Unauthorized
{
  "error": "session_expired"
}
[TokenPulse][DEBUG] refreshAccount completed: accountId=abc123, result=Failure$AuthError
```

**Common issues:**
- **401/403 Unauthorized**: Session expired - reconnect via Settings → Accounts → Edit
- **429 Too Many Requests**: Rate limit exceeded - wait and retry
- **Connection refused**: Network issue or Nebius API unavailable
- **JSON parse error**: Response format changed - check Nebius API docs

### 2. Nebius Session Extraction Issues

If the Nebius connect dialog fails to capture the session:

**Console Script Troubleshooting:**
1. Open DevTools Console on `https://tokenfactory.nebius.com/`
2. Paste the extraction script and run it
3. Check for error messages:
   - `Missing required cookies` → Not logged in
   - `Could not extract parentId` → Navigate to a contract page first
4. Verify the JSON output contains all 4 fields: `appSession`, `csrfCookie`, `csrfToken`, `parentId`

**Common extraction failures:**
- **Not logged in**: Ensure you're logged into Nebius before running the script
- **Wrong page**: Navigate to a contract page (URL contains `/p/<contract-id>/`) before extraction
- **Cookies blocked**: Check browser privacy settings allow Nebius cookies
- **Script errors**: Check console for JavaScript errors

**Manual extraction fallback:**
If the script fails, you can manually extract values:
```javascript
// Cookies
document.cookie  // Look for __Host-app_session and __Host-psifi.x-csrf-token

// ParentId from URL
window.location.pathname  // Should contain /p/<parentId>/

// Or from page state
window.__NEBIUS__?.contractId
```

### 3. Settings Loading Failures

If settings fail to load, check for:
- Corrupted `tokenpulse.xml` in IDE config directory
- Invalid providerId or authType values

**Debug logs will show:**
```
[TokenPulse] ERROR Failed to load settings, using defaults
java.lang.IllegalArgumentException: Invalid enum value
```

**Recovery:**
- Delete `tokenpulse.xml` and reconfigure accounts
- Check IDE logs at `Help → Show Log in Finder/Explorer`

### 4. Status Bar Not Updating
Check if `BalanceUpdatedTopic` is firing.
- Ensure `BalanceUpdatedListener` is properly subscribed in `TokenPulseStatusBarWidget`.

### 5. Settings Persistence
Settings are stored in `options/tokenpulse.xml`.
- In the development sandbox: `build/idea-sandbox/config/options/tokenpulse.xml`.
- In production: Check the `options` folder in your IDE's config directory.

## 🔧 Useful Commands

```bash
# Monitor TokenPulse logs in real-time
tail -f idea.log | grep TokenPulse

# Filter for Nebius-specific errors
tail -f idea.log | grep -i nebius

# Filter for debug messages
tail -f idea.log | grep "\[DEBUG\]"

# Filter for authentication errors
tail -f idea.log | grep -i "auth\|unauthorized\|expired"

# Filter for settings errors
tail -f idea.log | grep -i "settings\|loadState"
```
