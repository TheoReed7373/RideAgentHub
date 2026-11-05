package com.fancy.rideagenthub.agentbase.qweather.config;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRequest;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;
import java.util.zip.GZIPInputStream;

/**
 * 处理服务端返回的 gzip 压缩响应（Content-Encoding: gzip）。
 *
 * QWeather 文档说明返回 JSON 可能经过 gzip 压缩；为避免底层 HTTP client
 * 不自动解压导致 JSON 反序列化失败，这里做一次兜底解压。
 */
public class GzipDecompressingInterceptor implements ClientHttpRequestInterceptor {

    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution)
            throws IOException {
        // 主动声明支持 gzip
        request.getHeaders().add(HttpHeaders.ACCEPT_ENCODING, "gzip");

        ClientHttpResponse response = execution.execute(request, body);
        String contentEncoding = response.getHeaders().getFirst(HttpHeaders.CONTENT_ENCODING);
        if (contentEncoding == null) {
            return response;
        }

        if (!contentEncoding.toLowerCase(Locale.ROOT).contains("gzip")) {
            return response;
        }

        return new GzipDecompressingClientHttpResponse(response);
    }

    private static final class GzipDecompressingClientHttpResponse implements ClientHttpResponse {
        private final ClientHttpResponse delegate;

        private GzipDecompressingClientHttpResponse(ClientHttpResponse delegate) {
            this.delegate = delegate;
        }

        @Override
        public HttpHeaders getHeaders() {
            HttpHeaders headers = new HttpHeaders();
            headers.putAll(delegate.getHeaders());
            // 解压后不应再暴露 gzip 编码头，避免上层重复处理
            headers.remove(HttpHeaders.CONTENT_ENCODING);
            return headers;
        }

        @Override
        public InputStream getBody() throws IOException {
            return new GZIPInputStream(delegate.getBody());
        }

        @Override
        public HttpStatusCode getStatusCode() throws IOException {
            return delegate.getStatusCode();
        }

        @Override
        public String getStatusText() throws IOException {
            return delegate.getStatusText();
        }

        @Override
        public void close() {
            delegate.close();
        }
    }
}
