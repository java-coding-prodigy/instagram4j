package com.github.instagram4j.instagram4j;

import java.io.File;
import java.io.IOException;
import java.io.ObjectStreamException;
import java.io.Serializable;
import java.net.CookieHandler;
import java.net.CookieManager;
import java.net.CookieStore;
import java.net.http.HttpHeaders;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import com.github.instagram4j.instagram4j.actions.IGClientActions;
import com.github.instagram4j.instagram4j.exceptions.ExceptionallyHandler;
import com.github.instagram4j.instagram4j.exceptions.IGLoginException;
import com.github.instagram4j.instagram4j.exceptions.IGResponseException.IGFailedResponse;
import com.github.instagram4j.instagram4j.models.IGPayload;
import com.github.instagram4j.instagram4j.models.user.Profile;
import com.github.instagram4j.instagram4j.requests.IGRequest;
import com.github.instagram4j.instagram4j.requests.accounts.AccountsLoginRequest;
import com.github.instagram4j.instagram4j.requests.accounts.AccountsTwoFactorLoginRequest;
import com.github.instagram4j.instagram4j.requests.qe.QeSyncRequest;
import com.github.instagram4j.instagram4j.responses.IGResponse;
import com.github.instagram4j.instagram4j.responses.accounts.LoginResponse;
import com.github.instagram4j.instagram4j.utils.IGUtils;
import com.github.instagram4j.instagram4j.utils.SerializableCookieJar;
import com.github.instagram4j.instagram4j.utils.SerializeUtil;
import kotlin.Pair;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.NonNull;
import lombok.Setter;
import lombok.experimental.Accessors;
import lombok.extern.slf4j.Slf4j;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.CookieJar;
import okhttp3.OkHttpClient;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.jetbrains.annotations.NotNull;

import java.net.http.HttpClient;

@Data
@Slf4j
public class IGClient implements Serializable {
    private static final long serialVersionUID = -893265874836l;
    private final String $username;
    private final String $password;
    private transient String encryptionId, encryptionKey, authorization;
    @Accessors(chain = true)
    private transient HttpClient httpClient;
    private transient String sessionId;
    private transient IGClientActions actions;
    @Accessors(chain = true)
    private transient ExceptionallyHandler exceptionallyHandler;
    private String deviceId;
    private String guid;
    private String phoneId;
    @Setter(AccessLevel.PRIVATE)
    private boolean loggedIn = false;
    @Setter(AccessLevel.PRIVATE)
    private Profile selfProfile;
    @Accessors(chain = true)
    private IGDevice device = IGAndroidDevice.GOOD_DEVICES[0];

    public IGClient(String username, String password) {
        this(username, password, IGUtils.defaultHttpClientBuilder().build());
    }

    public IGClient(String username, String password, HttpClient client) {
        this.$username = username;
        this.$password = password;
        this.guid = IGUtils.randomUuid();
        this.phoneId = IGUtils.randomUuid();
        this.deviceId = IGUtils.generateDeviceId(username, password);
        this.httpClient = client;
        this.initializeDefaults();
    }

    private void initializeDefaults() {
        this.sessionId = IGUtils.randomUuid();
        this.actions = new IGClientActions(this);
        this.exceptionallyHandler = new ExceptionallyHandler() {

            @Override
            public <T> T handle(Throwable throwable, Class<T> type) {
                throw new CompletionException(throwable.getCause());
            }

        };
    }

    public IGClientActions actions() {
        return this.actions;
    }

    public CompletableFuture<LoginResponse> sendLoginRequest() {
        return new QeSyncRequest().execute(this)
                .thenCompose(res -> {
                    IGUtils.sleepSeconds(1);
                    return new AccountsLoginRequest($username,
                            IGUtils.encryptPassword(this.$password, this.encryptionId,
                                    this.encryptionKey)).execute(this);
                })
                .thenApply((res) -> {
                    this.setLoggedInState(res);

                    return res;
                });
    }

    public CompletableFuture<LoginResponse> sendLoginRequest(String code, String identifier) {
        return new QeSyncRequest().execute(this)
                .thenCompose(res -> new AccountsTwoFactorLoginRequest($username,
                        IGUtils.encryptPassword(this.$password, this.encryptionId,
                                this.encryptionKey),
                        code,
                        identifier).execute(this))
                .thenApply((res) -> {
                    this.setLoggedInState(res);

                    return res;
                });
    }

    public <T extends IGResponse> CompletableFuture<T> sendRequest(@NonNull IGRequest<T> req) {
        String uri = req.formUri(this).toString();
        log.info("Sending request : {}", uri);
        return this.httpClient.sendAsync(req.formRequest(this), res -> {
                    log.info("Response for {} : {}", uri, res.statusCode());
                    return new HttpResponse.BodySubscriber<T>() {
                        @Override
                        public void onSubscribe(Flow.Subscription subscription) {
                            //TODO
                        }

                        @Override
                        public void onNext(List<ByteBuffer> item) {
                            //TODO
                        }

                        @Override
                        public void onError(Throwable throwable) {
                            exceptionallyHandler.handle(throwable, req.getResponseType());
                        }

                        @Override
                        public void onComplete() {
                            //TODO
                        }

                        @Override
                        public CompletionStage<T> getBody() {
                            return new CompletableFuture<>();
                        }
                    };
                })
                .thenApply(res -> {
                    setFromResponseHeaders(res);
                    log.info("Response for {} with body (truncated) : {}",
                            res.request().uri(),
                            IGUtils.truncate(res.body().toString()));

                    return req.parseResponse(res);
                })
                .exceptionally((tr) -> {
                    return this.exceptionallyHandler.handle(tr, req.getResponseType());
                });

    }

