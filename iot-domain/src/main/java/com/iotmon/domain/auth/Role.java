package com.iotmon.domain.auth;

/**
 * 角色刻意只有三種，再細的權限矩陣在內部系統裡通常變成沒人敢動的負擔。
 * ADMIN 改設定與看稽核；OPERATOR 註冊裝置、調整監控樹；VIEWER 只看。
 */
public enum Role {
    ADMIN, OPERATOR, VIEWER;

    /** 能不能改影響全體裝置的設定 */
    public boolean canConfigure() {
        return this == ADMIN;
    }

    /** 能不能動單一裝置與監控樹 */
    public boolean canOperate() {
        return this == ADMIN || this == OPERATOR;
    }
}
