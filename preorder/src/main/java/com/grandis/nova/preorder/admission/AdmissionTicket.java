package com.grandis.nova.preorder.admission;

/**
 * 검증을 통과한 입장권.
 *
 * @param id 토큰 문자열 전체의 SHA-256 16진수 소문자 64자. preorders.admission_ticket_id 에 기록해 1회 소비를 UNIQUE 로 막는다
 */
public record AdmissionTicket(String id) {
}
