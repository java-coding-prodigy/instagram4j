package com.github.instagram4j.instagram4j.requests;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.github.instagram4j.instagram4j.IGClient;
import com.github.instagram4j.instagram4j.IGConstants;
import com.github.instagram4j.instagram4j.exceptions.IGResponseException;
import com.github.instagram4j.instagram4j.responses.IGResponse;
import com.github.instagram4j.instagram4j.utils.IGUtils;

import kotlin.Pair;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import okhttp3.HttpUrl;
import okhttp3.Request;
import okhttp3.Response;

@Slf4j
public abstract class IGRequest<T extends IGResponse> {

    public abstract String path();

    public abstract HttpRequest formRequest(IGClient client);

    public abstract Class<T> getResponseType();

    public String apiPath() {
        return IGConstants.API_V1;
    }

    public String baseApiUrl() {
        return IGConstants.BASE_API_URL;
    }

    public String getQueryString(IGClient client) {
        return "";
    }

    public URI formUri(IGClient client) {
        return URI.create(baseApiUrl() + apiPath() + path() + getQueryString(client));
    }

    public CompletableFuture<T> execute(IGClient client) {
        return client.sendRequest(this);
    }

    @SneakyThrows
    protected String mapQueryString(Object... strings) {
        StringBuilder builder = new StringBuilder("?");

        for (int i = 0; i < strings.length; i += 2) {
            if (i + 1 < strings.length && strings[i] != null && strings[i + 1] != null
                    && !strings[i].toString().isEmpty()
                    && !strings[i + 1].toString().isEmpty()) {
                builder.append(URLEncoder.encode(strings[i].toString(), "utf-8")).append("=")
                        .append(URLEncoder.encode(strings[i + 1].toString(), "utf-8")).append("&");
            }
        }

        return builder.substring(0, builder.length() - 1);
    }

    @SneakyThrows(IOException.class)
    public T parseResponse(HttpResponse<T> response) {
        T igResponse = parseResponse(response.body().toString());
        igResponse.setStatusCode(response.statusCode());
        if (response.statusCode() / 100 == 2 || (igResponse.getStatus() != null && igResponse.getStatus().equals("fail"))) {
            throw new IGResponseException(igResponse);
        }

        return igResponse;
    }

    public T parseResponse(String json) throws JsonProcessingException {
        return parseResponse(json, getResponseType());
    }

    public <U> U parseResponse(String json, Class<U> type) throws JsonProcessingException {
        log.debug("{} parsing response : {}", apiPath() + path(), json);
        U response = IGUtils.jsonToObject(json, type);

        return response;
    }

    protected HttpRequest.Builder applyHeaders(IGClient client, HttpRequest.Builder req) {
        req.header("Connection", "close")
                .header("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                .header("Accept-Language", "en-US")
                .header("X-IG-Capabilities", client.getDevice().getCapabilities())
                .header("X-IG-App-ID", IGConstants.APP_ID)
                .header("User-Agent", client.getDevice().getUserAgent())
                .header("X-IG-Connection-Type", "WIFI")
                .header("X-Ads-Opt-Out", "0")
                .header("X-CM-Bandwidth-KBPS", "-1.000")
                .header("X-CM-Latency", "-1.000")
                .header("X-IG-App-Locale", "en_US")
                .header("X-IG-Device-Locale", "en_US")
                .header("X-Pigeon-Session-Id", IGUtils.randomUuid())
                .header("X-Pigeon-Rawclienttime", System.currentTimeMillis() + "")
                .header("X-IG-Connection-Speed", ThreadLocalRandom.current().nextInt(2000, 4000) + "kbps")
                .header("X-IG-Bandwidth-Speed-KBPS", "-1.000")
                .header("X-IG-Bandwidth-TotalBytes-B", "0")
                .header("X-IG-Bandwidth-TotalTime-MS", "0")
                .header("X-IG-Extended-CDN-Thumbnail-Cache-Busting-Value", "1000")
                .header("X-IG-Device-ID", client.getGuid())
                .header("X-IG-Android-ID", client.getDeviceId())
                .header("X-FB-HTTP-engine", "Liger");
        Optional.ofNullable(client.getAuthorization())
                .ifPresent(s -> req.header("Authorization", s));

        return req;
    }

}
