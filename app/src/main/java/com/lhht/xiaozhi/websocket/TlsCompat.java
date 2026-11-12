package com.lhht.xiaozhi.websocket;

import android.util.Log;

import java.io.IOException;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.Socket;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.cert.CertificateException;
import java.security.cert.CertificateParsingException;
import java.security.cert.X509Certificate;
import java.util.Collection;
import java.util.List;
import java.util.Locale;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

/**
 * Android 7.0（API 24）以下的兼容 TLS 工厂。
 *
 * <p>旧系统的 {@link javax.net.ssl.SSLParameters} 没有
 * {@code setEndpointIdentificationAlgorithm()}，裸 {@link javax.net.ssl.SSLSocket}
 * 默认只校验证书链、不校验主机名（只有 HttpsURLConnection 会做）。
 * 若直接跳过校验，任何被系统信任的证书都能冒充服务器，形成中间人风险。
 *
 * <p>这里在不改动 Java-WebSocket 连接流程的前提下，用自定义 TrustManager 在 TLS
 * 握手阶段同时完成：系统 CA 证书链校验 + RFC 6125 主机名匹配（SAN / CN 回退）。
 */
final class TlsCompat {
    private static final int SAN_TYPE_DNS = 2;
    private static final int SAN_TYPE_IP = 7;

    private TlsCompat() {
    }

