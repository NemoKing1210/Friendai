package dev.friendai.features.appearance;

import java.util.UUID;

/**
 * Whether an appearance operation may still send its result. The session that started the fetch or the file dialog is
 * the only one allowed to upload: a reconnect in between must not paint the new server's companion with an image the
 * player chose for the old one.
 */
public final class SkinUploadSession {
    private SkinUploadSession() {}

    public static boolean allows(UUID expectedSession, UUID currentSession, boolean appearanceSupported) {
        return expectedSession != null && expectedSession.equals(currentSession) && appearanceSupported;
    }
}
