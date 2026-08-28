package com.elma.gohan.application;

/** 同一推荐餐厅收到冲突的重复反馈时抛出。 */
public class FeedbackAlreadyRecordedException extends RuntimeException {
    public FeedbackAlreadyRecordedException(String message) { super(message); }
}
