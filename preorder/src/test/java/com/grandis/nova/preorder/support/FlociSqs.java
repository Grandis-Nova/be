package com.grandis.nova.preorder.support;

import io.floci.testcontainers.FlociContainer;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.MessageSystemAttributeName;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * Floci(로컬 AWS 에뮬레이터) 위의 SQS 로 발행 · 소비를 검증하는 테스트의 기반. 컨테이너와 큐는 JVM 에 하나다.
 * SQS 빈은 설정 조건으로 켜지므로 컨텍스트를 만들기 전에 주소 · 전송 방식을 넣는다.
 */
public abstract class FlociSqs {

    public static final String REGION = "ap-northeast-2";
    public static final List<String> QUEUES =
            List.of("preorder-register", "preorder-cancel", "preorder-events", "order-events", "notification");
    /** 이만큼 받고도 처리하지 못하면 DLQ 로 간다. 테스트가 빨리 끝나도록 작게 둔다. */
    public static final int MAX_RECEIVE_COUNT = 2;

    private static final FlociContainer FLOCI = new FlociContainer("floci/floci:2.1.0");
    protected static final SqsClient SQS;

    static {
        FLOCI.start();
        SQS = SqsClient.builder()
                .region(Region.of(REGION))
                .endpointOverride(URI.create(FLOCI.getEndpoint()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(FLOCI.getAccessKey(), FLOCI.getSecretKey())))
                .httpClientBuilder(UrlConnectionHttpClient.builder())
                .build();
        QUEUES.forEach(FlociSqs::createWithDeadLetterQueue);
    }

    @DynamicPropertySource
    static void sqsProperties(DynamicPropertyRegistry registry) {
        registry.add("nova.sqs.region", () -> REGION);
        registry.add("nova.sqs.endpoint", FLOCI::getEndpoint);
        registry.add("nova.sqs.access-key", FLOCI::getAccessKey);
        registry.add("nova.sqs.secret-key", FLOCI::getSecretKey);
        registry.add("nova.outbox.transport", () -> "sqs");
        registry.add("nova.sqs.consumer.enabled", () -> "true");
        // 기본 호출 제한 시간(3s)보다 길게 — 롱 폴링이 제한 시간에 걸리지 않는지 함께 본다
        registry.add("nova.sqs.consumer.wait-seconds", () -> "5");
        registry.add("nova.sqs.consumer.backoff-base", () -> "1s");
        registry.add("nova.sqs.consumer.backoff-max", () -> "1s");
    }

    protected static String queueUrl(String queue) {
        return SQS.getQueueUrl(request -> request.queueName(queue)).queueUrl();
    }

    protected static void send(String queue, String body) {
        SQS.sendMessage(request -> request.queueUrl(queueUrl(queue)).messageBody(body));
    }

    /** 조건에 맞는 메시지가 올 때까지 받는다. 맞는 것은 지우고, 다른 테스트의 메시지는 그대로 둔다. */
    protected static Optional<Message> receive(String queue, Predicate<Message> match, Duration timeout) {
        String url = queueUrl(queue);
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            for (Message message : SQS.receiveMessage(request -> request.queueUrl(url).waitTimeSeconds(1)
                    .maxNumberOfMessages(10).visibilityTimeout(1)
                    .messageAttributeNames("All")
                    .messageSystemAttributeNames(MessageSystemAttributeName.ALL)).messages()) {
                if (match.test(message)) {
                    SQS.deleteMessage(request -> request.queueUrl(url).receiptHandle(message.receiptHandle()));
                    return Optional.of(message);
                }
            }
        }
        return Optional.empty();
    }

    private static void createWithDeadLetterQueue(String queue) {
        String dlqUrl = SQS.createQueue(request -> request.queueName(queue + "-dlq")).queueUrl();
        String dlqArn = SQS.getQueueAttributes(request -> request.queueUrl(dlqUrl)
                .attributeNames(QueueAttributeName.QUEUE_ARN)).attributes().get(QueueAttributeName.QUEUE_ARN);
        SQS.createQueue(request -> request.queueName(queue).attributes(Map.of(QueueAttributeName.REDRIVE_POLICY,
                "{\"deadLetterTargetArn\":\"%s\",\"maxReceiveCount\":\"%d\"}".formatted(dlqArn, MAX_RECEIVE_COUNT))));
    }
}
