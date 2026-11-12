package com.fancy.rideagenthub.agentbase.rag;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fancy.rideagenthub.domain.entity.*;
import com.fancy.rideagenthub.mapper.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.util.ArrayList;
import java.util.List;

/** 数据库事务中的持久同步任务；锁定任务行直至 ES 写完，阻止旧任务覆盖新版本。 */
@Service
@Slf4j
public class QaIndexSynchronizer {
    private final QaSyncJobMapper jobs;
    private final QaInfoMapper infos;
    private final QaElasticMapMapper questions;
    private final ElasticsearchClient es;
    private final EmbeddingModel embedding;

    public QaIndexSynchronizer(QaSyncJobMapper jobs, QaInfoMapper infos, QaElasticMapMapper questions,
            ElasticsearchClient es, @Qualifier("dashscopeEmbeddingModel") EmbeddingModel embedding) {
        this.jobs = jobs; this.infos = infos; this.questions = questions; this.es = es; this.embedding = embedding;
    }

    @Scheduled(fixedDelayString = "${rag.sync.interval-ms:5000}")
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void synchronize() {
        List<QaSyncJob> batch = jobs.claimBatch();
        for (QaSyncJob job : batch) {
            try {
                synchronizeGroup(job.getGroupId());
                jobs.deleteById(job.getGroupId());
            } catch (Exception failure) {
                jobs.defer(job.getGroupId());
                log.warn("知识库索引同步失败，保留任务以便重试: groupId={}", job.getGroupId(), failure);
            }
        }
    }

    void synchronizeGroup(Long groupId) throws java.io.IOException {
        if (!es.indices().exists(e -> e.index("qa_knowledge_base")).value()) {
            try {
                es.indices().create(c -> c.index("qa_knowledge_base").mappings(m -> m
                        .properties("groupId", p -> p.keyword(k -> k))
                        .properties("question", p -> p.text(t -> t))
                        .properties("answer", p -> p.text(t -> t.index(false)))
                        .properties("questionVector", p -> p.denseVector(v -> v.dims(1024).index(true)))));
            } catch (co.elastic.clients.elasticsearch._types.ElasticsearchException race) {
                if (!"resource_already_exists_exception".equals(race.error().type())) throw race;
            }
        }
        var deleted = es.deleteByQuery(d -> d.index("qa_knowledge_base")
                .query(q -> q.term(t -> t.field("groupId").value(groupId.toString()))));
        if (deleted.timedOut() || !deleted.failures().isEmpty()) throw new IllegalStateException("旧索引删除未完成");
        QaInfo info = infos.selectOne(new LambdaQueryWrapper<QaInfo>().eq(QaInfo::getGroupId, groupId));
        if (info == null) return;
        for (QaElasticMap question : questions.selectList(new LambdaQueryWrapper<QaElasticMap>()
                .eq(QaElasticMap::getGroupId, groupId))) {
            QaDocument document = new QaDocument();
            document.setId(question.getElasticId().toString());
            document.setGroupId(groupId.toString());
            document.setQuestion(question.getQuestion());
            document.setAnswer(info.getAnswer());
            float[] vector = embedding.embed(question.getQuestion());
            List<Float> values = new ArrayList<>(vector.length);
            for (float value : vector) values.add(value);
            document.setQuestionVector(values);
            es.index(i -> i.index("qa_knowledge_base").id(document.getId()).document(document));
        }
        es.indices().refresh(r -> r.index("qa_knowledge_base"));
    }
}