    private void setLoggedInState(LoginResponse state) {
        if (!state.getStatus().equals("ok"))
            return;
        this.loggedIn = true;
        this.selfProfile = state.getLogged_in_user();
        log.info("Logged into {} ({})", selfProfile.getUsername(), selfProfile.getPk());
    }

    public String getCsrfToken() {
        return IGUtils.getCookieValue(IGUtils.getCookieStore(this.getHttpClient()), "csrftoken")
                .orElse("missing");
    }

    public <T> void setFromResponseHeaders(HttpResponse<T> res) {
        HttpHeaders headers = res.headers();
        headers.firstValue("ig-set-password-encryption-key-id")
                .ifPresent(s -> this.encryptionId = s);
        headers.firstValue("ig-set-password-encryption-pub-key")
                .ifPresent(s -> this.encryptionKey = s);
        headers.firstValue("ig-set-authorization")
                .ifPresent(s -> this.authorization = s);
    }

    public IGPayload setIGPayloadDefaults(IGPayload load) {
        load.set_csrftoken(this.getCsrfToken());
        load.setDevice_id(this.deviceId);
        if (selfProfile != null) {
            load.set_uid(selfProfile.getPk().toString());
            load.set_uuid(this.guid);
        } else {
            load.setId(this.guid);
        }
        load.setGuid(this.guid);
        load.setPhone_id(this.phoneId);

        return load;
    }

    public static IGClient.Builder builder() {
        return new IGClient.Builder();
    }

    public static IGClient deserialize(File clientFile, File cookieFile)
            throws ClassNotFoundException, IOException {
        return deserialize(clientFile, cookieFile, IGUtils.defaultHttpClientBuilder());
    }

    public static IGClient deserialize(File clientFile, File cookieFile,
                                       HttpClient.Builder clientBuilder) throws ClassNotFoundException, IOException {
        IGClient client = SerializeUtil.deserialize(clientFile, IGClient.class);
        CookieHandler handler = SerializeUtil.deserialize(cookieFile, CookieHandler.class);

        client.httpClient = clientBuilder.
                cookieHandler(handler)
                .build();

        return client;
    }

    public void serialize(File clientFile, File cookieFile) throws IOException {
        SerializeUtil.serialize(this, clientFile);
        SerializeUtil.serialize(IGUtils.getCookieStore(this.getHttpClient()), cookieFile);
    }

    private Object readResolve() throws ObjectStreamException {
        this.initializeDefaults();
        if (loggedIn)
            log.info("Logged into {} ({})", selfProfile.getUsername(), selfProfile.getPk());
        return this;
    }

    @Accessors(fluent = true)
    @Setter
    @AllArgsConstructor
    @NoArgsConstructor
    public static class Builder {
        private String username;
        private String password;
        private HttpClient client;
        private IGDevice device = IGAndroidDevice.GOOD_DEVICES[0];
        private LoginHandler onChallenge;
        private LoginHandler onTwoFactor;
        private BiConsumer<IGClient, LoginResponse> onLogin = (client, login) -> {
        };

        public IGClient build() {
            return new IGClient(username, password, Optional.ofNullable(client)
                    .orElseGet(() -> IGUtils.defaultHttpClientBuilder().build())).setDevice(device);
        }

        public IGClient simulatedLogin(Consumer<List<CompletableFuture<?>>> postLoginResponses)
                throws IGLoginException {
            IGClient client = build();
            client.actions.simulate().preLoginFlow().forEach(CompletableFuture::join);
            onLogin.accept(client, performLogin(client));
            postLoginResponses.accept(client.actions.simulate().postLoginFlow());

            return client;
        }

        public IGClient simulatedLogin() throws IGLoginException {
            return simulatedLogin((res) -> {
            });
        }

        public IGClient login() throws IGLoginException {
            IGClient client = build();

            onLogin.accept(client, performLogin(client));

            return client;
        }

        private LoginResponse performLogin(IGClient client) throws IGLoginException {
            LoginResponse response = client.sendLoginRequest()
                    .exceptionally(tr -> {
                        LoginResponse loginResponse =
                                IGFailedResponse.of(tr.getCause(), LoginResponse.class);
                        if (loginResponse.getTwo_factor_info() != null && onTwoFactor != null) {
                            loginResponse = onTwoFactor.accept(client, loginResponse);
                        }
                        if (loginResponse.getChallenge() != null && onChallenge != null) {
                            loginResponse = onChallenge.accept(client, loginResponse);
                            client.setLoggedInState(loginResponse);
                        }

                        return loginResponse;
                    })
                    .join();

            if (!client.isLoggedIn()) {
                throw new IGLoginException(client, response);
            }

            return response;
        }

        @FunctionalInterface
        public static interface LoginHandler {
            public LoginResponse accept(IGClient client, LoginResponse t);
        }

    }
}
