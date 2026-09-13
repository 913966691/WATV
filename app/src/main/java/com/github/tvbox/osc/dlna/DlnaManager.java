package com.github.tvbox.osc.dlna;

import android.content.Context;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.MulticastSocket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * DLNA 投屏管理器（轻量自实现，无第三方依赖）
 * 
 * 功能：
 * 1. SSDP M-SEARCH 发现局域网内的 DMR 渲染器（电视/盒子）
 * 2. 获取设备描述 XML，解析 deviceType、modelName、controlURL
 * 3. 发送 SetAVTransportURI + Play SOAP 动作，实现投屏
 */
public class DlnaManager {
    private static final String TAG = "DlnaManager";
    private static final String SSDP_ADDR = "239.255.255.250";
    private static final int SSDP_PORT = 1900;
    private static final String SEARCH_TARGET = "urn:schemas-upnp-org:device:MediaRenderer:1";
    private static final int SEARCH_TIMEOUT_MS = 3000;
    /**
     * 投屏事件订阅端口:起一个最小 HTTP server 接收 Macast/DMR 推过来的 NOTIFY 事件。
     * 之前用 <http://0.0.0.0:0/> 占位,Macast 收到后会报 WinError 10049(地址无效),
     * 导致 App 端永远拿不到真实播放状态变化回调,这是 DLNA 投屏多个边界场景异常的根因。
     * 选 9980 是为了避开 RemoteServer 9978/9979 端口(它们已被占用)。
     */
    private static final int EVENT_CALLBACK_PORT = 9980;

    private final Context mContext;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final AtomicBoolean mSearching = new AtomicBoolean(false);
    private final Map<String, DlnaDevice> mDevices = new ConcurrentHashMap<>();
    private final OkHttpClient mHttpClient = new OkHttpClient.Builder()
            .connectTimeout(5000, java.util.concurrent.TimeUnit.MILLISECONDS)
            .readTimeout(5000, java.util.concurrent.TimeUnit.MILLISECONDS)
            .build();

    /**
     * 接收 DMR NOTIFY 事件用的最小 HTTP server。
     * Android 上用 ServerSocket + 简单 HTTP 解析实现,避免引入 NanoHTTPD/OkHttp Server 等额外依赖。
     * 关键修复:之前 SUBSCRIBE 时用 CALLBACK: <http://0.0.0.0:0/>,
     * DMR 会按这个 URL 推 NOTIFY,0.0.0.0:0 在 PC 上无法绑定 → WinError 10049。
     * 现在改成 <http://手机LAN_IP:9980/upnp/event>,DMR 能正常回调。
     */
    private EventCallbackServer mEventServer;

    private SearchCallback mSearchCallback;
    private WifiManager.MulticastLock mMulticastLock;

    public interface SearchCallback {
        void onDeviceFound(DlnaDevice device);
    }

    public DlnaManager(Context context) {
        mContext = context.getApplicationContext();
    }

    /**
     * 开始搜索 DLNA 渲染器设备
     */
    public void startSearch(SearchCallback callback) {
        if (mSearching.get()) {
            Log.w(TAG, "Already searching");
            return;
        }
        mSearchCallback = callback;
        mSearching.set(true);
        mDevices.clear();

        new Thread(this::performSearch, "DLNA-SSDP-Search").start();
    }

    /**
     * 停止搜索
     */
    public void stopSearch() {
        mSearching.set(false);
        releaseMulticastLock();
    }

    /**
     * 关闭事件回调 server。建议在用户停止投屏 / 退出详情页时调用,释放 9980 端口。
     */
    public void release() {
        stopSearch();
        if (mEventServer != null) {
            mEventServer.shutdown();
            mEventServer = null;
            Log.d(TAG, "EventCallbackServer released");
        }
    }

