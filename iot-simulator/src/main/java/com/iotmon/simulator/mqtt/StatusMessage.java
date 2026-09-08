package com.iotmon.simulator.mqtt;

/** 狀態訊息。{@code state} 只有 ONLINE／OFFLINE 兩種值，見 docs/contracts.md。 */
public record StatusMessage(String deviceId, String state, long ts) {

    public static final String ONLINE = "ONLINE";
    public static final String OFFLINE = "OFFLINE";
}
