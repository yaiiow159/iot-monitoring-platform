package com.iotmon.application.port.out;

import com.iotmon.domain.telemetry.TelemetryPoint;

import java.util.List;

/**
 * 遙測寫入埠。
 *
 * <p>刻意只提供批次介面，沒有單筆版本。每秒五萬點若逐筆寫入，
 * 光是往返次數就會讓連線池耗盡——而只要介面上存在單筆方法，
 * 就一定會有人在迴圈裡呼叫它。不提供，就不會發生。
 */
public interface TelemetryWriter {

    /**
     * 批次寫入。
     *
     * <p>實作必須保證：部分失敗時要嘛整批重試、要嘛明確回報哪些失敗，
     * 不可以安靜地寫入一半——時序資料的缺口沒有辦法從別處回推。
     *
     * @return 實際寫入的筆數
     */
    int write(List<TelemetryPoint> points);
}
