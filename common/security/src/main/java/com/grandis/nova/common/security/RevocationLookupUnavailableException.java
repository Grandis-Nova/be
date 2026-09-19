package com.grandis.nova.common.security;

import com.grandis.nova.common.BusinessException;
import com.grandis.nova.common.CommonErrorCode;
import com.grandis.nova.common.Retryable;

/**
 * 폐기 여부를 확인해야 하는데 조회가 안 됐고, 이 경로는 D-2 에서 닫기로 한 경로다(재발급). 401 UNAUTHENTICATED, retryable=true.
 * 필터가 닫는 경로에서 내는 401 과 같은 의미다. 필터를 거치지 않는 공개 경로(쿠키로 식별하는 재발급)에서 서비스가 던진다.
 */
public class RevocationLookupUnavailableException extends BusinessException implements Retryable {

    public RevocationLookupUnavailableException() {
        super(CommonErrorCode.UNAUTHENTICATED);
    }
}
