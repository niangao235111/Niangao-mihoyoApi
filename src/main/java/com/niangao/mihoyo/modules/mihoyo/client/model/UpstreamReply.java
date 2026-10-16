package com.niangao.mihoyo.modules.mihoyo.client.model;

import com.google.gson.JsonObject;

/** Client 内部抓取的协议响应，不直接暴露给 Controller。 */
public class UpstreamReply {
    private int httpStatus;
    private JsonObject body;
    private String aigis;
    private String traceId;
    public String getTraceId() { return traceId; }
    public void setTraceId(String value) { traceId = value; }

    public int getHttpStatus() {
        return httpStatus;
    }

    public void setHttpStatus(int value) {
        httpStatus = value;
    }

    public JsonObject getBody() {
        return body;
    }

    public void setBody(JsonObject value) {
        body = value;
    }

    public String getAigis() {
        return aigis;
    }

    public void setAigis(String value) {
        aigis = value;
    }
}
