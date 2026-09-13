package com.github.tvbox.osc.util;

import android.content.Context;
import android.os.Environment;
import android.text.TextUtils;
import android.util.Base64;

import com.github.tvbox.osc.base.App;
import com.github.tvbox.osc.server.ControlManager;
import com.google.gson.JsonObject;
import com.lzy.okgo.OkGo;
import com.lzy.okgo.model.HttpHeaders;
import com.orhanobut.hawk.Hawk;

import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.Response;

public class FileUtils {

    /**
     * 打开缓存文件。
     *
     * @param str 缓存文件名标识
     * @return 缓存文件对象
     */
    public static File open(String str) {
        File cacheDir = App.getInstance().getExternalCacheDir();
        // getExternalCacheDir() 在某些设备上可能返回 null
        String basePath = cacheDir != null ? cacheDir.getAbsolutePath() : App.getInstance().getCacheDir().getAbsolutePath();
        return new File(basePath + "/qjscache_" + str + ".js");
    }

    /**
     * 生成不带连字符的 UUID。
     *
     * @return UUID 字符串(32 位)
     */
    public static String genUUID() {
        return UUID.randomUUID()
            .toString()
            .replace("-", "");
    }

    /**
     * 读取字符串缓存(带过期时间)。
     *
     * @param name 缓存名
     * @return 缓存内容, 过期或不存在返回空串
     */
    public static String getCache(String name) {
        try {
            String code = "";
            File file = open(name);
            if (file.exists()) {
                code = new String(readSimple(file));
            }
            if (TextUtils.isEmpty(code)) {
                return "";
            }
            JsonObject asJsonObject = (GsonUtil.get()
                .fromJson(code, JsonObject.class))
                .getAsJsonObject();
            if (((long) asJsonObject.get("expires")
                .getAsInt()) > System.currentTimeMillis() / 1000) {
                return new String(Base64.decode(asJsonObject.get("data")
                    .getAsString(), Base64.URL_SAFE));
            }
            recursiveDelete(open(name));
            return "";
        } catch (Exception e4) {
            return "";
        }
    }

    /**
     * 写入字符串缓存。
     *
     * @param time 过期时间(秒)
     * @param name 缓存名
     * @param data 缓存数据
     */
    public static void setCache(int time, String name, String data) {
        try {
            JSONObject jSONObject = new JSONObject();
            jSONObject.put("expires", (int)(time + (System.currentTimeMillis() / 1000)));
            jSONObject.put("data", Base64.encodeToString(data.getBytes(), Base64.URL_SAFE));
            writeSimple(jSONObject.toString().getBytes(), open(name));
        } catch (Exception e) {
            LOG.e("setCache error", e);
        }
    }

    /**
     * 写入字节缓存。
     *
     * @param name 缓存名
     * @param data 字节数据
     */
    public static void setCacheByte(String name, byte[] data) {
        try {
            writeSimple(byteMerger("//DRPY".getBytes(),Base64.encode(data, Base64.URL_SAFE)), open("B_" + name));
        } catch (Exception e) {
            LOG.e("setCacheByte error", e);
        }
    }

    /**
     * 合并两个字节数组。
     *
     * @param bt1 第一个数组
     * @param bt2 第二个数组
     * @return 合并后的数组
     */
    public static byte[] byteMerger(byte[] bt1, byte[] bt2){
        byte[] bt3 = new byte[bt1.length+bt2.length];
        System.arraycopy(bt1, 0, bt3, 0, bt1.length);
        System.arraycopy(bt2, 0, bt3, bt1.length, bt2.length);
        return bt3;
    }

    public static String get(String str) {
        return get(str, null);
    }

    /**
     * 发起 HTTP GET 请求并返回响应文本。
     *
     * @param str       请求 URL
     * @param headerMap 请求头, 可为 null
     * @return 响应文本, 失败返回空串
     */
    public static String get(String str, Map<String, String> headerMap) {
        try {
            HttpHeaders h = new HttpHeaders();
            if (headerMap != null) {
                for (Map.Entry<String, String> entry : headerMap.entrySet()) {
                    h.put(entry.getKey(), entry.getValue());
                }
            }
            Response response;
            if (headerMap != null) {
                response = OkGo.<String>get(str).headers(h).execute();
            } else {
                response = OkGo.<String>get(str).headers("User-Agent", str.startsWith("https://gitcode.net/") ? UA.random() : "okhttp/3.15").execute();
            }
            try {
                if (response.isSuccessful() && response.body() != null) {
                    return new String(response.body().bytes(), StandardCharsets.UTF_8);
                }
            } finally {
                response.close();
            }
            return "";
        } catch (IOException e) {
            return "";
        }
    }

