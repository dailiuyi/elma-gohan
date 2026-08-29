package com.elma.gohan.application.shadow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.elma.gohan.infrastructure.persistence.RecommendationDecisionSnapshotEntity;
import com.elma.gohan.infrastructure.persistence.RecommendationDecisionSnapshotRepository;
import java.lang.reflect.Method;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

class SafeRegretShadowSnapshotWriterTest {

    @Test
    void databaseOperationsAreFlushBoundedRequiresNewTransactions() throws Exception {
        Method find = SafeRegretShadowSnapshotWriter.class.getMethod(
                "findExisting", UUID.class, String.class);
        Method save = SafeRegretShadowSnapshotWriter.class.getMethod(
                "save", RecommendationDecisionSnapshotEntity.class);

        Transactional findTx = AnnotatedElementUtils.findMergedAnnotation(
                find, Transactional.class);
        Transactional saveTx = AnnotatedElementUtils.findMergedAnnotation(
                save, Transactional.class);
        assertThat(findTx).isNotNull();
        assertThat(findTx.propagation()).isEqualTo(Propagation.REQUIRES_NEW);
        assertThat(findTx.readOnly()).isTrue();
        assertThat(findTx.timeoutString()).contains("transaction-timeout-seconds");
        assertThat(saveTx).isNotNull();
        assertThat(saveTx.propagation()).isEqualTo(Propagation.REQUIRES_NEW);
        assertThat(saveTx.timeoutString()).contains("transaction-timeout-seconds");
    }

    @Test
    void delegatesToFindAndSaveAndFlush() {
        RecommendationDecisionSnapshotRepository repository =
                mock(RecommendationDecisionSnapshotRepository.class);
        SafeRegretShadowSnapshotWriter writer =
                new SafeRegretShadowSnapshotWriter(repository);
        UUID logId = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");
        RecommendationDecisionSnapshotEntity entity =
                mock(RecommendationDecisionSnapshotEntity.class);
        when(repository.findByRecommendationLogIdAndExperimentKey(logId, "exp"))
                .thenReturn(Optional.of(entity));
        when(repository.saveAndFlush(entity)).thenReturn(entity);

        assertThat(writer.findExisting(logId, "exp")).contains(entity);
        assertThat(writer.save(entity)).isSameAs(entity);
        verify(repository).saveAndFlush(entity);
    }
}
