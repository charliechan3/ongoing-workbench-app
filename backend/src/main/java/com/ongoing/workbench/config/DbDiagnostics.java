package com.ongoing.workbench.config;

import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.Arrays;
import java.util.Collections;

/**
 * 云端数据库连通性自检。默认关闭，设置环境变量 APP_DIAG_DB=true 后启用。
 *
 * <p>为什么需要它：PaaS 上只能看日志，而 Hibernate 连不上库时会把原始异常吞掉，
 * 只抛 "Unable to determine Dialect without JDBC metadata"。本探针把链路上每一步
 * （DNS → TCP → PG SSLRequest → TLS 握手 → JDBC 登录）单独打出来，
 * 一眼就能看出是网络、TLS 还是认证的问题。
 *
 * <p>注意两点：
 * <ul>
 *   <li>PostgreSQL 必须先收到 SSLRequest 才肯握手，直接拿 SSLSocket 去连 5432 一定失败，
 *       所以这里手工完成了 PG 的 SSL 协商流程；</li>
 *   <li>主 TLS 检查用「不校验证书」的上下文，因为 sslmode=require 本身就不校验证书；
 *       用默认信任库会误报 PKIX，那是 verify-ca/verify-full 才会遇到的问题。</li>
 * </ul>
 */
public final class DbDiagnostics {

    private static final String P = "[DB-DIAG] ";
    private static final byte[] SSL_REQUEST = {0, 0, 0, 8, 0x04, (byte) 0xD2, 0x16, 0x2F};

    private DbDiagnostics() {
    }

    public static void runIfEnabled() {
        String flag = System.getenv("APP_DIAG_DB");
        if (flag == null) {
            flag = System.getProperty("app.diag.db");
        }
        if (!"true".equalsIgnoreCase(flag)) {
            return;
        }
        try {
            run();
        } catch (Throwable t) {
            System.out.println(P + "probe aborted: " + t);
        }
    }

    private static void run() {
        String url = firstNonBlank(System.getenv("CLOUD_DB_URL"), System.getProperty("spring.datasource.url"));
        String user = firstNonBlank(System.getenv("CLOUD_DB_USER"), System.getProperty("spring.datasource.username"));
        String pass = firstNonBlank(System.getenv("CLOUD_DB_PASS"), System.getProperty("spring.datasource.password"));

        System.out.println(P + "===== cloud db connectivity probe =====");
        System.out.println(P + "java  : " + System.getProperty("java.version")
                + " / " + System.getProperty("java.vendor")
                + " / " + System.getProperty("os.name") + " " + System.getProperty("os.arch"));
        System.out.println(P + "url   : " + url);
        System.out.println(P + "user  : " + user);
        try {
            System.out.println(P + "tls   : " + Arrays.toString(
                    SSLContext.getDefault().getSupportedSSLParameters().getProtocols()));
        } catch (Throwable t) {
            System.out.println(P + "tls   : cannot read default SSLContext: " + t);
        }

        if (url == null || !url.startsWith("jdbc:postgresql://")) {
            System.out.println(P + "SKIP: not a postgres url");
            return;
        }

        String host = splitHost(url);
        int port = splitPort(url);

        // 1) DNS
        InetAddress[] addrs;
        try {
            long t0 = System.currentTimeMillis();
            addrs = InetAddress.getAllByName(host);
            System.out.println(P + "1) dns   OK " + (System.currentTimeMillis() - t0) + "ms -> " + Arrays.toString(addrs));
        } catch (Throwable t) {
            System.out.println(P + "1) dns   FAIL " + t);
            return;
        }

        // 2) TCP（逐个地址试，IPv6-only 时能看出来）
        InetAddress good = null;
        for (InetAddress a : addrs) {
            try {
                long t0 = System.currentTimeMillis();
                try (Socket s = new Socket()) {
                    s.connect(new InetSocketAddress(a, port), 8000);
                }
                System.out.println(P + "2) tcp   OK " + a.getHostAddress() + ":" + port
                        + " " + (System.currentTimeMillis() - t0) + "ms");
                good = a;
                break;
            } catch (Throwable t) {
                System.out.println(P + "2) tcp   FAIL " + a.getHostAddress() + ":" + port + " " + t);
            }
        }
        if (good == null) {
            System.out.println(P + "=> TCP 不通：出网端口被封或地址不可达，与 sslmode 无关");
            return;
        }

        // 3) PG SSLRequest
        try (Socket raw = new Socket()) {
            raw.connect(new InetSocketAddress(good, port), 8000);
            raw.setSoTimeout(8000);
            OutputStream out = raw.getOutputStream();
            out.write(SSL_REQUEST);
            out.flush();
            int r = raw.getInputStream().read();
            System.out.println(P + "3) pgssl " + (r == 'S' ? "OK server accepts SSL ('S')"
                    : r == 'N' ? "server REFUSED ssl ('N') -> require 必然失败" : "unexpected reply byte=" + r));
        } catch (Throwable t) {
            System.out.println(P + "3) pgssl FAIL " + t);
            return;
        }

        // 4) 主检查：不校验证书的 TLS 握手（等价于 sslmode=require 的行为）
        boolean tlsOk;
        try {
            tlsOk = handshake(good, host, port, trustAllFactory(), "4) tls   ");
        } catch (Throwable t) {
            System.out.println(P + "4) tls   FAIL cannot build SSLContext: " + t);
            tlsOk = false;
        }
        if (!tlsOk) {
            System.out.println(P + "=> TLS 握手被中断：问题在运行环境的 crypto 模块或出网对 SNI 的阻断，与账号密码无关");
        } else {
            // 4b) 附加信息：默认信任库结果（只影响 verify-ca/verify-full，require 不看）
            handshake(good, host, port, (SSLSocketFactory) SSLSocketFactory.getDefault(), "4b) trust ");
        }

        // 5) JDBC 登录
        try (Connection cn = DriverManager.getConnection(url, user, pass)) {
            System.out.println(P + "5) jdbc  OK " + cn.getMetaData().getDatabaseProductVersion());
        } catch (Throwable t) {
            System.out.println(P + "5) jdbc  FAIL " + t.getClass().getName() + ": " + t.getMessage());
            Throwable c = t.getCause();
            int i = 0;
            while (c != null && i++ < 4) {
                System.out.println(P + "    caused by " + c.getClass().getName() + ": " + c.getMessage());
                c = c.getCause();
            }
        }
        System.out.println(P + "===== probe done =====");
    }