    private static final Pattern URL_JOIN_PATTERN = Pattern.compile("^http.*\\.(js|txt|json|m3u)$", Pattern.MULTILINE | Pattern.CASE_INSENSITIVE);

    /**
     * 加载 JS 模块: 支持 http/assets/file/clan 等多种协议。
     *
     * @param name 模块路径或 URL
     * @return 模块内容文本
     */
    public static String loadModule(String name) {
        try {
            if (name.endsWith("ali.js")) {
                name = "ali.js";
            } else if (name.endsWith("ali_api.js")) {
                name = "ali_api.js";
            } else if (name.contains("similarity.js")) {
                name = "similarity.js";
            } else if (name.contains("gbk.js")) {
                name = "gbk.js";
            } else if (name.contains("模板.js")) {
                name = "模板.js";
            } else if (name.contains("cat.js")) {
                name = "cat.js";
            }
            Matcher m = URL_JOIN_PATTERN.matcher(name);
            if (m.find()) {
                if (!Hawk.get(HawkConfig.DEBUG_OPEN, false)) {
                    String cache = getCache(MD5.encode(name));
                    if (StringUtils.isEmpty(cache)) {
                        String netStr = get(name);
                        if (!TextUtils.isEmpty(netStr)) {
                            setCache(604800, MD5.encode(name), netStr);
                        }
                        return netStr;
                    }
                    return cache;
                } else {
                    return get(name);
                }
            } else if (name.startsWith("assets://")) {
                return getAsOpen(name.substring(9));
            } else if (isAsFile(name, "js/lib")) {
                return getAsOpen("js/lib/" + name);
            } else if (name.startsWith("file://")) {
                return get(ControlManager.get()
                    .getAddress(true) + "file/" + name.replace("file:///", "")
                    .replace("file://", ""));
            } else if (name.startsWith("clan://localhost/")) {
                return get(ControlManager.get()
                    .getAddress(true) + "file/" + name.replace("clan://localhost/", ""));
            } else if (name.startsWith("clan://")) {
                String substring = name.substring(7);
                int indexOf = substring.indexOf(47);
                return get("http://" + substring.substring(0, indexOf) + "/file/" + substring.substring(indexOf + 1));
            }
        } catch (Exception e) {
            LOG.e("loadModule error", e);
            return name;
        }
        return name;
    }

    /**
     * 检查 assets 指定目录下是否存在指定文件。
     *
     * @param name 文件名
     * @param path assets 下的目录路径
     * @return 存在返回 true
     */
    public static boolean isAsFile(String name, String path) {
        try {
            String[] assetList = App.getInstance().getAssets().list(path);
            if (assetList == null) return false;
            for (String fname : assetList) {
                if (fname.equals(name.trim())) {
                    return true;
                }
            }
        } catch (Exception e) {
            LOG.e("isAsFile error", e);
        }
        return false;
    }

    /**
     * 从 assets 读取文件文本。
     *
     * @param name assets 下的文件路径
     * @return 文件文本内容, 失败返回空串
     */
    public static String getAsOpen(String name) {
        try (InputStream is = App.getInstance().getAssets().open(name)) {
            byte[] data = new byte[is.available()];
            if (is.read(data) == -1) {
                return "";
            }
            return new String(data, StandardCharsets.UTF_8);
        } catch (Exception e) {
            LOG.e("getAsOpen error", e);
        }
        return "";
    }

    /**
     * 写入字节到文件。
     *
     * @param data 字节数据
     * @param dst  目标文件
     * @return 成功返回 true
     */
    public static boolean writeSimple(byte[] data, File dst) {
        try {
            if (dst.exists() && !dst.delete()) {
                LOG.e("writeSimple: failed to delete existing file");
            }
            try (BufferedOutputStream bos = new BufferedOutputStream(new FileOutputStream(dst))) {
                bos.write(data);
            }
            return true;
        } catch (IOException e) {
            LOG.e("writeSimple error", e);
        }
        return false;
    }

    /**
     * 从文件读取全部字节。
     *
     * @param src 源文件
     * @return 字节数组, 失败返回 null
     */
    public static byte[] readSimple(File src) {
        try (BufferedInputStream bis = new BufferedInputStream(new FileInputStream(src))) {
            int len = bis.available();
            byte[] data = new byte[len];
            if (bis.read(data) == -1) {
                return null;
            }
            return data;
        } catch (IOException e) {
            LOG.e("readSimple error", e);
        }
        return null;
    }

    /**
     * 复制文件。
     *
     * @param source 源文件
     * @param dest   目标文件
     * @throws IOException 复制失败时抛出
     */
    public static void copyFile(File source, File dest) throws IOException {
        try (InputStream is = new FileInputStream(source); OutputStream os = new FileOutputStream(dest)) {
            byte[] buffer = new byte[1024];
            int length;
            while ((length = is.read(buffer)) > 0) {
                os.write(buffer, 0, length);
            }
        }
    }

