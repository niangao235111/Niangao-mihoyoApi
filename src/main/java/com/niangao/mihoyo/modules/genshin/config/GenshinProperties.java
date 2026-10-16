package com.niangao.mihoyo.modules.genshin.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 仅服务端配置上游地址及公开 DS 配对；网页不能指定请求目标。 */
@ConfigurationProperties(prefix = "genshin")
public class GenshinProperties {
    private String recordEndpoint = "https://api-takumi-record.mihoyo.com";
    private String dsSalt = "xV8v4Qu54lUKrEYFZkJhB8cuOh9Asafs";
    private String appVersion = "2.11.1";
    private String deviceFpEndpoint = "https://public-data-api.mihoyo.com";
    private String userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/118.0.0.0 Safari/537.36";

    public String getRecordEndpoint() { return recordEndpoint; }
    public void setRecordEndpoint(String value) { recordEndpoint = value; }
    public String getDsSalt() { return dsSalt; }
    public void setDsSalt(String value) { dsSalt = value; }
    public String getAppVersion() { return appVersion; }
    public void setAppVersion(String value) { appVersion = value; }
    public String getDeviceFpEndpoint() { return deviceFpEndpoint; }
    public void setDeviceFpEndpoint(String value) { deviceFpEndpoint = value; }
    public String getUserAgent() { return userAgent; }
    public void setUserAgent(String value) { userAgent = value; }
}
