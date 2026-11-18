package kr.ddinghowa.mc;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/** 크롬 DevTools 프로토콜(CDP) 최소 클라이언트: 명령 보내고 응답 받기만 한다. */
final class Cdp implements WebSocket.Listener {

    private final AtomicInteger ids = new AtomicInteger();
    private final Map<Integer, CompletableFuture<JsonObject>> pending = new ConcurrentHashMap<>();
    private final StringBuilder partial = new StringBuilder();
    private final Object sendLock = new Object();
    private WebSocket ws;
    private volatile boolean closed;

    static Cdp connect(String url) {
        Cdp c = new Cdp();
        HttpClient http = HttpClient.newBuilder().proxy(HttpClient.Builder.NO_PROXY).build();
        c.ws = http.newWebSocketBuilder().buildAsync(URI.create(url), c).join();
        return c;
    }

    CompletableFuture<JsonObject> send(String method, JsonObject params) {
        int id = ids.incrementAndGet();
        CompletableFuture<JsonObject> f = new CompletableFuture<>();
        if (closed) {
            f.completeExceptionally(new IllegalStateException("CDP 연결이 닫혔습니다"));
            return f;
        }
        pending.put(id, f);
        JsonObject msg = new JsonObject();
        msg.addProperty("id", id);
        msg.addProperty("method", method);
        msg.add("params", params == null ? new JsonObject() : params);
        synchronized (sendLock) {     // WebSocket 은 이전 전송이 끝나야 다음 전송 가능
            try {
                ws.sendText(msg.toString(), true).join();
            } catch (Exception e) {
                pending.remove(id);
                f.completeExceptionally(e);
            }
        }
        return f;
    }

    boolean isClosed() {
        return closed;
    }

    void close() {
        closed = true;
        try {
            ws.abort();
        } catch (Exception ignored) {
        }
        failAll();
    }

    private void failAll() {
        for (var f : pending.values()) f.completeExceptionally(new IllegalStateException("CDP 연결이 닫혔습니다"));
        pending.clear();
    }

    @Override
    public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
        partial.append(data);
        if (last) {
            String s = partial.toString();
            partial.setLength(0);
            try {
                JsonObject o = JsonParser.parseString(s).getAsJsonObject();
                if (o.has("id")) {
                    CompletableFuture<JsonObject> f = pending.remove(o.get("id").getAsInt());
                    if (f != null) {
                        if (o.has("error")) f.completeExceptionally(new IllegalStateException(o.get("error").toString()));
                        else f.complete(o.has("result") ? o.getAsJsonObject("result") : new JsonObject());
                    }
                }
            } catch (Exception ignored) {
            }
        }
        webSocket.request(1);
        return null;
    }

    @Override
    public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
        closed = true;
        failAll();
        return null;
    }

    @Override
    public void onError(WebSocket webSocket, Throwable error) {
        closed = true;
        failAll();
    }
}
