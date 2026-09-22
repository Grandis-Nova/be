package com.grandis.nova.preorder.preorder;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface PreorderEventRepository extends JpaRepository<PreorderEvent, PreorderEvent.Key> {

    /** 그 상태로 들어간 가장 최근 이력. 취소가 어느 상태에서 시작됐는지 볼 때 쓴다. */
    Optional<PreorderEvent> findFirstByPreorderIdAndToStatusOrderByEventSequenceDesc(Long preorderId,
                                                                                    PreorderStatus toStatus);
}
