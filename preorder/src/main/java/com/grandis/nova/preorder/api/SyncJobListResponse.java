package com.grandis.nova.preorder.api;

import com.fasterxml.jackson.annotation.JsonUnwrapped;
import com.grandis.nova.common.OffsetPage;
import com.grandis.nova.preorder.syncjob.ErrorGroup;
import com.grandis.nova.preorder.syncjob.SyncJobPage;

import java.util.List;

/** @param errorGroups groupByError 가 아니면 null */
public record SyncJobListResponse(
        @JsonUnwrapped OffsetPage<SyncJobSummaryResponse> page,
        List<ErrorGroup> errorGroups
) {

    public static SyncJobListResponse from(SyncJobPage page) {
        return new SyncJobListResponse(page.page().map(SyncJobSummaryResponse::from), page.errorGroups());
    }
}
