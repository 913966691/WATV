package com.github.tvbox.osc.dlna;

/**
 * DLNA 设备信息
 */
public class DlnaDevice {
    public String deviceType;      // urn:schemas-upnp-org:device:MediaRenderer:1
    public String friendlyName;    // 设备显示名称（如 "客厅电视"）
    public String modelName;       // 型号名称
    public String manufacturer;    // 制造商
    public String location;        // 设备描述 XML URL
    public String controlUrl;      // AVTransport 控制 URL
    public String eventUrl;        // AVTransport 事件订阅 URL
    public String renderingControlUrl;   // RenderingControl 控制 URL
    public String renderingEventUrl;     // RenderingControl 事件订阅 URL
    public String usn;             // 唯一序列号

    public boolean isValid() {
        return friendlyName != null && controlUrl != null;
    }

    @Override
    public String toString() {
        return friendlyName != null ? friendlyName : "未知设备";
    }
}
