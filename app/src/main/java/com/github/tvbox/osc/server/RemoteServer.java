package com.github.tvbox.osc.server;

import android.annotation.SuppressLint;
import android.content.Context;
import android.net.wifi.WifiManager;
import android.os.Environment;
import android.util.Base64;
import android.util.Log;

import com.github.tvbox.osc.R;
import com.github.tvbox.osc.api.ApiConfig;
import com.github.tvbox.osc.base.App;
import com.github.tvbox.osc.event.ServerEvent;
import com.github.tvbox.osc.util.FileUtils;
import com.github.tvbox.osc.util.OkGoHelper;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import org.greenrobot.eventbus.EventBus;

import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Comparator;
import java.util.Date;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import fi.iki.elonen.NanoHTTPD;

/**
 * @author pj567
 * @date :2021/1/5
 * @description:
 */
public class RemoteServer extends NanoHTTPD {
    private Context mContext;
    public static int serverPort = 9978;
    private boolean isStarted = false;
    private DataReceiver mDataReceiver;
    private ArrayList < RequestProcess > getRequestList = new ArrayList < > ();
    private ArrayList < RequestProcess > postRequestList = new ArrayList < > ();

    public static String m3u8Content;

    public RemoteServer(int port, Context context) {
        super("0.0.0.0", port);
        Log.d("RemoteServer", "NanoHTTPD bound to 0.0.0.0:" + port);
        mContext = context;
        addGetRequestProcess();
        addPostRequestProcess();
    }

    private void addGetRequestProcess() {
        getRequestList.add(new RawRequestProcess(this.mContext, "/", R.raw.index, NanoHTTPD.MIME_HTML));
        getRequestList.add(new RawRequestProcess(this.mContext, "/index.html", R.raw.index, NanoHTTPD.MIME_HTML));
        getRequestList.add(new RawRequestProcess(this.mContext, "/style.css", R.raw.style, "text/css"));
        getRequestList.add(new RawRequestProcess(this.mContext, "/ui.css", R.raw.ui, "text/css"));
        getRequestList.add(new RawRequestProcess(this.mContext, "/jquery.js", R.raw.jquery, "application/x-javascript"));
        getRequestList.add(new RawRequestProcess(this.mContext, "/script.js", R.raw.script, "application/x-javascript"));
        getRequestList.add(new RawRequestProcess(this.mContext, "/favicon.ico", R.drawable.app_icon, "image/x-icon"));
    }

    private void addPostRequestProcess() {
        postRequestList.add(new InputRequestProcess(this));
    }

    @Override
    public void start(int timeout, boolean daemon) throws IOException {
        isStarted = true;
        super.start(timeout, daemon);
        EventBus.getDefault().post(new ServerEvent(ServerEvent.SERVER_SUCCESS));
    }

    @Override
    public void stop() {
        super.stop();
        isStarted = false;
    }

