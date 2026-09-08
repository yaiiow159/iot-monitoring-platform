package com.iotmon.domain.device;

import com.iotmon.domain.model.DeviceModel;
import com.iotmon.domain.model.MetricDefinition;
import com.iotmon.domain.model.MetricKey;
import com.iotmon.domain.telemetry.TelemetryPoint;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * 裝置聚合根。
 *
 * <p>負責兩件事：狀態轉移的合法性，以及「這筆遙測是不是這台裝置該回報的」。
 * 後者是資料品質的第一道關卡——機型沒定義的指標一旦被放進時序表，
 * 它就會在字典表裡多出一個沒人認得的 metric_id，而且沒有任何地方會報錯。
 */
public final class Device {

    private final Long id;
    private final DeviceId deviceId;
    private final String serialNo;
    private final DeviceModel model;
    private final Long cabinetId;
    private final Short slotNo;

    private DeviceStatus status;
    private Instant lastSeenAt;

    private Device(Long id, DeviceId deviceId, String serialNo, DeviceModel model,
                   Long cabinetId, Short slotNo, DeviceStatus status, Instant lastSeenAt) {
        this.id = id;
        this.deviceId = deviceId;
        this.serialNo = serialNo;
        this.model = model;
        this.cabinetId = cabinetId;
        this.slotNo = slotNo;
        this.status = status;
        this.lastSeenAt = lastSeenAt;
    }

    public static Device register(Long id, DeviceId deviceId, String serialNo, DeviceModel model,
                                  Long cabinetId, Short slotNo) {
        Objects.requireNonNull(deviceId, "裝置識別碼不可為 null");
        Objects.requireNonNull(model, "裝置必須指定機型");
        if (serialNo == null || serialNo.isBlank()) {
            throw new IllegalArgumentException("裝置序號不可為空：" + deviceId);
        }
        // 有機櫃就必須有槽位，反之亦然。只有其一的狀態沒有實體意義，
        // 而它會讓機櫃檢視畫面出現一台「在這個櫃子裡但不知道在哪一格」的裝置。
        if ((cabinetId == null) != (slotNo == null)) {
            throw new IllegalArgumentException("機櫃與槽位必須同時提供或同時省略：" + deviceId);
        }
        return new Device(id, deviceId, serialNo, model, cabinetId, slotNo,
                DeviceStatus.UNKNOWN, null);
    }

    public static Device rehydrate(Long id, DeviceId deviceId, String serialNo, DeviceModel model,
                                   Long cabinetId, Short slotNo, DeviceStatus status, Instant lastSeenAt) {
        return new Device(id, deviceId, serialNo, model, cabinetId, slotNo,
                Objects.requireNonNullElse(status, DeviceStatus.UNKNOWN), lastSeenAt);
    }

    /**
     * 套用狀態轉移。
     *
     * @return 狀態是否真的改變了。呼叫端據此決定要不要發事件——
     *         重送同樣的狀態是常態（LWT retain、心跳補網），不該每次都推播。
     */
    public boolean transitionTo(DeviceStatus target, Instant at) {
        Objects.requireNonNull(target, "目標狀態不可為 null");
        if (!status.canTransitionTo(target)) {
            throw new IllegalStateException(
                    "非法的狀態轉移：" + deviceId + " " + status + " → " + target);
        }
        if (status == target) {
            return false;
        }
        status = target;
        if (target.isConnected()) {
            lastSeenAt = at;
        }
        return true;
    }

    /**
     * 收到遙測。回報本身就是「還活著」的證據，因此順帶更新 lastSeenAt。
     *
     * @return 這筆讀數是否落在機型定義的量程內；超出量程代表感測異常
     */
    public boolean acceptTelemetry(TelemetryPoint point) {
        Objects.requireNonNull(point, "遙測不可為 null");
        if (!point.deviceId().equals(deviceId)) {
            throw new IllegalArgumentException(
                    "遙測的裝置識別碼與本裝置不符：" + point.deviceId() + " vs " + deviceId);
        }
        MetricDefinition definition = model.metric(point.metric())
                .orElseThrow(() -> new IllegalArgumentException(
                        "機型 " + model.code() + " 未定義指標 " + point.metric()));

        lastSeenAt = point.timestamp();
        return definition.isWithinRange(point.value());
    }

    /**
     * 是否已超過允許的靜默時間。
     *
     * <p>這是 LWT 的補網，不是主要的斷線偵測手段——broker 自己重啟時遺言不會送出，
     * 只有這條路徑能發現。判斷依據見 ADR-0004。
     */
    public boolean isSilentBeyond(Duration threshold, Instant now) {
        if (!status.isConnected()) {
            return false;
        }
        if (lastSeenAt == null) {
            return true;
        }
        return Duration.between(lastSeenAt, now).compareTo(threshold) > 0;
    }

    public boolean reports(MetricKey metric) {
        return model.reports(metric);
    }

    public Long id() {
        return id;
    }

    public DeviceId deviceId() {
        return deviceId;
    }

    public String serialNo() {
        return serialNo;
    }

    public DeviceModel model() {
        return model;
    }

    public Optional<Long> cabinetId() {
        return Optional.ofNullable(cabinetId);
    }

    public Optional<Short> slotNo() {
        return Optional.ofNullable(slotNo);
    }

    public DeviceStatus status() {
        return status;
    }

    public Optional<Instant> lastSeenAt() {
        return Optional.ofNullable(lastSeenAt);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof Device device && deviceId.equals(device.deviceId);
    }

    @Override
    public int hashCode() {
        return deviceId.hashCode();
    }

    @Override
    public String toString() {
        return "Device[" + deviceId + " " + model.code() + " " + status + "]";
    }
}
