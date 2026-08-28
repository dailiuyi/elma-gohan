package com.elma.gohan.controller.api;

/** 搜索未完整但已有可用候选时的非错误提示。 */
public record SearchNotice(String code, String message) {
}
