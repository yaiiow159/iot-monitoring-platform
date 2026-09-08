package com.iotmon.infrastructure.live;

import java.io.IOException;

/**
 * 一條可以被推播的連線。
 *
 * <p>infrastructure 不該認得 {@code WebSocketSession}——那是 iot-api 的入站配接器。
 * 這個介面把「送一段文字給某個訂閱者」抽出來，registry 只依賴它；
 * WebSocket 的實作在 api 層，測試時則可以用一個記錄送出內容的假物件。
 * 換成 SSE 或 gRPC stream 時，registry 一行都不用改。
 */
public interface LiveSubscriber {

    /** 在 registry 內唯一的識別 */
    String id();

    boolean isOpen();

    /**
     * 送出一段 JSON。實作必須自行處理同一連線上的並行寫入
     * （WebSocket 的 sendMessage 不是執行緒安全的）。
     *
     * @throws IOException 送不出去時；registry 會據此把這條連線斷掉
     */
    void send(String json) throws IOException;

    /** 關閉連線。用在送不出去的慢連線上；失敗也不必回報，已經沒有更糟的了。 */
    void close();
}
