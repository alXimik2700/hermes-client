# Privacy Policy — Hermes Messenger

**Effective Date:** June 28, 2026  
**Last Updated:** June 28, 2026

Hermes Messenger ("Application") is an open-source messaging application. This Privacy Policy describes how the Application handles data when you use it.

## 1. Data Collection

The Application collects only the data strictly necessary for its functionality:

### 1.1 Data You Provide
- **Text messages** — stored locally on your device and transmitted to your self-hosted server
- **Voice messages** — recorded via microphone, processed for text-to-speech, transmitted to your server
- **Files and images** — selected by you for sharing, transmitted to your server
- **Profile information** — display name and avatar, stored locally

### 1.2 Data Collected Automatically
- **Network state** — checked to determine connectivity for message delivery
- **Device identifiers** — a unique client UUID generated locally for session management (not transmitted to third parties)

### 1.3 Camera Access
- Used exclusively for QR code scanning during device setup
- Camera data is processed on-device only (Google ML Kit) and is never stored or transmitted

## 2. Data Storage

- All messages are stored in an encrypted local database (Room/SQLite) on your device
- API credentials are stored in Android Keystore via EncryptedSharedPreferences (AES-256-GCM)
- No data is stored on third-party servers

## 3. Data Transmission

- All communication occurs between your device and your self-hosted server
- The Application connects to the server URL configured during setup
- All data in transit is encrypted via HTTPS/TLS

## 4. Data Sharing

The Application does **NOT** share, sell, or transmit your data to:
- Third-party analytics services
- Advertising networks
- Data brokers
- Social media platforms

The Application does not contain any third-party analytics, advertising, or tracking SDKs.

## 5. Data Retention

- Messages are retained on your device until you delete them
- Voice messages are processed in real-time and not stored permanently on the server
- Local cache is cleared when you clear the Application's storage

## 6. Your Rights

You have the right to:
- **Access** — view all data stored locally by the Application
- **Delete** — clear all local data via Application settings or device settings
- **Export** — messages can be exported from the server
- **Revoke permissions** — camera, microphone, and storage permissions can be revoked at any time in device settings

## 7. Children's Privacy

The Application is not directed at children under 13 years of age. We do not knowingly collect data from children.

## 8. Security

- End-to-end encryption is available (Signal Protocol architecture, pending full implementation)
- Local data encrypted with AES-256-GCM
- All network communication via TLS 1.2+
- Open-source codebase — security can be audited by anyone

## 9. Changes to This Policy

We may update this Privacy Policy from time to time. Changes will be posted in the Application and on the GitHub repository. Continued use of the Application after changes constitutes acceptance of the updated policy.

## 10. Contact

For questions about this Privacy Policy:
- GitHub Issues: https://github.com/hermes-messenger/hermes-client/issues
- Email: [developer email]

## 11. Open Source

Hermes Messenger is open-source software. The full source code is available at https://github.com/hermes-messenger/hermes-client, allowing independent verification of all data handling practices described in this policy.
