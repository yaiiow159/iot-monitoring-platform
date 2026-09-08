package com.iotmon.simulator.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 模擬器的全部旋鈕。壓測時要調的東西集中在這裡，不散落在各個元件的常數裡。
 */
@ConfigurationProperties(prefix = "simulator")
public record SimulatorProperties(

        @DefaultValue("1000") int deviceCount,

        @DefaultValue("1000") long publishIntervalMs,

        @DefaultValue("DEV-") String deviceIdPrefix,

        /** 發佈用的排程執行緒數，0 表示取 CPU 核心數。 */
        @DefaultValue("0") int publisherThreads,

        /**
         * 亂數種子。固定種子讓「哪些裝置故障、數值怎麼漂移」在每次執行都一樣，
         * 否則兩次壓測的差異分不清是改動造成的還是抽樣造成的。
         */
        @DefaultValue("20260909") long seed,

        @DefaultValue Mqtt mqtt,

        @DefaultValue Fault fault
) {

    public SimulatorProperties {
        if (deviceCount <= 0) {
            throw new IllegalArgumentException("simulator.device-count 必須大於 0：" + deviceCount);
        }
        if (publishIntervalMs <= 0) {
            throw new IllegalArgumentException("simulator.publish-interval-ms 必須大於 0：" + publishIntervalMs);
        }
    }

    public int resolvedPublisherThreads() {
        return publisherThreads > 0 ? publisherThreads : Runtime.getRuntime().availableProcessors();
    }

    public record Mqtt(

            @DefaultValue("localhost") String host,

            @DefaultValue("1883") int port,

            @DefaultValue("sim") String clientIdPrefix,

            /**
             * 每條 MQTT 連線承載幾台裝置。1 表示一台一條，斷線偵測的粒度最細。
             * 取捨的理由見 {@code MqttConnectionPool} 的類別註解。
             */
            @DefaultValue("1") int devicesPerConnection,

            /** 全部連線共用的 Netty 執行緒數，0 表示取 CPU 核心數。 */
            @DefaultValue("0") int nettyThreads,

            /** 啟動時同時進行的連線數上限，避免一萬條 CONNECT 同時打到 broker 被拒。 */
            @DefaultValue("200") int connectConcurrency,

            @DefaultValue("60") int keepAliveSeconds
    ) {

        public Mqtt {
            if (devicesPerConnection <= 0) {
                throw new IllegalArgumentException(
                        "simulator.mqtt.devices-per-connection 必須大於 0：" + devicesPerConnection);
            }
            if (connectConcurrency <= 0) {
                throw new IllegalArgumentException(
                        "simulator.mqtt.connect-concurrency 必須大於 0：" + connectConcurrency);
            }
        }

        public int resolvedNettyThreads() {
            return nettyThreads > 0 ? nettyThreads : Math.max(2, Runtime.getRuntime().availableProcessors());
        }
    }

    public record Fault(

            @DefaultValue("0") double offlineFlappingRatio,

            @DefaultValue("0") double outOfRangeRatio,

            @DefaultValue("0") double clockDriftRatio,

            @DefaultValue("0") double silentRatio,

            @DefaultValue("0") double spikeRatio,

            /** CLOCK_DRIFT 的時間戳落後秒數。 */
            @DefaultValue("45") int clockDriftSeconds,

            /** OFFLINE_FLAPPING 的線上與離線時長。 */
            @DefaultValue("30000") long flapUpMs,

            @DefaultValue("15000") long flapDownMs,

            /**
             * SILENT 裝置先正常回報這段時間再停止。一開始就不說話跟「從未連上」無法區分，
             * 平台也就看不到「從有資料變成沒資料」這個轉折。
             */
            @DefaultValue("60000") long silenceAfterMs,

            /** SPIKE 每次取樣出現尖峰的機率。 */
            @DefaultValue("0.02") double spikeProbability
    ) {

        public Fault {
            double total = offlineFlappingRatio + outOfRangeRatio + clockDriftRatio + silentRatio + spikeRatio;
            // 一台裝置只會被指派一種故障，比例加總超過 1 就代表設定者以為可以疊加，那是誤解不是筆誤
            if (total > 1.0 + 1e-9) {
                throw new IllegalArgumentException("simulator.fault.* 各比例加總不可超過 1：" + total);
            }
            if (offlineFlappingRatio < 0 || outOfRangeRatio < 0 || clockDriftRatio < 0
                    || silentRatio < 0 || spikeRatio < 0) {
                throw new IllegalArgumentException("simulator.fault.* 比例不可為負");
            }
        }
    }
}
