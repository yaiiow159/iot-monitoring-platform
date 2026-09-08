package com.iotmon.domain.cabinet;

import com.iotmon.domain.shared.Guard;
import com.iotmon.domain.model.ModelCode;

import java.util.Collections;
import java.util.Set;

/**
 * 機櫃聚合根。
 *
 * <p>它的核心職責是回答一個問題：**這台裝置可以裝進這個槽位嗎？**
 * 沒有這道檢查的話，「配電櫃裡插了一台溫濕度感測器」這種設定錯誤
 * 要等到有人看報表覺得數字怪怪的才會發現，而那可能是幾個月後。
 */
public final class Cabinet {

    private final Long id;
    private final String code;
    private final CabinetType type;
    private final String location;
    private final short slotCount;
    private final Set<ModelCode> acceptedModels;

    private Cabinet(Long id, String code, CabinetType type, String location,
                    short slotCount, Set<ModelCode> acceptedModels) {
        this.id = id;
        this.code = code;
        this.type = type;
        this.location = location;
        this.slotCount = slotCount;
        this.acceptedModels = acceptedModels;
    }

    public static Cabinet of(Long id, String code, CabinetType type, String location,
                             short slotCount, Set<ModelCode> acceptedModels) {
        Guard.notBlank(code, "機櫃代號");
        Guard.notNull(type, "機櫃類型");
        Guard.positive(slotCount, "機櫃 " + code + " 的槽位數");
        if (acceptedModels == null || acceptedModels.isEmpty()) {
            // 不接受任何機型的機櫃裝不了東西，建立它只會在設定畫面上製造困惑
            throw new IllegalArgumentException("機櫃類型至少要接受一種機型：" + code);
        }
        return new Cabinet(id, code, type, location, slotCount,
                Collections.unmodifiableSet(Set.copyOf(acceptedModels)));
    }

    /** 槽位編號是否在這個機櫃的範圍內。槽位從 1 開始編號，與實體標示一致。 */
    public boolean hasSlot(short slotNo) {
        return slotNo >= 1 && slotNo <= slotCount;
    }

    public boolean accepts(ModelCode modelCode) {
        return modelCode != null && acceptedModels.contains(modelCode);
    }

    /**
     * 檢查裝置能否安裝在指定槽位，不行就說明原因。
     *
     * <p>回傳訊息而不是布林值：設定畫面需要告訴使用者「為什麼不行」，
     * 只回 false 的話呼叫端得再自己判斷一次原因，那份判斷遲早會與這裡分歧。
     *
     * @return null 代表可以安裝，否則回傳不可安裝的原因
     */
    public String rejectReasonFor(ModelCode modelCode, short slotNo) {
        if (!hasSlot(slotNo)) {
            return "槽位 " + slotNo + " 超出機櫃 " + code + " 的範圍（1~" + slotCount + "）";
        }
        if (!accepts(modelCode)) {
            return "機櫃類型 " + type + " 不接受機型 " + modelCode;
        }
        return null;
    }

    public Long id() {
        return id;
    }

    public String code() {
        return code;
    }

    public CabinetType type() {
        return type;
    }

    public String location() {
        return location;
    }

    public short slotCount() {
        return slotCount;
    }

    public Set<ModelCode> acceptedModels() {
        return acceptedModels;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof Cabinet cabinet && code.equals(cabinet.code);
    }

    @Override
    public int hashCode() {
        return code.hashCode();
    }
}
