package com.iotmon.domain.auth;

/**
 * 角色。刻意只有三種：再細的權限矩陣在內部系統裡通常變成沒人敢動的負擔。
 *
 * <ul>
 *   <li>ADMIN：改設定（機型、機櫃、規則、使用者）、看稽核</li>
 *   <li>OPERATOR：註冊裝置、調整監控樹；不能改會影響所有裝置的設定</li>
 *   <li>VIEWER：只看</li>
 * </ul>
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
