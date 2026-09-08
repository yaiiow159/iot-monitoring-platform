package com.iotmon.simulator.catalog;

import com.iotmon.domain.model.DeviceModel;
import com.iotmon.domain.model.MetricDefinition;
import com.iotmon.domain.model.ModelCode;

import java.util.List;

/**
 * 模擬機型目錄。三種機型的指標組合與量程刻意不同，
 * 這樣才驗證得到「同一條入庫路徑要吃得下不同形狀的訊息」。
 */
public final class SimulatedModels {

    /** 溫濕度感測器：訊息最小、數量最多，代表典型的環境監測點。 */
    public static final SimulatedModel TH_100 = temperatureHumiditySensor();

    /** 三相電力模組：指標多一個，且功率因數是 0~1 的小數，會壓出不同的序列化成本。 */
    public static final SimulatedModel PWR_3P = threePhasePowerModule();

    /** 機櫃控制器：指標最多，含一個開關量。每則訊息五個點，是壓測的流量大戶。 */
    public static final SimulatedModel CAB_CTRL = cabinetController();

    private static final List<SimulatedModel> ALL = List.of(TH_100, PWR_3P, CAB_CTRL);

    /**
     * 機型分佈。真實場域的感測器遠多於控制器，用固定樣板而不是抽樣，
     * 讓「一萬台裝置共產生多少資料點」是可預測的數字，而不是每次執行都不同。
     */
    private static final SimulatedModel[] MIX = buildMix();

    private SimulatedModels() {
    }

    public static List<SimulatedModel> all() {
        return ALL;
    }

    public static SimulatedModel forIndex(int deviceIndex) {
        return MIX[Math.floorMod(deviceIndex, MIX.length)];
    }

    private static SimulatedModel[] buildMix() {
        SimulatedModel[] mix = new SimulatedModel[20];
        for (int i = 0; i < 12; i++) {
            mix[i] = TH_100;          // 60%
        }
        for (int i = 12; i < 17; i++) {
            mix[i] = PWR_3P;          // 25%
        }
        for (int i = 17; i < 20; i++) {
            mix[i] = CAB_CTRL;        // 15%
        }
        return mix;
    }

    private static SimulatedModel temperatureHumiditySensor() {
        MetricDefinition temperature = MetricDefinition.of("temperature", "°C", -20, 80);
        MetricDefinition humidity = MetricDefinition.of("humidity", "%", 0, 100);
        DeviceModel model = DeviceModel.of(
                ModelCode.of("TH-100"), "IotMon", "溫濕度感測器",
                List.of(temperature, humidity));
        return new SimulatedModel(model, List.of(
                // 機房恆溫在 18~32°C；量程開到 80°C 是為了讓感測器故障有地方可去
                MetricProfile.walk(temperature, 18, 32, 0.010, 2),
                MetricProfile.walk(humidity, 40, 70, 0.008, 1)));
    }

    private static SimulatedModel threePhasePowerModule() {
        MetricDefinition voltage = MetricDefinition.of("voltage", "V", 0, 500);
        MetricDefinition current = MetricDefinition.of("current", "A", 0, 200);
        MetricDefinition powerFactor = MetricDefinition.of("power_factor", "無單位", 0, 1);
        DeviceModel model = DeviceModel.of(
                ModelCode.of("PWR-3P"), "IotMon", "三相電力模組",
                List.of(voltage, current, powerFactor));
        return new SimulatedModel(model, List.of(
                MetricProfile.walk(voltage, 370, 390, 0.006, 1),
                // 負載波動比電壓大得多，volatility 跟著放大，否則畫出來的電流是一條假的直線
                MetricProfile.walk(current, 20, 90, 0.020, 2),
                MetricProfile.walk(powerFactor, 0.86, 0.99, 0.008, 3)));
    }

    private static SimulatedModel cabinetController() {
        MetricDefinition temperature = MetricDefinition.of("temperature", "°C", -20, 80);
        MetricDefinition humidity = MetricDefinition.of("humidity", "%", 0, 100);
        MetricDefinition voltage = MetricDefinition.of("voltage", "V", 0, 500);
        MetricDefinition current = MetricDefinition.of("current", "A", 0, 200);
        MetricDefinition powerFactor = MetricDefinition.of("power_factor", "無單位", 0, 1);
        MetricDefinition doorOpen = MetricDefinition.of("door_open", "無單位", 0, 1);
        MetricDefinition fanRpm = MetricDefinition.of("fan_rpm", "RPM", 0, 6000);
        DeviceModel model = DeviceModel.of(
                ModelCode.of("CAB-CTRL"), "IotMon", "機櫃控制器",
                List.of(temperature, humidity, voltage, current, powerFactor, doorOpen, fanRpm));
        return new SimulatedModel(model, List.of(
                MetricProfile.walk(temperature, 20, 34, 0.010, 2),
                MetricProfile.walk(humidity, 40, 70, 0.008, 1),
                MetricProfile.walk(voltage, 370, 390, 0.006, 1),
                MetricProfile.walk(current, 20, 90, 0.020, 2),
                MetricProfile.walk(powerFactor, 0.86, 0.99, 0.008, 3),
                // 門禁用隨機遊走會產生 0.37 這種說不通的值，開關量必須另外處理
                MetricProfile.binary(doorOpen, 0.0008),
                MetricProfile.walk(fanRpm, 1800, 3600, 0.015, 0)));
    }
}