    /**
     * 投屏到指定设备
     * 关键：如果 URL 是本地回环地址(127.0.0.1)，必须替换为手机局域网IP，否则设备端无法访问
     */
    public void cast(DlnaDevice device, String url, String title, CastCallback callback) {
        new Thread(() -> {
            try {
                // 把 127.0.0.1 替换为手机局域网IP，让 DLNA 设备能访问
                String castUrl = resolveLocalIp(url);
                Log.d(TAG, "Original URL: " + url);
                Log.d(TAG, "Setting AVTransportURI: " + castUrl);

                // 1. 先 SUBSCRIBE 事件通知（Macast 依赖此步骤才真正拉流）
                subscribeEvents(device);

                // 2. SetAVTransportURI + Play
                setAVTransportURI(device, castUrl, title);
                play(device);

                // 3. 开始轮询播放状态，保持会话活跃
                startStatusPolling(device);

                mHandler.post(() -> callback.onSuccess());
            } catch (Exception e) {
                Log.e(TAG, "Cast failed", e);
                mHandler.post(() -> callback.onError(e.getMessage()));
            }
        }, "DLNA-Cast").start();
    }

    /**
     * 订阅 AVTransport 和 RenderingControl 事件通知
     * 关键:先确保 9980 端口的 EventCallbackServer 启动,否则 DMR 第一次 NOTIFY 会 connection refused。
     */
    private void subscribeEvents(DlnaDevice device) {
        ensureEventServerStarted();
        if (device.eventUrl != null) {
            try {
                subscribeToUrl(device.eventUrl, "AVTransport");
            } catch (Exception e) {
                Log.w(TAG, "Subscribe AVTransport failed (non-fatal)", e);
            }
        }
        if (device.renderingEventUrl != null) {
            try {
                subscribeToUrl(device.renderingEventUrl, "RenderingControl");
            } catch (Exception e) {
                Log.w(TAG, "Subscribe RenderingControl failed (non-fatal)", e);
            }
        }
    }

    /**
     * 发送 SUBSCRIBE 请求
     * 修复:把 CALLBACK 从占位的 <http://0.0.0.0:0/> 改为真实可达的
     * <http://手机LAN_IP:9980/upnp/event>,让 DMR(Macast/电视/盒子)能正常回调 NOTIFY 事件。
     */
    private void subscribeToUrl(String eventUrl, String serviceName) throws Exception {
        // 取手机真实 LAN IP(不是 127.0.0.1,否则 DMR 还是访问不到)
        String localIp = com.github.tvbox.osc.server.RemoteServer.getLocalIPAddress(mContext);
        // UPnP 规范要求 CALLBACK 必须是合法 URL,且支持多个 callback 嵌套在 <>
        String callback = "<http://" + localIp + ":" + EVENT_CALLBACK_PORT + "/upnp/event>";

        Request request = new Request.Builder()
                .url(eventUrl)
                .method("SUBSCRIBE", null)
                .addHeader("CALLBACK", callback)
                .addHeader("NT", "upnp:event")
                .addHeader("TIMEOUT", "Second-300")
                .addHeader("HOST", new java.net.URL(eventUrl).getHost() + ":" + new java.net.URL(eventUrl).getPort())
                .build();

        Log.d(TAG, "SUBSCRIBE " + serviceName + " -> " + eventUrl + " CALLBACK=" + callback);
        try (Response response = mHttpClient.newCall(request).execute()) {
            Log.d(TAG, "SUBSCRIBE " + serviceName + " response: HTTP " + response.code());
        }
    }

    /**
     * 启动事件回调 server(端口 9980),用于接收 DMR(Macast/电视)推过来的 NOTIFY 事件。
     * 这个 server 必须先起来,否则 SUBSCRIBE 时即便 CALLBACK 写对了,DMR 第一次 NOTIFY
     * 也会 connection refused。
     */
    private void ensureEventServerStarted() {
        if (mEventServer != null && mEventServer.isServerAlive()) {
            return;
        }
        mEventServer = new EventCallbackServer();
        mEventServer.start();
        Log.d(TAG, "EventCallbackServer started on port " + EVENT_CALLBACK_PORT);
    }

    /**
     * 最小 HTTP server,只处理 NOTIFY /upnp/event 请求,丢弃 body,返回 200。
     * 用 Android 自带 java.net.ServerSocket 实现,不依赖第三方库。
     */
    private class EventCallbackServer extends Thread {
        private java.net.ServerSocket mSocket;
        private volatile boolean mAlive = true;

        EventCallbackServer() {
            super("DLNA-EventServer");
        }

        /**
         * 重命名,避免和 Thread.isAlive() final 方法冲突。
         * 原来叫 isAlive() 编译报错"被覆盖的方法为 final"。
         */
        boolean isServerAlive() {
            return mAlive && mSocket != null && !mSocket.isClosed();
        }

