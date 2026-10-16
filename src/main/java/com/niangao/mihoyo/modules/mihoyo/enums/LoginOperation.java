package com.niangao.mihoyo.modules.mihoyo.enums;

public enum LoginOperation {
    SMS("sms"),
    LOGIN("login");
    private final String value;

    LoginOperation(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }
}