    public static String getRootPath() {
        return Environment.getExternalStorageDirectory()
            .getAbsolutePath();
    }

    public static File getCacheDir() {
        return App.getInstance().getCacheDir();
    }

    public static File getExternalCacheDir() {
        return App.getInstance().getExternalCacheDir();
    }

    public static String getCachePath() {
        return getCacheDir()
            .getAbsolutePath();
    }

    /**
     * 递归删除文件或目录。
     *
     * @param file 要删除的文件/目录
     */
    public static void recursiveDelete(File file) {
        if (file == null || !file.exists()) return;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File f : children) {
                    recursiveDelete(f);
                }
            }
        }
        if (!file.delete()) {
            LOG.e("recursiveDelete: failed to delete " + file.getAbsolutePath());
        }
    }

    /**
     * 格式化文件大小为可读字符串。
     *
     * @param size 字节数
     * @return 格式化后的字符串(如 "1.23MB")
     */
    public static String getFormatSize(double size) {
        double kiloByte = size / 1024;
        if (kiloByte < 1) {
            return "0K";
        }

        double megaByte = kiloByte / 1024;
        if (megaByte < 1) {
            BigDecimal result1 = new BigDecimal(Double.toString(kiloByte));
            return result1.setScale(2, RoundingMode.HALF_UP)
                .toPlainString() + "KB";
        }

        double gigaByte = megaByte / 1024;
        if (gigaByte < 1) {
            BigDecimal result2 = new BigDecimal(Double.toString(megaByte));
            return result2.setScale(2, RoundingMode.HALF_UP)
                .toPlainString() + "MB";
        }

        double teraBytes = gigaByte / 1024;
        if (teraBytes < 1) {
            BigDecimal result3 = new BigDecimal(Double.toString(gigaByte));
            return result3.setScale(2, RoundingMode.HALF_UP)
                .toPlainString() + "GB";
        }
        BigDecimal result4 = new BigDecimal(teraBytes);
        return result4.setScale(2, RoundingMode.HALF_UP)
            .toPlainString() + "TB";
    }

    /**
     * 递归删除目录及其内容。
     *
     * @param dir 目录文件
     * @return 全部删除成功返回 true
     */
    private static boolean deleteDir(File dir) {
        if (dir == null || !dir.exists()) return false;
        if (dir.isDirectory()) {
            String[] children = dir.list();
            if (children != null) {
                for (String child : children) {
                    boolean success = deleteDir(new File(dir, child));
                    if (!success) {
                        return false;
                    }
                }
            }
        }
        return dir.delete();
    }

    /**
     * 清理播放器缓存(ijk/thunder/jpali)。
     */
    public static void cleanPlayerCache() {
        String ijkCachePath = getCachePath() + "/ijkcaches/";
        String thunderCachePath = getCachePath() + "/thunder/";
        File ijkCacheDir = new File(ijkCachePath);
        File thunderCacheDir = new File(thunderCachePath);
        try {
            if (ijkCacheDir.exists()) deleteDir(ijkCacheDir);
        } catch (Exception e) {
            LOG.e("cleanPlayerCache ijk error", e);
        }
        try {
            if (thunderCacheDir.exists()) deleteDir(thunderCacheDir);
        } catch (Exception e) {
            LOG.e("cleanPlayerCache thunder error", e);
        }
    }

    /**
     * 获取不含扩展名的文件名。
     *
     * @param filePath 文件路径
     * @return 不含扩展名的文件名
     */
    public static String getFileNameWithoutExt(String filePath) {
        if (TextUtils.isEmpty(filePath)) return "";
        String fileName = filePath;
        int p = fileName.lastIndexOf(File.separatorChar);
        if (p != -1) {
            fileName = fileName.substring(p + 1);
        }
        p = fileName.indexOf('.');
        if (p != -1) {
            fileName = fileName.substring(0, p);
        }
        return fileName;
    }

    /**
     * 清理目录下所有文件(不删目录本身)。
     *
     * @param dir 目标目录
     */
    public static void cleanDirectory(File dir) {
        if (!dir.exists()) return;
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File one : files) {
            try {
                deleteFile(one);
            } catch (Exception e) {
                LOG.e("cleanDirectory error", e);
            }
        }
    }

    /**
     * 删除文件或目录。
     *
     * @param file 要删除的文件
     */
    public static void deleteFile(File file) {
        if (file == null || !file.exists()) return;
        if (file.isFile()) {
            if (file.canWrite()) file.delete();
            return;
        }
        if (file.isDirectory()) {
            File[] files = file.listFiles();
            if (files != null && files.length > 0) {
                for (File one : files) {
                    deleteFile(one);
                }
            }
            if (file.canWrite()) file.delete();
        }
    }
}
