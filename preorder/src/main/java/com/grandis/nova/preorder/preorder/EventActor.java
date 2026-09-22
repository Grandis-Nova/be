package com.grandis.nova.preorder.preorder;

/** 이력을 남긴 주체. 관리자는 회원 밖 단일 계정이라 회원 참조를 두지 않는다. */
public enum EventActor {
    USER,
    ADMIN,
    SYSTEM
}
