# End-to-End Encryption

## Current Status

Messages are encrypted in transit (HTTPS/Tailscale) but not end-to-end. Server can read message content.

## Proposed Architecture

### Signal Protocol (Recommended)

Use libsignal or Mautrix for E2E encryption:

1. **Key Exchange**: X3DH (Extended Triple Diffie-Hellman)
2. **Messaging**: Double Ratchet Algorithm
3. **Group**: Sender Keys

### Implementation Plan

1. **Client-side key generation**:
   ```kotlin
   val keyPair = SignalProtocolKeyHelper.generateIdentityKeyPair()
   ```

2. **Key exchange on first connect**:
   - Client uploads public key to server
   - Server stores key, forwards to other devices

3. **Message encryption**:
   ```kotlin
   val cipher = SessionCipher(sessionStore, address)
   val ciphertext = cipher.encrypt(message.toByteArray())
   ```

4. **Server stores encrypted blobs**:
   - Server only sees encrypted data
   - Decryption happens on client

### Trade-offs

| Feature | Current | With E2E |
|---------|---------|----------|
| Server can read messages | Yes | No |
| Search on server | Yes | No |
| Voice processing | Server-side | Needs local processing |
| Multi-device sync | Simple | Complex key management |

## Recommendation

For personal use with trusted server: current HTTPS + Tailscale is sufficient.
For maximum privacy: implement Signal Protocol.
