# Push Notifications Setup

Push notifications require Firebase Cloud Messaging (FCM).

## Setup Steps

1. Create Firebase project at https://console.firebase.google.com
2. Add Android app with package `com.hermes.messenger`
3. Download `google-services.json` to `android/app/`
4. Add FCM dependency to `build.gradle.kts`:
   ```kotlin
   implementation("com.google.firebase:firebase-messaging-ktx:23.4.0")
   ```
5. Add to `build.gradle.kts`:
   ```kotlin
   id("com.google.gms.google-services")
   ```

## Current Status

- WebSocket provides real-time updates when connected
- Push notifications needed only when app is in background
- Server needs to send FCM messages when AI responds

## Implementation

```kotlin
// HermesMessagingService.kt
class HermesMessagingService : FirebaseMessagingService() {
    override fun onNewToken(token: String) {
        // Send token to server
    }
    
    override fun onMessageReceived(message: RemoteMessage) {
        // Show notification
    }
}
```

Server endpoint needed:
```
POST /api/register-push
Body: {"fcm_token": "..."}
```
