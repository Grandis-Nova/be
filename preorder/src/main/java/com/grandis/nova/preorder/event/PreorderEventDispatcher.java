package com.grandis.nova.preorder.event;

import com.grandis.nova.preorder.cancel.CampaignCancelService;
import com.grandis.nova.preorder.cancel.ExpiryCancelService;
import com.grandis.nova.preorder.outbox.EventEnvelope;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * 받은 메시지를 이벤트 종류별 처리로 보낸다. 큐 소비기는 본문을 그대로 여기에 넘긴다.
 *
 * 모르는 종류 · 깨진 본문은 예외로 올린다. 소비기는 메시지를 지우지 않고, 재수신 한도를 넘으면 DLQ 로 간다.
 */
@Component
public class PreorderEventDispatcher {

    private final PreorderEventHandler handler;
    private final ExpiryCancelService expiryCancelService;
    private final CampaignCancelService campaignCancelService;
    private final JsonMapper jsonMapper;

    public PreorderEventDispatcher(PreorderEventHandler handler, ExpiryCancelService expiryCancelService,
                                   CampaignCancelService campaignCancelService, JsonMapper jsonMapper) {
        this.handler = handler;
        this.expiryCancelService = expiryCancelService;
        this.campaignCancelService = campaignCancelService;
        this.jsonMapper = jsonMapper;
    }

    /** @throws IllegalArgumentException 받지 않는 이벤트 종류 */
    public void dispatch(String body) {
        EventEnvelope envelope = jsonMapper.readValue(body, EventEnvelope.class);
        switch (InboundEventType.valueOf(envelope.eventType())) {
            case EXTERNAL_JOB_SUCCEEDED -> handler.onExternalJobSucceeded(
                    jsonMapper.treeToValue(envelope.payload(), ExternalJobSucceeded.class));
            case PREORDER_ORDER_SETTLED -> handler.onOrderSettled(
                    jsonMapper.treeToValue(envelope.payload(), PreorderOrderSettled.class));
            case PREORDER_EXPIRY_REQUESTED -> expiryCancelService.expire(
                    jsonMapper.treeToValue(envelope.payload(), PreorderExpiryRequested.class).preorderId());
            case PREORDER_CAMPAIGN_CANCELED -> {
                PreorderCampaignCanceled canceled =
                        jsonMapper.treeToValue(envelope.payload(), PreorderCampaignCanceled.class);
                campaignCancelService.cancel(canceled.productId(), canceled.reason());
            }
        }
    }
}