        @Override
        public void run() {
            try {
                mSocket = new java.net.ServerSocket(EVENT_CALLBACK_PORT);
                while (mAlive) {
                    try {
                        final java.net.Socket client = mSocket.accept();
                        new Thread(() -> handleNotify(client), "DLNA-EventHandler").start();
                    } catch (java.io.IOException e) {
                        if (mAlive) Log.w(TAG, "EventServer accept error", e);
                    }
                }
            } catch (java.io.IOException e) {
                Log.e(TAG, "EventCallbackServer failed to bind port " + EVENT_CALLBACK_PORT, e);
            }
        }

        private void handleNotify(java.net.Socket client) {
            try (java.io.InputStream in = client.getInputStream();
                 java.io.OutputStream out = client.getOutputStream()) {
                // 读 HTTP 请求头(直到 \r\n\r\n),body 不需要解析,只关心 path
                java.io.BufferedReader br = new java.io.BufferedReader(
                        new java.io.InputStreamReader(in, "UTF-8"));
                String line;
                String nt = null;
                String nts = null;
                String sid = null;
                int contentLength = 0;
                while ((line = br.readLine()) != null && !line.isEmpty()) {
                    if (line.startsWith("NT:")) nt = line.substring(3).trim();
                    else if (line.startsWith("NTS:")) nts = line.substring(4).trim();
                    else if (line.startsWith("SID:")) sid = line.substring(4).trim();
                    else if (line.toLowerCase().startsWith("content-length:")) {
                        try { contentLength = Integer.parseInt(line.substring(15).trim()); }
                        catch (NumberFormatException ignore) {}
                    }
                }
                // 把 body 也读掉,否则 DMR 会卡在写
                if (contentLength > 0) {
                    byte[] buf = new byte[contentLength];
                    int read = 0;
                    while (read < contentLength) {
                        int n = in.read(buf, read, contentLength - read);
                        if (n < 0) break;
                        read += n;
                    }
                    String body = new String(buf, 0, read, "UTF-8");
                    Log.d(TAG, "NOTIFY received NTS=" + nts + " SID=" + sid
                            + " bodyLen=" + contentLength);
                    // ★ 把投屏状态变更 post 到主线程,UI 可以订阅做提示/暂停本地播放等
                    final String fNts = nts;
                    final String fSid = sid;
                    final String fBody = body;
                    mHandler.post(() -> {
                        if (mEventCallback != null) {
                            mEventCallback.onNotify(fNts, fSid, fBody);
                        }
                    });
                } else {
                    Log.d(TAG, "NOTIFY received (no body) NTS=" + nts + " SID=" + sid);
                }
                // 返回 200 OK
                String resp = "HTTP/1.1 200 OK\r\nContent-Length: 0\r\nConnection: close\r\n\r\n";
                out.write(resp.getBytes("UTF-8"));
                out.flush();
            } catch (Exception e) {
                Log.d(TAG, "handleNotify error: " + e.getMessage());
            } finally {
                // 用全限定名 java.io.IOException,避免编译器报"找不到符号"
                try { client.close(); } catch (java.io.IOException ignore) {}
            }
        }

        void shutdown() {
            mAlive = false;
            try { if (mSocket != null) mSocket.close(); } catch (java.io.IOException ignore) {}
        }
    }

    private EventNotifyCallback mEventCallback;

    /**
     * 设置 NOTIFY 事件回调,UI 层可订阅做"投屏已开始/已暂停/已结束"提示,
     * 以及"投屏后暂停本地播放"等联动逻辑。
     */
    public void setEventNotifyCallback(EventNotifyCallback cb) {
        this.mEventCallback = cb;
    }

    public interface EventNotifyCallback {
        void onNotify(String nts, String sid, String body);
    }

    /**
     * 轮询播放状态，保持会话活跃（参考 Cling 库的标准做法）
     */
    private void startStatusPolling(DlnaDevice device) {
        new Thread(() -> {
            while (true) {
                try {
                    Thread.sleep(3000);
                    getTransportInfo(device);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    Log.d(TAG, "Status polling error (may be normal): " + e.getMessage());
                    break;
                }
            }
        }, "DLNA-Poll").start();
    }

