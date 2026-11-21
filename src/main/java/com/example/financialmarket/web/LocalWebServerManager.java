package com.example.financialmarket.web;

public class LocalWebServerManager {
    public static void start() {
        try {
            LocalHttpServer.start();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static void stop() {
        LocalHttpServer.stop();
    }
}