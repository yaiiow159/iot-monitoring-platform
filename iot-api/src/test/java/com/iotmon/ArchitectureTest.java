package com.iotmon;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * 分層依賴的強制檢查。
 *
 * <p>這些規則存在的理由是：違反它們不會有任何立即症狀。
 * 領域層 import 一個 Spring 註解，程式照跑，測試照過——
 * 直到有一天要換框架或想單獨測業務規則，才發現整層已經黏死。
 *
 * <p><b>不要為了讓測試通過而放寬規則。</b>規則被放寬過一次，就再也不會收回去。
 */
class ArchitectureTest {

    private static JavaClasses classes;

    @BeforeAll
    static void importClasses() {
        classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.iotmon");
    }

    @Test
    @DisplayName("領域層不得依賴任何框架")
    void domainHasNoFrameworkDependencies() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("..domain..")
                .should().dependOnClassesThat()
                .resideInAnyPackage(
                        "org.springframework..",
                        "jakarta.persistence..",
                        "com.fasterxml.jackson..",
                        "org.apache.kafka..",
                        "com.hivemq..",
                        "io.micrometer..")
                .because("領域層一旦沾上框架，業務規則就會開始配合框架的形狀變形");

        rule.check(classes);
    }

    @Test
    @DisplayName("領域層不得依賴應用層或基礎設施層")
    void domainDependsOnNothingInternal() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("..domain..")
                .should().dependOnClassesThat()
                .resideInAnyPackage("..application..", "..infrastructure..", "..api..")
                .because("依賴方向只能由外往內");

        rule.check(classes);
    }

    @Test
    @DisplayName("應用層不得依賴基礎設施層或 API 層")
    void applicationDoesNotDependOnOuterLayers() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("..application..")
                .should().dependOnClassesThat()
                .resideInAnyPackage("..infrastructure..", "..api..")
                .because("應用層只認得 Port 介面，換掉資料庫或訊息佇列不該動到它");

        rule.check(classes);
    }

    @Test
    @DisplayName("應用層只允許 spring-context 與 spring-tx，不得碰 web 或 data")
    void applicationUsesOnlyMinimalSpring() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("..application..")
                .should().dependOnClassesThat()
                .resideInAnyPackage(
                        "org.springframework.web..",
                        "org.springframework.data..",
                        "org.springframework.jdbc..",
                        "org.springframework.kafka..")
                .because("應用層要能在沒有資料庫、沒有 broker 的情況下用 mock 測完");

        rule.check(classes);
    }

    @Test
    @DisplayName("領域層不得使用 java.util.logging 之外的日誌框架")
    void domainDoesNotLog() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("..domain..")
                .should().dependOnClassesThat()
                .resideInAnyPackage("org.slf4j..", "ch.qos.logback..", "org.apache.logging..")
                .because("領域層應該用拋例外或回傳值表達問題，而不是寫日誌");

        rule.check(classes);
    }
}
