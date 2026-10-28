package dev.friendai.text;

/** An error whose text the player should see. {@link #getMessage()} is English, for logs. */
public class UserFacingException extends RuntimeException {
    private final UserMessage userMessage;

    public UserFacingException(UserMessage message) {
        super(message.toString());
        this.userMessage = message;
    }

    public UserMessage userMessage() {
        return userMessage;
    }
}
