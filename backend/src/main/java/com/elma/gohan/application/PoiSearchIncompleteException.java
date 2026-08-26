package com.elma.gohan.application;

/** 候选召回达到分页上限但未穷尽，不能据此断言附近没有符合条件的餐厅。 */
public class PoiSearchIncompleteException extends RuntimeException {

    public PoiSearchIncompleteException(String message) {
        super(message);
    }
}
