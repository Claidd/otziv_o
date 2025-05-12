//package com.hunt.otziv.text_generator.service;
//
//import lombok.RequiredArgsConstructor;
//import lombok.extern.slf4j.Slf4j;
//import org.openqa.selenium.JavascriptExecutor;
//import org.openqa.selenium.WebDriver;
//import org.openqa.selenium.chrome.ChromeDriver;
//import org.openqa.selenium.chrome.ChromeOptions;
//import org.springframework.stereotype.Service;
//import io.github.bonigarcia.wdm.WebDriverManager;
//
//import java.time.Duration;
//import java.util.List;
//
//@Service
//@Slf4j
//@RequiredArgsConstructor
//public class SeleniumSiteParserServiceImpl implements SeleniumSiteParserService{
//    public String getRenderedText(String url) {
//        WebDriverManager.chromedriver().setup();
//
//        ChromeOptions options = new ChromeOptions();
//        options.addArguments("--headless=new"); // Новый headless-режим
//        options.addArguments("--disable-gpu");
//        options.addArguments("--no-sandbox");
//        options.addArguments("--disable-blink-features=AutomationControlled");
//        options.setExperimentalOption("excludeSwitches", List.of("enable-automation"));
//        options.setExperimentalOption("useAutomationExtension", false);
//
//        WebDriver driver = new ChromeDriver(options);
//
//        try {
//            driver.manage().timeouts().pageLoadTimeout(Duration.ofSeconds(10));
//            driver.get(url);
//
//            // Удалить признак автоматизации из JS
//            ((JavascriptExecutor) driver).executeScript(
//                    "Object.defineProperty(navigator, 'webdriver', {get: () => undefined})");
//
//            Thread.sleep(3000); // Подождать загрузку динамического контента
//            return driver.getPageSource(); // Можно обернуть в Jsoup.parse() при желании
//        } catch (Exception e) {
//            e.printStackTrace();
//            return "⚠️ Ошибка при загрузке страницы.";
//        } finally {
//            driver.quit();
//        }
//    }
//}
