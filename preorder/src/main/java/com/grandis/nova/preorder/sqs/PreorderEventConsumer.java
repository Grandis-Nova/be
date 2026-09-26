package com.grandis.nova.preorder.sqs;

import com.grandis.nova.preorder.event.PreorderEventDispatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.MessageSystemAttributeName;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * preorder-events 큐를 롱 폴링으로 받아 분배기에 넘긴다. 처리하면 지우고, 실패하면 지우지 않고 다시 보일 때까지 늦춘다
 * (지수 백오프 + 지터). 계속 실패하면 큐의 재드라이브 정책이 DLQ 로 옮긴다. 처리기는 같은 메시지를 두 번 받아도 된다.
 * 종료 때는 새로 받지 않고, 받은 묶음을 마저 처리한 뒤 멈춘다.
 */
@Component
@ConditionalOnProperty(prefix = "nova.sqs.consumer", name = "enabled", havingValue = "true")
class PreorderEventConsumer implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(PreorderEventConsumer.class);
    /** 받기가 연달아 실패하면(큐 장애) 이만큼에서 시작해 두 배씩, 상한까지 쉰다 — 장애 동안 로그 · 요청이 쏟아지지 않게. */
    private static final Duration RECEIVE_FAILURE_PAUSE = Duration.ofSeconds(1);
    private static final Duration RECEIVE_FAILURE_PAUSE_MAX = Duration.ofSeconds(30);
    /** 롱 폴링은 대기 시간만큼 걸리므로 클라이언트 기본 제한 시간 대신 대기 시간에 여유를 더해 쓴다. */
    private static final Duration RECEIVE_TIMEOUT_MARGIN = Duration.ofSeconds(5);

    private final SqsClient sqs;
    private final SqsQueueUrls queueUrls;
    private final PreorderEventDispatcher dispatcher;
    private final SqsProperties.Consumer settings;
    private final Duration receiveTimeout;
    private final List<Thread> workers = new ArrayList<>();
    private volatile boolean running;

    PreorderEventConsumer(SqsClient sqs, SqsQueueUrls queueUrls, PreorderEventDispatcher dispatcher,
                          SqsProperties properties) {
        this.sqs = sqs;
        this.queueUrls = queueUrls;
        this.dispatcher = dispatcher;
        this.settings = properties.consumer();
        this.receiveTimeout = Duration.ofSeconds(settings.waitSeconds()).plus(RECEIVE_TIMEOUT_MARGIN);
    }

    @Override
    public void start() {
        running = true;
        for (int i = 0; i < settings.concurrency(); i++) {
            workers.add(Thread.ofVirtual().name("preorder-events-" + i).start(this::poll));
        }
    }

    /** 받는 중인 롱 폴링(최대 waitSeconds)과 받은 묶음의 처리가 끝나기를 기다린다. */
    @Override
    public void stop() {
        running = false;
        for (Thread worker : workers) {
            try {
                worker.join(Duration.ofSeconds(settings.waitSeconds() + 10L));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        workers.clear();
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    private void poll() {
        int failures = 0;
        while (running) {
            try {
                String queueUrl = queueUrls.of(settings.queue());
                List<Message> messages = sqs.receiveMessage(request -> request
                        .queueUrl(queueUrl)
                        .waitTimeSeconds(settings.waitSeconds())
                        .maxNumberOfMessages(settings.maxMessages())
                        .visibilityTimeout((int) settings.visibility().toSeconds())
                        .messageSystemAttributeNames(MessageSystemAttributeName.APPROXIMATE_RECEIVE_COUNT)
                        .overrideConfiguration(config -> config.apiCallTimeout(receiveTimeout)))
                        .messages();
                failures = 0;
                messages.forEach(message -> handle(queueUrl, message));
            } catch (RuntimeException e) {
                failures++;
                Duration pause = RetryBackoff.of(failures, RECEIVE_FAILURE_PAUSE, RECEIVE_FAILURE_PAUSE_MAX);
                log.warn("이벤트 큐를 받지 못했다 — {} 뒤 다시 받는다 failures={}", pause, failures, e);
                if (!pause(pause)) {
                    return;
                }
            }
        }
    }

    /** 메시지마다 따로 끝낸다 — 한 건의 삭제 · 가시성 변경이 실패해도 같은 묶음의 나머지는 처리한다. */
    private void handle(String queueUrl, Message message) {
        try {
            dispatcher.dispatch(message.body());
        } catch (RuntimeException e) {
            retryLater(queueUrl, message, e);
            return;
        }
        try {
            sqs.deleteMessage(request -> request.queueUrl(queueUrl).receiptHandle(message.receiptHandle()));
        } catch (RuntimeException e) {
            log.warn("처리한 이벤트를 지우지 못했다 — 다시 받으면 멱등하게 한 번 더 처리된다 messageId={}",
                    message.messageId(), e);
        }
    }

    private void retryLater(String queueUrl, Message message, RuntimeException cause) {
        int receiveCount = Integer.parseInt(
                message.attributes().getOrDefault(MessageSystemAttributeName.APPROXIMATE_RECEIVE_COUNT, "1"));
        Duration delay = RetryBackoff.of(receiveCount, settings.backoffBase(), settings.backoffMax());
        log.warn("이벤트 처리 실패 — {} 뒤 다시 받는다 messageId={} receiveCount={}",
                delay, message.messageId(), receiveCount, cause);
        try {
            sqs.changeMessageVisibility(request -> request.queueUrl(queueUrl)
                    .receiptHandle(message.receiptHandle())
                    .visibilityTimeout(seconds(delay)));
        } catch (RuntimeException e) {
            log.warn("다시 받을 시각을 늦추지 못했다 — 가시성 시간이 지나면 다시 보인다 messageId={}",
                    message.messageId(), e);
        }
    }

    /** SQS 는 초 단위다. 1초 미만은 올려 곧바로 다시 보이지 않게 한다. */
    private static int seconds(Duration delay) {
        return (int) Math.max(1, (delay.toMillis() + 999) / 1000);
    }

    /** @return 인터럽트되면 false — 그 뒤의 대기 · 호출이 모두 곧바로 실패하므로 이 작업 스레드를 끝낸다 */
    private static boolean pause(Duration duration) {
        try {
            Thread.sleep(duration);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