    @Override
    public Response serve(IHTTPSession session) {
        EventBus.getDefault().post(new ServerEvent(ServerEvent.SERVER_CONNECTION));
        if (!session.getUri().isEmpty()) {
            String fileName = session.getUri().trim();
            if (fileName.indexOf('?') >= 0) {
                fileName = fileName.substring(0, fileName.indexOf('?'));
            }
            if (session.getMethod() == Method.GET) {
                for (RequestProcess process: getRequestList) {
                    if (process.isRequest(session, fileName)) {
                        return process.doResponse(session, fileName, session.getParms(), null);
                    }
                }
                if (fileName.equals("/proxy")) {
                    Log.d("RemoteServer", "Proxy request from: " + session.getRemoteHostName()
                            + ":" + session.getRemoteIpAddress()
                            + " params: " + session.getParms().get("do")
                            + " uri: " + session.getUri());
                    Map < String, String > params = session.getParms();
                    params.putAll(session.getHeaders());
                    params.put("request-headers", new Gson().toJson(session.getHeaders()));
                    if (params.containsKey("do")) {
                        // ★ do=raw:纯 HTTP 代理(直连 + 透传),用于 m3u8 rewrite 后的 .ts 分片
                        // 不依赖 jar 里的 spider,直接 OkHttp GET 参数 url,然后原样返回
                        if ("raw".equalsIgnoreCase(params.get("do"))) {
                            String targetUrl = params.get("url");
                            Log.d("RemoteServer", "Proxy(raw) target=" + targetUrl);
                            if (targetUrl == null || targetUrl.isEmpty()) {
                                return NanoHTTPD.newFixedLengthResponse(
                                        NanoHTTPD.Response.Status.BAD_REQUEST,
                                        NanoHTTPD.MIME_PLAINTEXT, "missing url param");
                            }
                            try {
                                // ★ 透传调用方原始请求头(尤其是 Macast/mpv 主动带的 Range)
                                // Chaoxing/ananas CDN 用 origin.jpg 这种路径做了防盗链,
                                // 必须带 Referer: https://mooc1-1.chaoxing.com/ 才能访问。
                                // 此外 Chaoxing 资源还会验 User-Agent 和 Accept。
                                okhttp3.OkHttpClient raw = new okhttp3.OkHttpClient.Builder()
                                        .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                                        .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                                        .followRedirects(true)
                                        .build();
                                okhttp3.Request.Builder rb = new okhttp3.Request.Builder().url(targetUrl);
                                // 透传客户端发来的 header
                                String hUserAgent = params.get("user-agent");
                                if (hUserAgent == null) hUserAgent = params.get("User-Agent");
                                if (hUserAgent == null) hUserAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";
                                rb.header("User-Agent", hUserAgent);
                                // 优先用调用方 Referer,否则按 host 智能选
                                String hReferer = params.get("referer");
                                if (hReferer == null) hReferer = params.get("Referer");
                                if (hReferer == null) {
                                    // 根据 target host 智能选 Referer
                                    if (targetUrl.contains("ananas.chaoxing.com") || targetUrl.contains("chaoxing.com")) {
                                        hReferer = "https://mooc1-1.chaoxing.com/";
                                    } else if (targetUrl.contains("vip.123pan.cn") || targetUrl.contains("123pan")) {
                                        hReferer = "https://vip.123pan.cn/";
                                    } else if (targetUrl.contains("ruxiangsuisu.cn") || targetUrl.contains("dgxj.")) {
                                        hReferer = "https://dgxj.ruxiangsuisu.cn/";
                                    } else {
                                        hReferer = targetUrl;  // 兜底用自己
                                    }
                                }
                                rb.header("Referer", hReferer);
                                // 透传 Range 头(分片请求会带)
                                String hRange = params.get("range");
                                if (hRange == null) hRange = params.get("Range");
                                if (hRange != null) rb.header("Range", hRange);
                                // 透传 Accept
                                String hAccept = params.get("accept");
                                if (hAccept == null) hAccept = params.get("Accept");
                                if (hAccept != null) rb.header("Accept", hAccept);
                                okhttp3.Response rs = raw.newCall(rb.build()).execute();
                                int code = rs.code();
                                String mime = "application/octet-stream";
                                String ct = rs.header("Content-Type");
                                if (ct != null && !ct.isEmpty()) mime = ct;
                                InputStream body = rs.body() != null ? rs.body().byteStream() : null;
                                long contentLength = rs.body() != null ? rs.body().contentLength() : -1;
                                Log.d("RemoteServer", "Proxy(raw) response: code=" + code + " mime=" + mime
                                        + " bodySize=" + contentLength + " referer=" + hReferer);
                                // Chaoxing/ananas 返回 403 时,记下响应体前 200 字符便于排查
                                if (code == 403) {
                                    try {
                                        String bodyStr = body != null ? new java.util.Scanner(rs.body().byteStream(), "UTF-8").useDelimiter("\\A").next() : "";
                                        Log.w("RemoteServer", "Proxy(raw) 403 body preview: "
                                                + bodyStr.substring(0, Math.min(200, bodyStr.length())));
                                    } catch (Exception ignore) {}
                                    // ★ 重要:重新构造请求拿 body,因为上面 byteStream 已被消耗
                                    okhttp3.Response rs2 = raw.newCall(rb.build()).execute();
                                    body = rs2.body() != null ? rs2.body().byteStream() : null;
                                }
                                Response response = NanoHTTPD.newChunkedResponse(
                                        NanoHTTPD.Response.Status.lookup(code),
                                        mime,
                                        body);
                                return response;
                            } catch (Exception ex) {
                                Log.e("RemoteServer", "Proxy(raw) failed", ex);
                                return NanoHTTPD.newFixedLengthResponse(
                                        NanoHTTPD.Response.Status.INTERNAL_ERROR,
                                        NanoHTTPD.MIME_PLAINTEXT, "raw proxy error: " + ex.getMessage());
                            }
                        }
                        // ★ m3u8 rewrite 开关:在 RemoteServer 层做最外层 m3u8 内容改写,
                        // 解决"Macast/mpv 拿到 m3u8 后无法拉 .ts 分片"的问题。
                        // 之前 jar 里的 spider 只代理了 m3u8 文件本身,m3u8 里 .ts 分片 URL
                        // 还是直指 123pan CDN,Macast PC 端拉不到这些分片就卡 start-file。
                        // 思路:在 RemoteServer 这层把 m3u8 文本读出来,把里面所有
                        //   #EXTINF
                        //   https://vip.123pan.cn/.../seg-001.ts
                        //   #EXTINF
                        //   https://vip.123pan.cn/.../seg-002.ts
                        // 这种分片 URL 全部改写成
                        //   /proxy?do=raw&url=https%3A%2F%2Fvip.123pan.cn%2F...%2Fseg-001.ts
                        // 让分片也走手机代理,保证 Macast 能拉到。
                        // 注意:我们改写"原始文本",不破坏 m3u8 的格式(行结构、#EXTINF 行不动)。
                        if ("m3u8".equalsIgnoreCase(params.get("do"))) {
                            try {
                                Object[] rs = ApiConfig.get().proxyLocal(params);
                                int code = (int) rs[0];
                                String mime = (String) rs[1];
                                InputStream stream = rs[2] != null ? (InputStream) rs[2] : null;
                                if (code == 200 && stream != null) {
                                    // 读取 m3u8 全部内容
                                    java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
                                    byte[] buf = new byte[8192];
                                    int n;
                                    while ((n = stream.read(buf)) > 0) baos.write(buf, 0, n);
                                    stream.close();
                                    String m3u8Text = baos.toString("UTF-8");
                                    Log.d("RemoteServer", "Proxy(m3u8) raw length=" + m3u8Text.length());
                                    // rewrite
                                    String rewritten = rewriteM3u8Segments(m3u8Text, params.get("url"));
                                    Log.d("RemoteServer", "Proxy(m3u8) rewritten length=" + rewritten.length());
                                    InputStream newStream = new java.io.ByteArrayInputStream(
                                            rewritten.getBytes("UTF-8"));
                                    Response response = NanoHTTPD.newChunkedResponse(
                                            NanoHTTPD.Response.Status.lookup(code),
                                            mime,
                                            newStream);
                                    if (rs.length > 3) {
                                        try {
                                            HashMap < String, String > headers = (HashMap < String, String > ) rs[3];
                                            for (String key: headers.keySet()) {
                                                response.addHeader(key, headers.get(key));
                                            }
                                        } catch (Throwable th) {
                                            th.printStackTrace();
                                        }
                                    }
                                    return response;
                                }
                                // 不是 200 或 stream 为空 → 走原路径
                                Response response = NanoHTTPD.newChunkedResponse(
                                        NanoHTTPD.Response.Status.lookup(code),
                                        mime,
                                        stream);
                                return response;
                            } catch (Exception ex) {
                                Log.e("RemoteServer", "m3u8 rewrite failed, fall back to raw proxy", ex);
                                // 失败就走原始路径,不阻断
                            }
                        }
                        Object[] rs = ApiConfig.get().proxyLocal(params);
                        //if (rs[0] instanceof Response) {
                        //    return (Response) rs[0];
                        //}
                        int code = (int) rs[0];
                        String mime = (String) rs[1];
                        InputStream stream = rs[2] != null ? (InputStream) rs[2] : null;
                        Log.d("RemoteServer", "Proxy response: code=" + code + " mime=" + mime
                                + " stream=" + (stream != null ? "non-null" : "null"));
                        Response response = NanoHTTPD.newChunkedResponse(
                                NanoHTTPD.Response.Status.lookup(code),
                                mime,
                                stream);
                        if (rs.length > 3) {
                            try {
                                HashMap < String, String > headers = (HashMap < String, String > ) rs[3];
                                for (String key: headers.keySet()) {
                                    response.addHeader(key, headers.get(key));
                                }
                            } catch (Throwable th) {
                                th.printStackTrace();
                            }
                        }
                        return response;
                    }
                } else if (fileName.startsWith("/file/")) {
                    try {
                        String f = fileName.substring(6);
                        String root = Environment.getExternalStorageDirectory().getAbsolutePath();
                        String file = root + "/" + f;
                        File localFile = new File(file);
                        if (localFile.exists()) {
                            if (localFile.isFile()) {
                                return NanoHTTPD.newChunkedResponse(NanoHTTPD.Response.Status.OK, "application/octet-stream", new FileInputStream(localFile));
                            } else {
                                return NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.OK, NanoHTTPD.MIME_PLAINTEXT, fileList(root, f));
                            }
                        } else {
                            return NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.INTERNAL_ERROR, NanoHTTPD.MIME_PLAINTEXT, "File " + file + " not found!");
                        }
                    } catch (Throwable th) {
                        return NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.INTERNAL_ERROR, NanoHTTPD.MIME_PLAINTEXT, th.getMessage());
                    }
                } else if (fileName.equals("/dns-query")) {
                    String name = session.getParms().get("name");
                    byte[] rs = null;
                    try {
                        rs = OkGoHelper.dnsOverHttps.lookupHttpsForwardSync(name);
                    } catch (Throwable th) {
                        rs = new byte[0];
                    }
                    return NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.OK, "application/dns-message", new ByteArrayInputStream(rs), rs.length);
                } else if (fileName.equals("/m3u8")) {
                    return NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.OK,  NanoHTTPD.MIME_PLAINTEXT, m3u8Content);
                }
            } else if (session.getMethod() == Method.POST) {
                Map < String, String > files = new HashMap < String, String > ();
                try {
                    if (session.getHeaders().containsKey("content-type")) {
                        String hd = session.getHeaders().get("content-type");
                        if (hd != null) {
                            // cuke: 修正中文乱码问题
                            if (hd.toLowerCase().contains("multipart/form-data") && !hd.toLowerCase().contains("charset=")) {
                                Matcher matcher = Pattern.compile("[ |\t]*(boundary[ |\t]*=[ |\t]*['|\"]?[^\"^'^;^,]*['|\"]?)", Pattern.CASE_INSENSITIVE).matcher(hd);
                                String boundary = matcher.find() ? matcher.group(1) : null;
                                if (boundary != null) {
                                    session.getHeaders().put("content-type", "multipart/form-data; charset=utf-8; " + boundary);
                                }
                            }
                        }
                    }
                    session.parseBody(files);
                } catch (IOException IOExc) {
                    return createPlainTextResponse(NanoHTTPD.Response.Status.INTERNAL_ERROR, "SERVER INTERNAL ERROR: IOException: " + IOExc.getMessage());
                } catch (NanoHTTPD.ResponseException rex) {
                    return createPlainTextResponse(rex.getStatus(), rex.getMessage());
                }
                for (RequestProcess process: postRequestList) {
                    if (process.isRequest(session, fileName)) {
                        return process.doResponse(session, fileName, session.getParms(), files);
                    }
                }
                try {
                    Map < String, String > params = session.getParms();
                    if (fileName.equals("/upload")) {
                        String path = params.get("path");
                        for (String k: files.keySet()) {
                            if (k.startsWith("files-")) {
                                String fn = params.get(k);
                                String tmpFile = files.get(k);
                                File tmp = new File(tmpFile);
                                String root = Environment.getExternalStorageDirectory().getAbsolutePath();
                                File file = new File(root + "/" + path + "/" + fn);
                                if (file.exists()) file.delete();
                                if (tmp.exists()) {
                                    if (fn.toLowerCase().endsWith(".zip")) {
                                        unzip(tmp, root + "/" + path);
                                    } else {
                                        FileUtils.copyFile(tmp, file);
                                    }
                                }
                                if (tmp.exists()) tmp.delete();
                            }
                        }
                        return NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.OK, NanoHTTPD.MIME_PLAINTEXT, "OK");
                    } else if (fileName.equals("/newFolder")) {
                        String path = params.get("path");
                        String name = params.get("name");
                        String root = Environment.getExternalStorageDirectory().getAbsolutePath();
                        File file = new File(root + "/" + path + "/" + name);
                        if (!file.exists()) {
                            file.mkdirs();
                            File flag = new File(root + "/" + path + "/" + name + "/.tvbox_folder");
                            if (!flag.exists()) flag.createNewFile();
                        }
                        return NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.OK, NanoHTTPD.MIME_PLAINTEXT, "OK");
                    } else if (fileName.equals("/delFolder")) {
                        String path = params.get("path");
                        String root = Environment.getExternalStorageDirectory().getAbsolutePath();
                        File file = new File(root + "/" + path);
                        if (file.exists()) {
                            FileUtils.recursiveDelete(file);
                        }
                        return NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.OK, NanoHTTPD.MIME_PLAINTEXT, "OK");
                    } else if (fileName.equals("/delFile")) {
                        String path = params.get("path");
                        String root = Environment.getExternalStorageDirectory().getAbsolutePath();
                        File file = new File(root + "/" + path);
                        if (file.exists()) {
                            file.delete();
                        }
                        return NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.OK, NanoHTTPD.MIME_PLAINTEXT, "OK");
                    }
                } catch (Throwable th) {
                    return NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.OK, NanoHTTPD.MIME_PLAINTEXT, "OK");
                }
            }
        }
        //default page: index.html
        return getRequestList.get(0).doResponse(session, "", null, null);
    }

    public void setDataReceiver(DataReceiver receiver) {
        mDataReceiver = receiver;
    }

    public DataReceiver getDataReceiver() {
        return mDataReceiver;
    }

    /**
     * 把 m3u8 内容里所有分片 URL(以 http/https 开头)改写成手机代理 URL。
     * 这样 Macast/mpv 拉 m3u8 后再 GET 分片,会经过手机的 9978 代理,而不是直连 CDN。
     *
     * @param m3u8Text   jar 里的 spider 已经返回的 m3u8 原始文本
     * @param originUrl  SetAVTransportURI 时使用的代理 URL 里的 "url" 参数(原始 CDN 地址)
     * @return 改写后的 m3u8 文本
     */
    private String rewriteM3u8Segments(String m3u8Text, String originUrl) {
        if (m3u8Text == null || m3u8Text.isEmpty()) return m3u8Text;
        String baseUrl = originUrl != null ? originUrl : "";
        // 提取 base URL 的 scheme://host 部分,用于拼接相对路径
        String basePrefix = "";
        int schemeEnd = baseUrl.indexOf("://");
        if (schemeEnd >= 0) {
            int pathStart = baseUrl.indexOf('/', schemeEnd + 3);
            basePrefix = pathStart > 0 ? baseUrl.substring(0, pathStart) : baseUrl;
        }
        String[] lines = m3u8Text.split("\n");
        StringBuilder out = new StringBuilder(m3u8Text.length() + 1024);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            String trimmed = line.trim();
            // 跳过 # 开头的元数据行(EXTM3U / EXTINF / EXT-X-*)
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                out.append(line);
                if (i < lines.length - 1) out.append('\n');
                continue;
            }
            // 这一行是分片 URL → rewrite
            String absUrl = trimmed;
            if (!absUrl.startsWith("http://") && !absUrl.startsWith("https://")) {
                // 相对路径:拼上 basePrefix
                if (absUrl.startsWith("/")) {
                    absUrl = basePrefix + absUrl;
                } else if (!basePrefix.isEmpty()) {
                    int lastSlash = baseUrl.lastIndexOf('/');
                    String baseDir = lastSlash > schemeEnd + 2 ? baseUrl.substring(0, lastSlash + 1) : basePrefix + "/";
                    absUrl = baseDir + absUrl;
                }
            }
            // 跳过已经是手机代理 URL 的,避免重复嵌套
            if (absUrl.contains("127.0.0.1:") && absUrl.contains("/proxy?")) {
                out.append(line);
            } else {
                // 改用 try-catch 包住 URLEncoder.encode,
                // 避免 throws UnsupportedEncodingException 让上层编译失败。
                // (Java 8 之后的 API 是 throws UnsupportedEncodingException;
                //  Android 编译虽然认 UTF-8 常量,但这里稳妥起见包一层。)
                String encoded;
                try {
                    encoded = java.net.URLEncoder.encode(absUrl, "UTF-8");
                } catch (java.io.UnsupportedEncodingException uee) {
                    Log.w("RemoteServer", "URLEncoder.encode failed, use raw url", uee);
                    encoded = absUrl;
                }
                // 关键:不指定 'do',让 RemoteServer 走原始 GET 代理路径;
                // 如果指定 'do=m3u8',又会触发 jar spider 解析(可能再次包成 m3u8,死循环)
                String proxied = "http://127.0.0.1:" + serverPort + "/proxy?do=raw&url=" + encoded;
                out.append(proxied);
                Log.d("RemoteServer", "rewrite segment: " + absUrl + " -> " + proxied);
            }
            if (i < lines.length - 1) out.append('\n');
        }
        return out.toString();
    }

    public boolean isStarting() {
        return isStarted;
    }

    public String getServerAddress() {
        String ipAddress = getLocalIPAddress(mContext);
        return "http://" + ipAddress + ":" + RemoteServer.serverPort + "/";
    }

    public String getLoadAddress() {
        return "http://127.0.0.1:" + RemoteServer.serverPort + "/";
    }

    public static Response createPlainTextResponse(Response.IStatus status, String text) {
        return newFixedLengthResponse(status, NanoHTTPD.MIME_PLAINTEXT, text);
    }

    public static Response createJSONResponse(Response.IStatus status, String text) {
        return newFixedLengthResponse(status, "application/json", text);
    }

    @SuppressLint("DefaultLocale")
    public static String getLocalIPAddress(Context context) {
        WifiManager wifiManager = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
        int ipAddress = wifiManager.getConnectionInfo().getIpAddress();
        if (ipAddress == 0) {
            try {
                Enumeration < NetworkInterface > enumerationNi = NetworkInterface.getNetworkInterfaces();
                while (enumerationNi.hasMoreElements()) {
                    NetworkInterface networkInterface = enumerationNi.nextElement();
                    String interfaceName = networkInterface.getDisplayName();
                    if (interfaceName.equals("eth0") || interfaceName.equals("wlan0")) {
                        Enumeration < InetAddress > enumIpAddr = networkInterface.getInetAddresses();
                        while (enumIpAddr.hasMoreElements()) {
                            InetAddress inetAddress = enumIpAddr.nextElement();
                            if (!inetAddress.isLoopbackAddress() && inetAddress instanceof Inet4Address) {
                                return inetAddress.getHostAddress();
                            }
                        }
                    }
                }
            } catch (SocketException e) {
                e.printStackTrace();
            }
        } else {
            return String.format("%d.%d.%d.%d", (ipAddress & 0xff), (ipAddress >> 8 & 0xff), (ipAddress >> 16 & 0xff), (ipAddress >> 24 & 0xff));
        }
        return "0.0.0.0";
    }

    String fileTime(long time, String fmt) {
        Calendar calendar = Calendar.getInstance();
        calendar.setTimeInMillis(time);
        Date date = calendar.getTime();
        SimpleDateFormat sdf = new SimpleDateFormat(fmt);
        return sdf.format(date);
    }

    String fileList(String root, String path) {
        File file = new File(root + "/" + path);
        File[] list = file.listFiles();
        JsonObject info = new JsonObject();
        info.addProperty("remote", getServerAddress().replace("http://", "clan://"));
        info.addProperty("del", 0);
        if (path.isEmpty()) {
            info.addProperty("parent", ".");
        } else {
            info.addProperty("parent", file.getParentFile().getAbsolutePath().replace(root + "/", "").replace(root, ""));
        }
        if (list == null || list.length == 0) {
            info.add("files", new JsonArray());
            return info.toString();
        }
        Arrays.sort(list, new Comparator < File > () {@Override
        public int compare(File o1, File o2) {
            if (o1.isDirectory() && o2.isFile()) return -1;
            return o1.isFile() && o2.isDirectory() ? 1 : o1.getName().compareTo(o2.getName());
        }
        });
        JsonArray result = new JsonArray();
        for (File f: list) {
            if (f.getName().startsWith(".")) {
                if (f.getName().equals(".tvbox_folder")) {
                    info.addProperty("del", 1);
                }
                continue;
            }
            JsonObject fileObj = new JsonObject();
            fileObj.addProperty("name", f.getName());
            fileObj.addProperty("path", f.getAbsolutePath().replace(root + "/", ""));
            fileObj.addProperty("time", fileTime(f.lastModified(), "yyyy/MM/dd aHH:mm:ss"));
            fileObj.addProperty("dir", f.isDirectory() ? 1 : 0);
            result.add(fileObj);
        }
        info.add("files", result);
        return info.toString();
    }

    void unzip(File zipFilePath, String destDirectory) throws Throwable {
        File destDir = new File(destDirectory);
        if (!destDir.exists()) {
            destDir.mkdirs();
        }
        ZipFile zip = new ZipFile(zipFilePath);
        Enumeration < ZipEntry > iter = (Enumeration < ZipEntry > ) zip.entries();
        while (iter.hasMoreElements()) {
            ZipEntry entry = iter.nextElement();
            InputStream is = zip.getInputStream(entry);
            String filePath = destDirectory + File.separator + entry.getName();
            if (!entry.isDirectory()) {
                extractFile(is, filePath);
            } else {
                File dir = new File(filePath);
                if (!dir.exists()) dir.mkdirs();
                File flag = new File(dir + "/.tvbox_folder");
                if (!flag.exists()) flag.createNewFile();
            }
        }
    }

    void extractFile(InputStream inputStream, String destFilePath) throws Throwable {
        File dst = new File(destFilePath);
        if (dst.exists()) dst.delete();
        BufferedOutputStream bos = new BufferedOutputStream(new FileOutputStream(destFilePath));
        byte[] bytesIn = new byte[2048];
        int len = inputStream.read(bytesIn);
        while (len > 0) {
            bos.write(bytesIn, 0, len);
            len = inputStream.read(bytesIn);
        }
        bos.close();
    }

}