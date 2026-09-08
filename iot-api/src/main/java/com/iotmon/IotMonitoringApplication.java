package com.iotmon;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 平台主程式。
 *
 * <p>入站配接器（REST／WebSocket）、MQTT 橋接、Kafka 消費端與寫入路徑
 * 目前跑在同一個行程裡。這是刻意的：在還沒量到瓶頸之前拆成多個服務，
 * 只會用分散式的複雜度換取想像中的擴充性。
 *
 * <p>真的要拆的時候，切分線已經畫好了——各個消費端本來就只透過 Kafka
 * 與其他部分溝通，抽出去不需要改動業務邏輯。
 */
@SpringBootApplication
@EnableScheduling
public class IotMonitoringApplication {

    public static void main(String[] args) {
        SpringApplication.run(IotMonitoringApplication.class, args);
    }
}
