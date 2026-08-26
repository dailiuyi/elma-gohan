package com.elma.gohan.infrastructure.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.elma.gohan.application.PoiSearchIncompleteException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class GlobalExceptionHandlerTest {

    @Test
    @DisplayName("召回未完成使用独立稳定错误码，不伪装成附近没有结果")
    void mapsIncompleteRecallToDedicatedErrorCode() {
        var response = new GlobalExceptionHandler().handlePoiSearchIncomplete(
                new PoiSearchIncompleteException("本次尚未完成全部检索"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo("POI_SEARCH_INCOMPLETE");
        assertThat(response.getBody().message()).contains("尚未完成全部检索");
    }
}
