package com.grandis.nova.preorder.support;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.IntFunction;

/**
 * 작업 여러 개를 한꺼번에 출발시키고 각 결과(값 또는 던진 예외)를 순서대로 모은다.
 * 작업은 모두 만든 뒤 한 신호로 동시에 시작한다 — 먼저 만든 스레드가 먼저 끝나 경합이 사라지지 않게.
 */
public final class Concurrently {

    static final long TIMEOUT_SECONDS = 60;
    static final long SHUTDOWN_SECONDS = 10;

    private Concurrently() {
    }

    /** @param tasks 번호(0부터)를 받아 그 번째 작업을 만든다 */
    public static <T> List<Outcome<T>> run(int count, IntFunction<Callable<T>> tasks) throws InterruptedException {
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(count);
        List<Future<T>> futures = new ArrayList<>();
        Throwable failure = null;
        try {
            for (int i = 0; i < count; i++) {
                Callable<T> task = tasks.apply(i);
                futures.add(executor.submit(() -> {
                    start.await();
                    return task.call();
                }));
            }
            start.countDown();
            List<Outcome<T>> outcomes = new ArrayList<>();
            for (Future<T> future : futures) {
                outcomes.add(await(future));
            }
            return outcomes;
        } catch (Throwable e) {
            failure = e;
            throw e;
        } finally {
            stop(executor, futures, failure);
        }
    }

    /**
     * 시간 초과 · 작업 생성 실패로 빠져나와도 스레드를 남기지 않는다. 끝까지 기다리는 close() 는 쓰지 않는다.
     * 멈추지 않으면 실패로 알리되, 이미 실패 중이면 원래 원인을 가리지 않게 거기에 덧붙인다.
     */
    private static void stop(ExecutorService executor, List<? extends Future<?>> futures, Throwable failure)
            throws InterruptedException {
        futures.forEach(future -> future.cancel(true));
        executor.shutdownNow();
        if (executor.awaitTermination(SHUTDOWN_SECONDS, TimeUnit.SECONDS)) {
            return;
        }
        AssertionError stuck = new AssertionError("동시 작업 스레드가 %d초 안에 멈추지 않았다".formatted(SHUTDOWN_SECONDS));
        if (failure == null) {
            throw stuck;
        }
        failure.addSuppressed(stuck);
    }

    private static <T> Outcome<T> await(Future<T> future) throws InterruptedException {
        try {
            return new Outcome<>(future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS), null);
        } catch (ExecutionException e) {
            return new Outcome<>(null, e.getCause());
        } catch (TimeoutException e) {
            throw new AssertionError("동시 작업이 %d초 안에 끝나지 않았다".formatted(TIMEOUT_SECONDS), e);
        }
    }

    /** 작업 하나의 결과. 성공이면 value, 실패면 error. */
    public record Outcome<T>(T value, Throwable error) {

        public boolean succeeded() {
            return error == null;
        }
    }
}
