package com.grandis.nova.preorder.preorder;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PreorderEventRepository extends JpaRepository<PreorderEvent, PreorderEvent.Key> {

    /** 이력은 시각이 아니라 번호 순이다. */
    List<PreorderEvent> findByPreorderIdOrderByEventSequence(Long preorderId);
}