    /**
     * 获取播放状态信息
     */
    private void getTransportInfo(DlnaDevice device) throws Exception {
        String soapBody = "<?xml version=\"1.0\" encoding=\"utf-8\"?>\r\n" +
                "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">\r\n" +
                "<s:Body>\r\n" +
                "<u:GetTransportInfo xmlns:u=\"urn:schemas-upnp-org:service:AVTransport:1\">\r\n" +
                "<InstanceID>0</InstanceID>\r\n" +
                "</u:GetTransportInfo>\r\n" +
                "</s:Body>\r\n" +
                "</s:Envelope>";

        RequestBody body = RequestBody.create(
                MediaType.parse("text/xml; charset=\"utf-8\""),
                soapBody);

        java.net.URL controlUrlObj = new java.net.URL(device.controlUrl);
        String hostHeader = controlUrlObj.getHost();
        if (controlUrlObj.getPort() != -1 && controlUrlObj.getPort() != 80) {
            hostHeader += ":" + controlUrlObj.getPort();
        }

        Request request = new Request.Builder()
                .url(device.controlUrl)
                .post(body)
                .addHeader("SOAPAction", "\"urn:schemas-upnp-org:service:AVTransport:1#GetTransportInfo\"")
                .addHeader("Content-Type", "text/xml; charset=\"utf-8\"")
                .addHeader("HOST", hostHeader)
                .addHeader("User-Agent", "TVBoxOS/1.0 UPnP/1.0")
                .addHeader("Connection", "close")
                .build();

        try (Response response = mHttpClient.newCall(request).execute()) {
            String responseBody = response.body() != null ? response.body().string() : "";
            Log.d(TAG, "GetTransportInfo response: " + responseBody);
        }
    }

    /**
     * 把 URL 中的 127.0.0.1 / localhost 替换为手机局域网 IP
     */
    private String resolveLocalIp(String url) {
        if (url == null) return null;
        if (url.contains("127.0.0.1") || url.contains("localhost")) {
            String localIp = com.github.tvbox.osc.server.RemoteServer.getLocalIPAddress(mContext);
            Log.d(TAG, "Replacing loopback address with LAN IP: " + localIp);
            url = url.replace("127.0.0.1", localIp);
            url = url.replace("localhost", localIp);
        }
        return url;
    }

    /**
     * 停止投屏（发送 Stop 动作）
     */
    public void stop(DlnaDevice device, CastCallback callback) {
        new Thread(() -> {
            try {
                stop(device);
                mHandler.post(() -> callback.onSuccess());
            } catch (Exception e) {
                Log.e(TAG, "Stop failed", e);
                mHandler.post(() -> callback.onError(e.getMessage()));
            }
        }, "DLNA-Stop").start();
    }

    public interface CastCallback {
        void onSuccess();
        void onError(String message);
    }

    // ===== 内部实现 =====

    private void performSearch() {
        MulticastSocket socket = null;
        try {
            acquireMulticastLock();

            // 关键修复: 客户端 M-SEARCH 必须从随机端口发出,不能绑 1900(那是服务器监听端口)。
            // 绑 1900 在 Android 上大概率失败或被防火墙拦截,导致收不到设备响应。
            socket = new MulticastSocket(0);
            socket.setReuseAddress(true);
            socket.setSoTimeout(SEARCH_TIMEOUT_MS);

            InetAddress group = InetAddress.getByName(SSDP_ADDR);
            socket.joinGroup(group);

            String searchMsg = "M-SEARCH * HTTP/1.1\r\n" +
                    "HOST: 239.255.255.250:1900\r\n" +
                    "MAN: \"ssdp:discover\"\r\n" +
                    "MX: 3\r\n" +
                    "ST: " + SEARCH_TARGET + "\r\n" +
                    "\r\n";

            byte[] searchData = searchMsg.getBytes(StandardCharsets.UTF_8);
            // 连发 2 次 M-SEARCH,间隔 200ms,提高发现率(设备可能错过第一包)
            DatagramPacket searchPacket = new DatagramPacket(searchData, searchData.length, group, SSDP_PORT);
            socket.send(searchPacket);
            Thread.sleep(200);
            socket.send(searchPacket);

            Log.d(TAG, "SSDP M-SEARCH x2 sent from localPort=" + socket.getLocalPort()
                    + " MX=3, waiting for responses...");

            byte[] buffer = new byte[4096];
            long deadline = System.currentTimeMillis() + SEARCH_TIMEOUT_MS;
            while (mSearching.get() && System.currentTimeMillis() < deadline) {
                try {
                    DatagramPacket receivePacket = new DatagramPacket(buffer, buffer.length);
                    socket.receive(receivePacket);

                    String response = new String(receivePacket.getData(), 0, receivePacket.getLength(), StandardCharsets.UTF_8);
                    Log.d(TAG, "SSDP response received from " + receivePacket.getAddress()
                            + ":" + receivePacket.getPort());
                    processResponse(response);
                } catch (SocketTimeoutException e) {
                    // 单次超时,继续等直到 deadline
                }
            }

            Log.d(TAG, "Search finished, found " + mDevices.size() + " device(s)");
            socket.leaveGroup(group);
        } catch (Exception e) {
            Log.e(TAG, "Search failed", e);
        } finally {
            if (socket != null && !socket.isClosed()) {
                try { socket.close(); } catch (Throwable ignored) {}
            }
            mSearching.set(false);
            releaseMulticastLock();
        }
    }

