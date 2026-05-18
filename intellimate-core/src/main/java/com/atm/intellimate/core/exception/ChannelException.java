package com.atm.intellimate.core.exception;

public class ChannelException extends IntelliMateException {

    private final String channelId;

    public ChannelException(String channelId, String message) {
        super("CHANNEL_ERROR", "[" + channelId + "] " + message);
        this.channelId = channelId;
    }

    public ChannelException(String channelId, String message, Throwable cause) {
        super("CHANNEL_ERROR", "[" + channelId + "] " + message, cause);
        this.channelId = channelId;
    }

    public String getChannelId() {
        return channelId;
    }
}
