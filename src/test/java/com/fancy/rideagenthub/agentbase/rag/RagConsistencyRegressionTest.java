package com.fancy.rideagenthub.agentbase.rag;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fancy.rideagenthub.domain.dto.*;
import com.fancy.rideagenthub.domain.entity.*;
import com.fancy.rideagenthub.mapper.*;
import com.fancy.rideagenthub.util.SnowflakeIdWorker;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.*;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RagConsistencyRegressionTest {
    private final QaInfoMapper infos = mock(QaInfoMapper.class);
    private final QaElasticMapMapper questions = mock(QaElasticMapMapper.class);
    private final QaSyncJobMapper jobs = mock(QaSyncJobMapper.class);
    private final ElasticsearchClient es = mock(ElasticsearchClient.class);
    private final RagService service = new RagService(mock(EmbeddingModel.class), es, infos, questions);
    @BeforeAll static void metadata() {
        var assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "rag-consistency");
        for (Class<?> type : List.of(QaInfo.class, QaElasticMap.class, QaSyncJob.class)) TableInfoHelper.initTableInfo(assistant, type);
    }
    @BeforeEach void setup() {
        ReflectionTestUtils.setField(service, "syncJobs", jobs);
        ReflectionTestUtils.setField(service, "snowflakeIdWorker", new SnowflakeIdWorker(1, 1));
    }
    @Test void addWritesDatabaseAndDurableJobWithoutCallingElasticsearch() {
        var dto = new RagQAAddDTO(); dto.setQuestions(List.of("q")); dto.setAnswer("answer");
        service.addQA(dto);
        verify(infos).insert(any(QaInfo.class)); verify(questions).insert(any(QaElasticMap.class));
        verify(jobs).enqueue(anyLong()); verifyNoInteractions(es);
    }
    @Test void updateIsStoredAndQueuedEvenWhenElasticsearchIsUnavailable() {
        when(infos.update(isNull(), any())).thenReturn(1);
        var dto = new RagQAUpdateAnswerDTO(); dto.setGroupId("1"); dto.setAnswer("new");
        service.updateAnswer(dto);
        verify(jobs).enqueue(1L); verifyNoInteractions(es);
    }
    @Test void failedOrDeletedDocumentsAreNeverServedFromEsAlone() {
        assertTrue(service.authoritative(List.of(document("10", "1", "q", "ghost"))).isEmpty());
    }
    @Test void answerAlwaysComesFromCurrentDatabaseRecord() {
        when(questions.selectList(any())).thenReturn(List.of(QaElasticMap.builder().elasticId(10L).groupId(1L).question("q").build()));
        when(infos.selectList(any())).thenReturn(List.of(QaInfo.builder().groupId(1L).answer("current").build()));
        assertEquals("current", service.authoritative(List.of(document("10", "1", "q", "stale"))).getFirst().getAnswer());
    }
    @Test void staleQuestionVectorIsRejectedUntilResynchronized() {
        when(questions.selectList(any())).thenReturn(List.of(QaElasticMap.builder().elasticId(10L).groupId(1L).question("new question").build()));
        when(infos.selectList(any())).thenReturn(List.of(QaInfo.builder().groupId(1L).answer("a").build()));
        assertTrue(service.authoritative(List.of(document("10", "1", "old question", "a"))).isEmpty());
    }
    @Test void failedIndexSynchronizationRetainsJob() throws Exception {
        var job = new QaSyncJob(); job.setGroupId(1L); job.setVersion(1L);
        when(jobs.claimBatch()).thenReturn(List.of(job));
        var worker = spy(new QaIndexSynchronizer(jobs, infos, questions, es, mock(EmbeddingModel.class)));
        doThrow(new java.io.IOException("down")).when(worker).synchronizeGroup(1L);
        worker.synchronize();
        verify(jobs, never()).deleteById(anyLong());
        verify(jobs).defer(1L);
    }
    @Test void completedIndexSynchronizationAcknowledgesJob() throws Exception {
        var job = new QaSyncJob(); job.setGroupId(1L); job.setVersion(1L);
        when(jobs.claimBatch()).thenReturn(List.of(job));
        var worker = spy(new QaIndexSynchronizer(jobs, infos, questions, es, mock(EmbeddingModel.class)));
        doNothing().when(worker).synchronizeGroup(1L);
        worker.synchronize();
        verify(jobs).deleteById(1L);
    }
    @Test void embeddingSharesTheOverallSearchDeadline() throws Exception {
        var embedding = mock(EmbeddingModel.class);
        var release = new java.util.concurrent.CountDownLatch(1);
        when(embedding.embed(anyString())).thenAnswer(call -> { release.await(2, java.util.concurrent.TimeUnit.SECONDS); return new float[]{1}; });
        var bounded = new RagService(embedding, es, infos, questions);
        var executor = java.util.concurrent.Executors.newSingleThreadExecutor();
        ReflectionTestUtils.setField(bounded, "executor", executor);
        ReflectionTestUtils.setField(bounded, "searchTimeoutMillis", 50L);
        try {
            assertTimeout(java.time.Duration.ofSeconds(1), () -> assertThrows(RuntimeException.class, () -> bounded.searchAnswers("q")));
            verifyNoInteractions(es);
        } finally { release.countDown(); executor.shutdownNow(); }
    }
    private QaDocument document(String id, String group, String question, String answer) {
        var doc = new QaDocument(); doc.setId(id); doc.setGroupId(group); doc.setQuestion(question); doc.setAnswer(answer); return doc;
    }
}