    private void processResponse(String response) {
        // 解析 SSDP 响应，提取 LOCATION
        String location = null;
        String usn = null;
        for (String line : response.split("\r\n")) {
            if (line.startsWith("LOCATION:")) {
                location = line.substring(9).trim();
            } else if (line.startsWith("USN:")) {
                usn = line.substring(4).trim();
            }
        }

        if (location == null) {
            return;
        }

        // 去重
        if (mDevices.containsKey(location)) {
            return;
        }

        final String finalLocation = location;
        final String finalUsn = usn;

        Log.d(TAG, "Found device at: " + finalLocation);

        // 异步获取设备描述
        new Thread(() -> {
            try {
                DlnaDevice device = fetchDeviceDescription(finalLocation, finalUsn);
                if (device != null && !mDevices.containsKey(finalLocation)) {
                    mDevices.put(finalLocation, device);
                    mHandler.post(() -> {
                        if (mSearchCallback != null) {
                            mSearchCallback.onDeviceFound(device);
                        }
                    });
                }
            } catch (Exception e) {
                Log.e(TAG, "Failed to fetch device description", e);
            }
        }, "DLNA-Desc").start();
    }

    private DlnaDevice fetchDeviceDescription(String location, String usn) throws Exception {
        Log.d(TAG, "Fetching device description from: " + location);
        Request request = new Request.Builder()
                .url(location)
                .get()
                .build();

        try (Response response = mHttpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                Log.e(TAG, "Device description HTTP failed: " + response.code());
                return null;
            }

            String xml = response.body() != null ? response.body().string() : "";
            Log.d(TAG, "Device description XML length=" + xml.length()
                    + ", first 500 chars: " + xml.substring(0, Math.min(500, xml.length())));
            DlnaDevice device = parseDeviceXml(xml, location, usn);
            if (device == null) {
                Log.e(TAG, "parseDeviceXml returned null for: " + location);
            } else {
                Log.d(TAG, "Device parsed: name=" + device.friendlyName + ", controlUrl=" + device.controlUrl);
            }
            return device;
        } catch (Exception e) {
            Log.e(TAG, "fetchDeviceDescription failed for " + location, e);
            throw e;
        }
    }

    private DlnaDevice parseDeviceXml(String xml, String location, String usn) {
        // 简单解析 XML（避免引入 XML 解析库）
        String deviceType = extractXmlTag(xml, "deviceType");
        String friendlyName = extractXmlTag(xml, "friendlyName");
        String modelName = extractXmlTag(xml, "modelName");
        String manufacturer = extractXmlTag(xml, "manufacturer");

        // 提取各服务的 controlURL 和 eventSubURL
        String avTransportService = extractXmlSection(xml, "AVTransport");
        String renderingControlService = extractXmlSection(xml, "RenderingControl");
        String connectionManagerService = extractXmlSection(xml, "ConnectionManager");

        String controlUrl = null;
        String eventUrl = null;
        String renderingControlUrl = null;
        String renderingEventUrl = null;

        if (avTransportService != null) {
            controlUrl = extractXmlTag(avTransportService, "controlURL");
            eventUrl = extractXmlTag(avTransportService, "eventSubURL");
        }
        if (renderingControlService != null) {
            renderingControlUrl = extractXmlTag(renderingControlService, "controlURL");
            renderingEventUrl = extractXmlTag(renderingControlService, "eventSubURL");
        }

        if (friendlyName == null || controlUrl == null) {
            return null;
        }

        // 构建完整的 URL（相对路径 → 绝对路径）
        String baseUrl = location.substring(0, location.lastIndexOf('/') + 1);
        controlUrl = resolveUrl(baseUrl, controlUrl);
        if (eventUrl != null) eventUrl = resolveUrl(baseUrl, eventUrl);
        if (renderingControlUrl != null) renderingControlUrl = resolveUrl(baseUrl, renderingControlUrl);
        if (renderingEventUrl != null) renderingEventUrl = resolveUrl(baseUrl, renderingEventUrl);

        DlnaDevice device = new DlnaDevice();
        device.deviceType = deviceType;
        device.friendlyName = friendlyName;
        device.modelName = modelName;
        device.manufacturer = manufacturer;
        device.location = location;
        device.controlUrl = controlUrl;
        device.eventUrl = eventUrl;
        device.renderingControlUrl = renderingControlUrl;
        device.renderingEventUrl = renderingEventUrl;
        device.usn = "uuid:" + (usn != null ? usn.split("::")[0].substring(5) : "");

        Log.d(TAG, "Parsed device: name=" + device.friendlyName
                + ", controlUrl=" + device.controlUrl
                + ", eventUrl=" + device.eventUrl
                + ", renderingControlUrl=" + device.renderingControlUrl);

        return device;
    }

    /** 把相对路径 URL 解析为绝对路径 */
    private String resolveUrl(String baseUrl, String url) {
        if (url == null) return null;
        if (url.startsWith("http")) return url;
        if (url.startsWith("/")) {
            return baseUrl.substring(0, baseUrl.indexOf('/', 7)) + url;
        }
        return baseUrl + url;
    }

    private String extractXmlTag(String xml, String tag) {
        // UPnP XML 经常带命名空间前缀, 如 <d:friendlyName> 或 <ns:friendlyName>
        // 用正则匹配 <任意前缀:tag> 或 <tag>
        java.util.regex.Pattern p = java.util.regex.Pattern.compile(
                "<(?:[a-zA-Z]+:)?" + tag + "[^>]*>([^<]*)</(?:[a-zA-Z]+:)?" + tag + ">",
                java.util.regex.Pattern.DOTALL);
        java.util.regex.Matcher m = p.matcher(xml);
        if (m.find()) {
            return m.group(1).trim();
        }
        return null;
    }

    private String extractXmlSection(String xml, String sectionName) {
        // 逐个遍历 <service>...</service> 块, 找包含 sectionName 的那个
        int searchFrom = 0;
        while (true) {
            int serviceStart = xml.indexOf("<service", searchFrom);
            if (serviceStart < 0) break;
            int serviceEnd = xml.indexOf("</service>", serviceStart);
            if (serviceEnd < 0) break;
            serviceEnd += 10; // include </service>
            String block = xml.substring(serviceStart, serviceEnd);
            if (block.contains(sectionName)) {
                return block;
            }
            searchFrom = serviceEnd;
        }
        return null;
    }

    private void setAVTransportURI(DlnaDevice device, String url, String title) throws Exception {
        String soapBody = "<?xml version=\"1.0\" encoding=\"utf-8\"?>\r\n" +
                "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">\r\n" +
                "<s:Body>\r\n" +
                "<u:SetAVTransportURI xmlns:u=\"urn:schemas-upnp-org:service:AVTransport:1\">\r\n" +
                "<InstanceID>0</InstanceID>\r\n" +
                "<CurrentURI>" + escapeXml(url) + "</CurrentURI>\r\n" +
                "<CurrentURIMetaData>" + buildDidlLite(title) + "</CurrentURIMetaData>\r\n" +
                "</u:SetAVTransportURI>\r\n" +
                "</s:Body>\r\n" +
                "</s:Envelope>";

        sendSoapAction(device, "SetAVTransportURI", soapBody);
    }

    private void play(DlnaDevice device) throws Exception {
        String soapBody = "<?xml version=\"1.0\" encoding=\"utf-8\"?>\r\n" +
                "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">\r\n" +
                "<s:Body>\r\n" +
                "<u:Play xmlns:u=\"urn:schemas-upnp-org:service:AVTransport:1\">\r\n" +
                "<InstanceID>0</InstanceID>\r\n" +
                "<Speed>1</Speed>\r\n" +
                "</u:Play>\r\n" +
                "</s:Body>\r\n" +
                "</s:Envelope>";

        sendSoapAction(device, "Play", soapBody);
    }

    private void stop(DlnaDevice device) throws Exception {
        String soapBody = "<?xml version=\"1.0\" encoding=\"utf-8\"?>\r\n" +
                "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">\r\n" +
                "<s:Body>\r\n" +
                "<u:Stop xmlns:u=\"urn:schemas-upnp-org:service:AVTransport:1\">\r\n" +
                "<InstanceID>0</InstanceID>\r\n" +
                "</u:Stop>\r\n" +
                "</s:Body>\r\n" +
                "</s:Envelope>";

        sendSoapAction(device, "Stop", soapBody);
    }

    private void sendSoapAction(DlnaDevice device, String action, String soapBody) throws Exception {
        RequestBody body = RequestBody.create(
                MediaType.parse("text/xml; charset=\"utf-8\""),
                soapBody);

        // 解析 device 的 host:port 用于 HOST 头
        java.net.URL controlUrlObj = new java.net.URL(device.controlUrl);
        String hostHeader = controlUrlObj.getHost();
        if (controlUrlObj.getPort() != -1 && controlUrlObj.getPort() != 80) {
            hostHeader += ":" + controlUrlObj.getPort();
        }

        Request request = new Request.Builder()
                .url(device.controlUrl)
                .post(body)
                .addHeader("SOAPAction", "\"urn:schemas-upnp-org:service:AVTransport:1#" + action + "\"")
                .addHeader("Content-Type", "text/xml; charset=\"utf-8\"")
                .addHeader("HOST", hostHeader)
                .addHeader("User-Agent", "TVBoxOS/1.0 UPnP/1.0")
                .addHeader("Connection", "close")
                .build();

        Log.d(TAG, "SOAP " + action + " -> " + device.controlUrl);
        Log.d(TAG, "SOAP body: " + soapBody);

        try (Response response = mHttpClient.newCall(request).execute()) {
            String responseBody = response.body() != null ? response.body().string() : "";
            Log.d(TAG, "SOAP " + action + " response: HTTP " + response.code() + " body: " + responseBody);
            if (!response.isSuccessful()) {
                throw new RuntimeException("SOAP action failed: " + action + ", HTTP " + response.code() + ", body: " + responseBody);
            }
            if (responseBody.contains("<s:Fault>")) {
                throw new RuntimeException("SOAP fault: " + extractXmlTag(responseBody, "errorDescription"));
            }
        }
    }

    private String buildDidlLite(String title) {
        if (title == null || title.isEmpty()) {
            return "";
        }
        return "&lt;DIDL-Lite xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\" " +
                "xmlns:dc=\"http://purl.org/dc/elements/1.1/\" " +
                "xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\"&gt;" +
                "&lt;item id=\"0\" parentID=\"-1\" restricted=\"1\"&gt;" +
                "&lt;dc:title&gt;" + escapeXml(title) + "&lt;/dc:title&gt;" +
                "&lt;upnp:class&gt;object.item.videoItem&lt;/upnp:class&gt;" +
                "&lt;/item&gt;&lt;/DIDL-Lite&gt;";
    }

    private String escapeXml(String text) {
        if (text == null) return "";
        return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }

    private void acquireMulticastLock() {
        if (mMulticastLock == null) {
            WifiManager wifiManager = (WifiManager) mContext.getSystemService(Context.WIFI_SERVICE);
            if (wifiManager != null) {
                mMulticastLock = wifiManager.createMulticastLock(TAG);
                mMulticastLock.setReferenceCounted(true);
                mMulticastLock.acquire();
                Log.d(TAG, "MulticastLock acquired");
            }
        }
    }

    private void releaseMulticastLock() {
        if (mMulticastLock != null && mMulticastLock.isHeld()) {
            mMulticastLock.release();
            Log.d(TAG, "MulticastLock released");
            mMulticastLock = null;
        }
    }

    public List<DlnaDevice> getFoundDevices() {
        return new ArrayList<>(mDevices.values());
    }
}
