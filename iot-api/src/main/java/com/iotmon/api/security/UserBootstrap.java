package com.iotmon.api.security;

import com.iotmon.domain.auth.Role;
import com.iotmon.infrastructure.auth.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * 第一次啟動時建立三個示範帳號，之後只要表裡有人就不再碰。
 * 密碼從設定檔來，日誌會提醒去改；一個只能用預設密碼登入的系統等於沒有登入。
 */
@Component
public class UserBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(UserBootstrap.class);

    private final UserRepository users;
    private final PasswordEncoder encoder;
    private final String adminPassword;
    private final String operatorPassword;
    private final String viewerPassword;

    public UserBootstrap(UserRepository users, PasswordEncoder encoder,
                         @Value("${iot.auth.bootstrap.admin-password}") String adminPassword,
                         @Value("${iot.auth.bootstrap.operator-password}") String operatorPassword,
                         @Value("${iot.auth.bootstrap.viewer-password}") String viewerPassword) {
        this.users = users;
        this.encoder = encoder;
        this.adminPassword = adminPassword;
        this.operatorPassword = operatorPassword;
        this.viewerPassword = viewerPassword;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (users.count() > 0) {
            return;
        }
        users.insert("admin", encoder.encode(adminPassword), Role.ADMIN, "系統管理員");
        users.insert("operator", encoder.encode(operatorPassword), Role.OPERATOR, "值班工程師");
        users.insert("viewer", encoder.encode(viewerPassword), Role.VIEWER, "訪客");
        log.warn("已建立示範帳號 admin／operator／viewer，密碼來自 iot.auth.bootstrap.*，正式環境請改掉");
    }
}
