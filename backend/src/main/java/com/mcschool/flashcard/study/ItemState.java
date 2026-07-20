package com.mcschool.flashcard.study;

public enum ItemState {
    /** Not yet answered correctly in this session; still in the queue. */
    PENDING,
    /** Answered correctly; removed from the queue. */
    ANSWERED_CORRECT
}
