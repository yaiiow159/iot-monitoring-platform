package com.iotmon.simulator.fault;

/**
 * 故障模式。每台裝置最多一種——真實故障也很少同時發生，
 * 而且疊加之後就分不出是哪一種讓告警響的。
 */
public enum FaultType {

    /** 正常運轉。 */
    NONE,

    /** 間歇斷線重連。用來驗證 LWT 斷線偵測與狀態機不會卡在錯誤的狀態。 */
    OFFLINE_FLAPPING,

    /** 讀數持續超出機型量程，模擬感測器或線路故障。 */
    OUT_OF_RANGE,

    /** 時間戳落後數十秒，用來驗證平台是以裝置取樣時間而不是接收時間入庫。 */
    CLOCK_DRIFT,

    /** 連線還在但停止回報。LWT 抓不到這種故障，只能靠心跳逾時補網。 */
    SILENT,

    /** 偶發尖峰值，用來觸發告警規則。 */
    SPIKE
}
