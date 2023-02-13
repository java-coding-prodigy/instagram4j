package com.github.instagram4j.instagram4j.requests;

import com.github.instagram4j.instagram4j.IGClient;
import com.github.instagram4j.instagram4j.responses.IGResponse;

import okhttp3.Request;

import java.net.http.HttpRequest;

public abstract class IGGetRequest<T extends IGResponse> extends IGRequest<T> {

    @Override
    public HttpRequest formRequest(IGClient client) {
        HttpRequest.Builder req = HttpRequest.newBuilder()
                .uri(this.formUri(client));
        this.applyHeaders(client, req);

        return req.build();
    }
}
