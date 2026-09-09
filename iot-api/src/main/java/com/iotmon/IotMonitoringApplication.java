package com.iotmon;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 入站配接器、MQTT 橋接、Kafka 消費端跑在同一個行程：量到瓶頸之前先不拆。
 * 切分線已經畫好——各消費端只透過 Kafka 溝通，抽出去不必改業務邏輯。
 */
@SpringBootApplication
@EnableScheduling
public class IotMonitoringApplication {

    public static void main(String[] args) {
        SpringApplication.run(IotMonitoringApplication.class, args);
    }
}