    /**
     * 创建会校验证书主机名的 SSLSocketFactory。
     *
     * @param expectedHost 连接目标的 host（来自 WebSocket URI）
     */
    static SSLSocketFactory createHostnameVerifyingSocketFactory(String expectedHost)
            throws GeneralSecurityException {
        if (expectedHost == null || expectedHost.isEmpty()) {
            throw new GeneralSecurityException("缺少服务器主机名，无法校验证书");
        }
        final X509TrustManager systemTrust = systemX509TrustManager();
        final String host = expectedHost.toLowerCase(Locale.US);

        X509TrustManager hostnameChecking = new X509TrustManager() {
            @Override
            public void checkClientTrusted(X509Certificate[] chain, String authType)
                    throws CertificateException {
                systemTrust.checkClientTrusted(chain, authType);
            }

            @Override
            public void checkServerTrusted(X509Certificate[] chain, String authType)
                    throws CertificateException {
                systemTrust.checkServerTrusted(chain, authType);
                if (chain == null || chain.length == 0) {
                    throw new CertificateException("服务器未返回证书");
                }
                if (!matchesHostname(host, chain[0])) {
                    throw new CertificateException(
                            "服务器证书与主机名不匹配: " + host + "（证书信息: " + describe(chain[0]) + "）");
                }
            }

            @Override
            public X509Certificate[] getAcceptedIssuers() {
                return systemTrust.getAcceptedIssuers();
            }
        };

        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, new TrustManager[]{hostnameChecking}, null);
        return new SniSocketFactory(context.getSocketFactory());
    }

    /**
     * 包装系统 SSLSocketFactory，保证旧系统一定带上 SNI。
     *
     * <p>Android 5.x/6.x 的 Conscrypt 在部分构造路径下不会发送 SNI；官方网关按 SNI
     * 选择证书，没有 SNI 时会返回默认证书（实测 api.tenclass.net 会拿到 mqtt.tenclass.net
     * 的证书），于是主机名校验必然失败。旧系统没有公开的 SNI API，只能反射内部字段。
     */
    private static final class SniSocketFactory extends SSLSocketFactory {
        private static final String TAG = "TlsCompat";
        private final SSLSocketFactory delegate;

        SniSocketFactory(SSLSocketFactory delegate) {
            this.delegate = delegate;
        }

        @Override
        public String[] getDefaultCipherSuites() {
            return delegate.getDefaultCipherSuites();
        }

        @Override
        public String[] getSupportedCipherSuites() {
            return delegate.getSupportedCipherSuites();
        }

        @Override
        public Socket createSocket(Socket s, String host, int port, boolean autoClose)
                throws IOException {
            return forceSni(delegate.createSocket(s, host, port, autoClose), host);
        }

        @Override
        public Socket createSocket(String host, int port) throws IOException {
            return forceSni(delegate.createSocket(host, port), host);
        }

        @Override
        public Socket createSocket(String host, int port, InetAddress localHost, int localPort)
                throws IOException {
            return forceSni(delegate.createSocket(host, port, localHost, localPort), host);
        }

        @Override
        public Socket createSocket(InetAddress host, int port) throws IOException {
            return delegate.createSocket(host, port);
        }

        @Override
        public Socket createSocket(InetAddress address, int port, InetAddress localAddress,
                                   int localPort) throws IOException {
            return delegate.createSocket(address, port, localAddress, localPort);
        }

        @Override
        public Socket createSocket() throws IOException {
            return delegate.createSocket();
        }
    }

    private static Socket forceSni(Socket socket, String host) {
        if (socket == null || host == null) return socket;
        boolean hostnameSet = invoke(socket, "setHostname", String.class, host);
        boolean sniEnabled = invoke(socket, "setUseSni", boolean.class, true);
        if (hostnameSet || sniEnabled) {
            Log.i("TlsCompat", "已通过反射开启 SNI: host=" + host);
        } else {
            Log.w("TlsCompat", "系统 SSLSocket 不支持反射开启 SNI，host=" + host);
        }
        return socket;
    }

    private static boolean invoke(Socket socket, String method, Class<?> parameterType,
                                  Object argument) {
        try {
            Method m = socket.getClass().getMethod(method, parameterType);
            m.invoke(socket, argument);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 取系统内置 CA 对应的 TrustManager；null KeyStore 表示使用平台根证书。 */
    private static X509TrustManager systemX509TrustManager() throws GeneralSecurityException {
        TrustManagerFactory factory =
                TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        factory.init((KeyStore) null);
        for (TrustManager manager : factory.getTrustManagers()) {
            if (manager instanceof X509TrustManager) {
                return (X509TrustManager) manager;
            }
        }
        throw new GeneralSecurityException("系统未提供 X509 TrustManager");
    }

    /** 证书摘要，便于用户在“证书不匹配”时判断是网络被劫持还是配置写错。 */
    private static String describe(X509Certificate certificate) {
        StringBuilder sb = new StringBuilder();
        try {
            sb.append("subject=").append(certificate.getSubjectX500Principal().getName());
        } catch (Exception ignored) {
            sb.append("subject=<unavailable>");
        }
        try {
            Collection<List<?>> sans = certificate.getSubjectAlternativeNames();
            sb.append(", SAN=").append(sans == null ? "无" : sans.toString());
        } catch (Exception e) {
            sb.append(", SAN=<解析失败: ").append(e.getMessage()).append('>');
        }
        return sb.toString();
    }

    /** 证书是否对 host 有效：优先 SAN（dNSName / iPAddress），没有 SAN 时回退 CN。 */
    static boolean matchesHostname(String host, X509Certificate certificate) {
        try {
            Collection<List<?>> sanEntries = certificate.getSubjectAlternativeNames();
            if (sanEntries != null) {
                boolean hasDnsOrIpSan = false;
                for (List<?> entry : sanEntries) {
                    if (entry == null || entry.size() < 2) continue;
                    Object type = entry.get(0);
                    Object value = entry.get(1);
                    if (!(type instanceof Integer) || !(value instanceof String)) continue;
                    int sanType = (Integer) type;
                    if (sanType == SAN_TYPE_DNS) {
                        hasDnsOrIpSan = true;
                        if (matchesDnsName(host, (String) value)) return true;
                    } else if (sanType == SAN_TYPE_IP) {
                        hasDnsOrIpSan = true;
                        if (host.equalsIgnoreCase(((String) value).trim())) return true;
                    }
                }
                // 证书带 DNS/IP 类型 SAN 时，按 RFC 6125 不再回退 CN
                if (hasDnsOrIpSan) return false;
            }
        } catch (CertificateParsingException e) {
            return false;
        }
        return matchesCommonName(host, certificate);
    }

    /**
     * 从 RFC 2253 格式的 Subject DN 中取 CN 做匹配。
     * Android 不提供 javax.naming，这里只做够用级别的拆分（按未转义的逗号分段）。
     */
    private static boolean matchesCommonName(String host, X509Certificate certificate) {
        String dn = certificate.getSubjectX500Principal().getName();
        for (String rdn : dn.split("(?<!\\\\),")) {
            int eq = rdn.indexOf('=');
            if (eq <= 0) continue;
            if (!"CN".equalsIgnoreCase(rdn.substring(0, eq).trim())) continue;
            String value = rdn.substring(eq + 1).trim();
            if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
                value = value.substring(1, value.length() - 1);
            }
            if (matchesDnsName(host, value)) return true;
        }
        return false;
    }

    /** 单层通配符匹配：{@code *.example.com} 只匹配 {@code a.example.com}。 */
    private static boolean matchesDnsName(String host, String pattern) {
        String normalizedHost = host.toLowerCase(Locale.US);
        String normalizedPattern = pattern.trim().toLowerCase(Locale.US);
        if (normalizedPattern.startsWith("*.")) {
            String suffix = normalizedPattern.substring(1); // ".example.com"
            if (!normalizedHost.endsWith(suffix)) return false;
            String label = normalizedHost.substring(0, normalizedHost.length() - suffix.length());
            return !label.isEmpty() && label.indexOf('.') < 0;
        }
        return normalizedHost.equals(normalizedPattern);
    }
}
