package dev.nimbus.testutil;

import dev.nimbus.upstream.UpstreamClient;
import dev.nimbus.upstream.UpstreamException;
import dev.nimbus.upstream.UpstreamResponse;

import java.net.URI;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.function.Function;

/**
 * In-process {@link UpstreamClient} with scripted responses and exceptions.
 *
 * <p>One-shot behaviors queued with {@link #enqueue} run first, in order; then the default from {@link #respond}.
 * Every call is recorded, and an optional gate blocks calls until a test opens it.
 */
public final class FakeUpstreamClient implements UpstreamClient {

    private final Queue<Function<URI, UpstreamResponse>> queued = new ConcurrentLinkedQueue<>();
    private final List<URI> calls = new CopyOnWriteArrayList<>();
    private volatile Function<URI, UpstreamResponse> defaultBehavior =
            uri -> TestEntries.response(200, "{\"uri\":\"" + uri + "\"}", "Content-Type", "application/json");
    private volatile CountDownLatch gate;

    /** Sets the behavior for every call that has no queued behavior. */
    public FakeUpstreamClient respond(Function<URI, UpstreamResponse> behavior) {
        this.defaultBehavior = behavior;
        return this;
    }

    public FakeUpstreamClient respond(UpstreamResponse response) {
        return respond(uri -> response);
    }

    /** Queues a behavior for the next call only. */
    public FakeUpstreamClient enqueue(Function<URI, UpstreamResponse> behavior) {
        queued.add(behavior);
        return this;
    }

    public FakeUpstreamClient enqueue(UpstreamResponse response) {
        return enqueue(uri -> response);
    }

    /** Makes every call throw {@code exception}. */
    public FakeUpstreamClient fail(RuntimeException exception) {
        return respond(uri -> {
            throw exception;
        });
    }

    /** Makes calls block until {@code latch} is opened. */
    public FakeUpstreamClient gate(CountDownLatch latch) {
        this.gate = latch;
        return this;
    }

    @Override
    public UpstreamResponse fetch(URI uri) {
        calls.add(uri);
        CountDownLatch g = gate;
        if (g != null) {
            try {
                g.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new UpstreamException(UpstreamException.Kind.INTERRUPTED, "interrupted", e);
            }
        }
        Function<URI, UpstreamResponse> behavior = queued.poll();
        return (behavior != null ? behavior : defaultBehavior).apply(uri);
    }

    public int callCount() {
        return calls.size();
    }

    public List<URI> calls() {
        return List.copyOf(calls);
    }

    public URI lastCall() {
        return calls.isEmpty() ? null : calls.get(calls.size() - 1);
    }
}
