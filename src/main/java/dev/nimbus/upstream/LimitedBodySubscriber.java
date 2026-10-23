package dev.nimbus.upstream;

import java.io.IOException;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalLong;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;

/**
 * Collects a response body into a {@code byte[]}, aborting the download as soon as it exceeds a limit.
 *
 * <p>The cap is enforced while the body streams in, because the upstream often uses chunked encoding without
 * {@code Content-Length}. When a {@code Content-Length} over the limit is declared, the body is rejected before
 * any of it is read. On JDK 25+, {@code BodyHandlers.limiting} does the same job.
 */
public final class LimitedBodySubscriber implements HttpResponse.BodySubscriber<byte[]> {

    /** The body exceeded the limit. */
    public static final class ResponseTooLargeException extends IOException {
        private static final long serialVersionUID = 1L;

        ResponseTooLargeException(String message) {
            super(message);
        }
    }

    private final long limit;
    private final CompletableFuture<byte[]> result = new CompletableFuture<>();
    private final List<byte[]> chunks = new ArrayList<>();
    private long received;
    private Flow.Subscription subscription;

    LimitedBodySubscriber(long limit, OptionalLong declaredLength) {
        if (limit < 0) {
            throw new IllegalArgumentException("limit must be >= 0");
        }
        this.limit = limit;
        if (declaredLength.isPresent() && declaredLength.getAsLong() > limit) {
            result.completeExceptionally(new ResponseTooLargeException(
                    "Content-Length " + declaredLength.getAsLong() + " exceeds limit " + limit));
        }
    }

    /** A body handler that checks {@code Content-Length} first, then caps the streamed body at {@code limit}. */
    public static HttpResponse.BodyHandler<byte[]> handler(long limit) {
        return info -> new LimitedBodySubscriber(limit, contentLength(info));
    }

    private static OptionalLong contentLength(HttpResponse.ResponseInfo info) {
        try {
            return info.headers().firstValueAsLong("Content-Length");
        } catch (NumberFormatException e) {
            return OptionalLong.empty();
        }
    }

    @Override
    public void onSubscribe(Flow.Subscription subscription) {
        this.subscription = subscription;
        if (result.isDone()) {
            subscription.cancel(); // Rejected by Content-Length: don't read the body.
        } else {
            subscription.request(Long.MAX_VALUE);
        }
    }

    @Override
    public void onNext(List<ByteBuffer> buffers) {
        if (result.isDone()) {
            return;
        }
        for (ByteBuffer buffer : buffers) {
            received += buffer.remaining();
            if (received > limit) {
                subscription.cancel();
                chunks.clear();
                result.completeExceptionally(new ResponseTooLargeException("Response body exceeds limit " + limit));
                return;
            }
            byte[] chunk = new byte[buffer.remaining()];
            buffer.get(chunk);
            chunks.add(chunk);
        }
    }

    @Override
    public void onError(Throwable throwable) {
        result.completeExceptionally(throwable);
    }

    @Override
    public void onComplete() {
        byte[] body = new byte[(int) received];
        int offset = 0;
        for (byte[] chunk : chunks) {
            System.arraycopy(chunk, 0, body, offset, chunk.length);
            offset += chunk.length;
        }
        chunks.clear();
        result.complete(body);
    }

    @Override
    public CompletionStage<byte[]> getBody() {
        return result;
    }
}