    /** 按 PG 的协议先发 SSLRequest，再做 TLS 握手（带 SNI）。 */
    private static boolean handshake(InetAddress addr, String host, int port, SSLSocketFactory f, String label) {
        try (Socket raw = new Socket()) {
            raw.connect(new InetSocketAddress(addr, port), 8000);
            raw.setSoTimeout(10000);
            OutputStream out = raw.getOutputStream();
            out.write(SSL_REQUEST);
            out.flush();
            if (raw.getInputStream().read() != 'S') {
                System.out.println(P + label + "SKIP: server refused ssl");
                return false;
            }
            try (SSLSocket ssl = (SSLSocket) f.createSocket(raw, host, port, true)) {
                SSLParameters p = ssl.getSSLParameters();
                p.setServerNames(Collections.singletonList(new SNIHostName(host)));
                ssl.setSSLParameters(p);
                long t0 = System.currentTimeMillis();
                ssl.startHandshake();
                System.out.println(P + label + "OK " + (System.currentTimeMillis() - t0) + "ms "
                        + ssl.getSession().getProtocol() + " " + ssl.getSession().getCipherSuite());
                for (Certificate c : ssl.getSession().getPeerCertificates()) {
                    if (c instanceof X509Certificate x) {
                        System.out.println(P + "    cert  " + x.getSubjectX500Principal());
                    }
                }
                return true;
            }
        } catch (Throwable t) {
            System.out.println(P + label + "FAIL " + t.getClass().getName() + ": " + t.getMessage());
            Throwable c = t.getCause();
            int i = 0;
            while (c != null && i++ < 4) {
                System.out.println(P + "    caused by " + c.getClass().getName() + ": " + c.getMessage());
                c = c.getCause();
            }
            return false;
        }
    }

    private static SSLSocketFactory trustAllFactory() throws Exception {
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(null, new TrustManager[]{new X509TrustManager() {
            @Override
            public void checkClientTrusted(X509Certificate[] chain, String authType) {
            }

            @Override
            public void checkServerTrusted(X509Certificate[] chain, String authType) {
            }

            @Override
            public X509Certificate[] getAcceptedIssuers() {
                return new X509Certificate[0];
            }
        }}, new SecureRandom());
        return ctx.getSocketFactory();
    }

    private static String splitHost(String url) {
        String s = url.substring("jdbc:postgresql://".length());
        int q = s.indexOf('?');
        if (q >= 0) {
            s = s.substring(0, q);
        }
        int slash = s.indexOf('/');
        if (slash >= 0) {
            s = s.substring(0, slash);
        }
        int colon = s.lastIndexOf(':');
        return colon > 0 ? s.substring(0, colon) : s;
    }

    private static int splitPort(String url) {
        String s = url.substring("jdbc:postgresql://".length());
        int q = s.indexOf('?');
        if (q >= 0) {
            s = s.substring(0, q);
        }
        int slash = s.indexOf('/');
        if (slash >= 0) {
            s = s.substring(0, slash);
        }
        int colon = s.lastIndexOf(':');
        if (colon > 0) {
            try {
                return Integer.parseInt(s.substring(colon + 1));
            } catch (NumberFormatException ignored) {
                // fall through
            }
        }
        return 5432;
    }

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) {
            return a;
        }
        return b == null ? "" : b;
    }
}
