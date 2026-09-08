package com.iotmon.simulator;

import com.iotmon.simulator.config.SimulatorProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * 裝置模擬器的進入點。獨立應用，只透過 MQTT 與平台溝通。
 *
 * <p>刻意不依賴 iot-application 與 iot-infrastructure：模擬器一旦能直接呼叫平台的內部程式碼，
 * 壓測走的就不是真實裝置的路徑，量出來的數字也就沒有意義。
 */
@SpringBootApplication
@EnableConfigurationProperties(SimulatorProperties.class)
public class SimulatorApplication {

    public static void main(String[] args) {
        SpringApplication.run(SimulatorApplication.class, args);
    }
}
