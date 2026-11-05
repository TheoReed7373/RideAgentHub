package com.fancy.rideagenthub.agentbase.rag;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.UpdateByQueryResponse;
import co.elastic.clients.elasticsearch.core.UpdateResponse;
import co.elastic.clients.elasticsearch._types.Conflicts;
import co.elastic.clients.elasticsearch._types.Refresh;
import co.elastic.clients.elasticsearch.core.search.Hit;
import co.elastic.clients.json.JsonData;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fancy.rideagenthub.domain.dto.RagQAAddDTO;
import com.fancy.rideagenthub.domain.dto.RagQADelDTO;
import com.fancy.rideagenthub.domain.dto.RagQAQueryDTO;
import com.fancy.rideagenthub.domain.dto.RagQAUpdateAnswerDTO;
import com.fancy.rideagenthub.domain.dto.RagQAUpdateQuestionDTO;
import com.fancy.rideagenthub.domain.entity.QaDocument;
import com.fancy.rideagenthub.domain.entity.QaElasticMap;
import com.fancy.rideagenthub.domain.entity.QaInfo;
import com.fancy.rideagenthub.domain.response.PageResult;
import com.fancy.rideagenthub.domain.vo.RagQAQueryVO;
import com.fancy.rideagenthub.mapper.QaElasticMapMapper;
import com.fancy.rideagenthub.mapper.QaInfoMapper;
import com.fancy.rideagenthub.util.SnowflakeIdWorker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

@Service
@Slf4j
public class RagService {
    private final EmbeddingModel embeddingModel;
    private final ElasticsearchClient esClient;
    private final QaInfoMapper qaInfoMapper;
    private final QaElasticMapMapper qaElasticMapMapper;

    private final SnowflakeIdWorker snowflakeIdWorker = new SnowflakeIdWorker(1, 1);

    public RagService(@Qualifier("dashscopeEmbeddingModel") EmbeddingModel embeddingModel,
                      ElasticsearchClient elasticsearchClient,
                      QaInfoMapper qaInfoMapper,
                      QaElasticMapMapper qaElasticMapMapper) {
        this.embeddingModel = embeddingModel;
        this.esClient = elasticsearchClient;
        this.qaInfoMapper = qaInfoMapper;
        this.qaElasticMapMapper = qaElasticMapMapper;
    }

    public PageResult<RagQAQueryVO> queryRagQA(RagQAQueryDTO dto) {
        String groupIdStr = dto == null ? null : dto.getGroupId();
        if (groupIdStr != null && !groupIdStr.isBlank()) {
            groupIdStr = groupIdStr.trim();
            Long groupId;
            try {
                groupId = Long.valueOf(groupIdStr);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("groupId格式不合法");
            }

            QaInfo info = qaInfoMapper.selectOne(new LambdaQueryWrapper<QaInfo>()
                    .eq(QaInfo::getGroupId, groupId)
                    .last("limit 1"));
            if (info == null) {
                return PageResult.<RagQAQueryVO>builder()
                        .page(1)
                        .size(0)
                        .total(0L)
                        .records(Collections.emptyList())
                        .build();
            }

            List<QaElasticMap> maps = qaElasticMapMapper.selectList(
                    new LambdaQueryWrapper<QaElasticMap>()
                            .eq(QaElasticMap::getGroupId, groupId)
                            .orderByAsc(QaElasticMap::getId)
            );
            Map<String, String> questionMap = new LinkedHashMap<>();
            if (maps != null) {
                for (QaElasticMap m : maps) {
                    if (m.getElasticId() == null) {
                        continue;
                    }
                    questionMap.put(m.getElasticId().toString(), m.getQuestion());
                }
            }

            RagQAQueryVO vo = new RagQAQueryVO();
            vo.setGroupId(groupIdStr);
            vo.setAnswer(info.getAnswer());
            vo.setQuestionMap(questionMap);

            return PageResult.<RagQAQueryVO>builder()
                    .page(1)
                    .size(1)
                    .total(1L)
                    .records(List.of(vo))
                    .build();
        }

        int p = dto == null || dto.getPage() == null ? 1 : dto.getPage();
        int s = dto == null || dto.getSize() == null ? 10 : dto.getSize();
        if (p <= 0 || s <= 0) {
            throw new IllegalArgumentException("page/size必须为正数");
        }
        int offset = (p - 1) * s;

        LambdaQueryWrapper<QaInfo> qw = new LambdaQueryWrapper<>();
        Long total = qaInfoMapper.selectCount(qw);
        if (total == null || total <= 0) {
            return PageResult.<RagQAQueryVO>builder()
                    .page(p)
                    .size(s)
                    .total(0L)
                    .records(Collections.emptyList())
                    .build();
        }

        List<QaInfo> qaInfos = qaInfoMapper.selectList(qw
                .orderByDesc(QaInfo::getId)
                .last("limit " + offset + "," + s));
        if (qaInfos == null || qaInfos.isEmpty()) {
            return PageResult.<RagQAQueryVO>builder()
                    .page(p)
                    .size(s)
                    .total(total)
                    .records(Collections.emptyList())
                    .build();
        }

        List<Long> groupIds = qaInfos.stream()
                .map(QaInfo::getGroupId)
                .filter(Objects::nonNull)
                .toList();
        Map<Long, List<QaElasticMap>> mapByGroupId = new HashMap<>();
        if (!groupIds.isEmpty()) {
            List<QaElasticMap> maps = qaElasticMapMapper.selectList(
                    new LambdaQueryWrapper<QaElasticMap>()
                            .in(QaElasticMap::getGroupId, groupIds)
                            .orderByAsc(QaElasticMap::getId)
            );
            if (maps != null && !maps.isEmpty()) {
                mapByGroupId = maps.stream()
                        .filter(m -> m.getGroupId() != null)
                        .collect(Collectors.groupingBy(QaElasticMap::getGroupId));
            }
        }

        List<RagQAQueryVO> records = new ArrayList<>(qaInfos.size());
        for (QaInfo info : qaInfos) {
            RagQAQueryVO vo = new RagQAQueryVO();
            vo.setGroupId(info.getGroupId() == null ? null : info.getGroupId().toString());
            vo.setAnswer(info.getAnswer());

            Map<String, String> questionMap = new LinkedHashMap<>();
            List<QaElasticMap> qList = mapByGroupId.getOrDefault(info.getGroupId(), Collections.emptyList());
            for (QaElasticMap m : qList) {
                if (m.getElasticId() == null) {
                    continue;
                }
                questionMap.put(m.getElasticId().toString(), m.getQuestion());
            }
            vo.setQuestionMap(questionMap);
            records.add(vo);
        }

        return PageResult.<RagQAQueryVO>builder()
                .page(p)
                .size(s)
                .total(total)
                .records(records)
                .build();
    }

    @Transactional
    public void addQA(RagQAAddDTO dto) {
        log.info("Adding QA");
        if (dto == null) {
            throw new IllegalArgumentException("dto不能为空");
        }
        if (dto.getQuestions() == null || dto.getQuestions().isEmpty()) {
            throw new IllegalArgumentException("questions不能为空");
        }
        if (dto.getAnswer() == null || dto.getAnswer().isBlank()) {
            throw new IllegalArgumentException("answer不能为空");
        }

        Long groupId = snowflakeIdWorker.nextId();
        String answer = dto.getAnswer().trim();

        List<String> questions = dto.getQuestions().stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .distinct()
                .toList();
        if (questions.isEmpty()) {
            throw new IllegalArgumentException("questions不能为空");
        }
        List<String> insertedEsIds = new ArrayList<>(questions.size());
        try {
            // 先写 ES
            for (String q : questions) {
                long questionId = snowflakeIdWorker.nextId();
                String qid = Long.toString(questionId);

                QaDocument doc = new QaDocument();
                doc.setId(qid);
                doc.setGroupId(groupId.toString());
                doc.setQuestion(q);
                doc.setQuestionVector(vectorToList(embeddingModel.embed(q)));
                doc.setAnswer(answer);

                esClient.index(i -> i.index("qa_knowledge_base").id(qid).document(doc));
                insertedEsIds.add(qid);
            }
            // 再写 MySQL
            qaInfoMapper.insert(QaInfo.builder()
                    .groupId(groupId)
                    .answer(answer)
                    .build());
            for (int idx = 0; idx < insertedEsIds.size(); idx++) {
                String qid = insertedEsIds.get(idx);
                String qText = questions.get(idx);
                qaElasticMapMapper.insert(QaElasticMap.builder()
                        .groupId(groupId)
                        .elasticId(Long.valueOf(qid))
                        .question(qText)
                        .build());
            }
        } catch (Exception ex) {
            // MySQL 回滚，但 ES 需要补偿删除
            for (String id : insertedEsIds) {
                try {
                    esClient.delete(d -> d.index("qa_knowledge_base").id(id));
                } catch (Exception ignore) {
                    // best effort
                }
            }
            throw new RuntimeException("添加问答对失败", ex);
        }
    }

    @Transactional
    public void addQAs(List<RagQAAddDTO> dtos) {
        if (dtos == null || dtos.isEmpty()) {
            return;
        }
        for (RagQAAddDTO dto : dtos) {
            addQA(dto);
        }
    }

    @Transactional
    public void deleteQA(RagQADelDTO dto) {
        if (dto == null) {
            throw new IllegalArgumentException("dto不能为空");
        }
        Set<String> idSet = new HashSet<>();
        Set<Long> affectedGroupIds = new HashSet<>();
        if (dto.getGroupIds() != null && !dto.getGroupIds().isEmpty()) {
            for (String gidStr : dto.getGroupIds()) {
                if (gidStr == null || gidStr.isBlank()) {
                    continue;
                }
                Long gid;
                try {
                    gid = Long.valueOf(gidStr.trim());
                } catch (NumberFormatException e) {
                    continue;
                }
                affectedGroupIds.add(gid);

                List<QaElasticMap> maps = qaElasticMapMapper.selectList(
                        new LambdaQueryWrapper<QaElasticMap>().eq(QaElasticMap::getGroupId, gid));
                if (maps != null) {
                    for (QaElasticMap m : maps) {
                        if (m.getElasticId() != null) {
                            idSet.add(m.getElasticId().toString());
                        }
                    }
                }
            }
        }
        if (dto.getQuestionIds() != null && !dto.getQuestionIds().isEmpty()) {
            for (String qid : dto.getQuestionIds()) {
                if (qid != null && !qid.isBlank()) {
                    idSet.add(qid.trim());
                }
            }
        }
        if (idSet.isEmpty() && affectedGroupIds.isEmpty()) {
            return;
        }
        // 通过 questionIds 反查 groupIds，用于后续清理 qa_info
        Set<Long> qidLongs = idSet.stream()
                .map(s -> {
                    try {
                        return Long.valueOf(s);
                    } catch (Exception e) {
                        return null;
                    }
                })
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        if (!qidLongs.isEmpty()) {
            List<QaElasticMap> maps = qaElasticMapMapper.selectList(
                    new LambdaQueryWrapper<QaElasticMap>().in(QaElasticMap::getElasticId, qidLongs));
            if (maps != null) {
                for (QaElasticMap m : maps) {
                    if (m.getGroupId() != null) {
                        affectedGroupIds.add(m.getGroupId());
                    }
                }
            }
        }
        // 先删 ES
        for (String id : idSet) {
            try {
                esClient.delete(d -> d.index("qa_knowledge_base").id(id));
            } catch (Exception e) {
                // ES 中可能不存在，忽略
            }
        }
        // 再删 MySQL mapping
        if (!qidLongs.isEmpty()) {
            qaElasticMapMapper.delete(new LambdaQueryWrapper<QaElasticMap>()
                    .in(QaElasticMap::getElasticId, qidLongs));
        }
        // 对于受影响的 group：若已无问题映射，则删除 qa_info
        for (Long gid : affectedGroupIds) {
            Long left = qaElasticMapMapper.selectCount(
                    new LambdaQueryWrapper<QaElasticMap>().eq(QaElasticMap::getGroupId, gid));
            if (left == null || left == 0) {
                qaInfoMapper.delete(new LambdaQueryWrapper<QaInfo>().eq(QaInfo::getGroupId, gid));
            }
        }
    }

    private static List<Float> vectorToList(float[] vector) {
        if (vector == null || vector.length == 0) {
            return Collections.emptyList();
        }
        return IntStream.range(0, vector.length)
                .mapToObj(i -> vector[i])
                .collect(Collectors.toList());
    }

    private static final int RRF_K = 60; // 官方默认常数
    private static final int WINDOW_SIZE = 100; // 每个队列取前100名参与融合

    public List<QaDocument> searchAnswers(String userQuery) {
        try {
            // 1. 准备向量
            float[] vectorArray = embeddingModel.embed(userQuery);
            List<Float> queryVector = vectorToList(vectorArray);
            // 2. 并行执行两个查询 (Vector 和 BM25)
            // 使用 CompletableFuture 并行不仅快，而且解耦
            CompletableFuture<List<Hit<QaDocument>>> vectorFuture = CompletableFuture.supplyAsync(() ->
                    searchVectorOnly(queryVector, WINDOW_SIZE)
            );
            CompletableFuture<List<Hit<QaDocument>>> bm25Future = CompletableFuture.supplyAsync(() ->
                    searchBm25Only(userQuery, WINDOW_SIZE)
            );
            // 3. 等待结果返回
            CompletableFuture.allOf(vectorFuture, bm25Future).join();
            List<Hit<QaDocument>> vectorHits = vectorFuture.get();
            List<Hit<QaDocument>> bm25Hits = bm25Future.get();
            // 4. 执行 RRF 融合算法
            Map<String, Double> scoreMap = new HashMap<>(); // DocId -> RRF Score
            Map<String, QaDocument> docMap = new HashMap<>(); // DocId -> Doc Object (为了最后返回)
            // 4.1 处理向量结果
            for (int i = 0; i < vectorHits.size(); i++) {
                Hit<QaDocument> hit = vectorHits.get(i);
                String docId = hit.id();
                // RRF 公式: score = 1 / (k + rank)
                // 注意: i 是 0-based，rank 是 1-based，所以是 i + 1
                double score = 1.0 / (RRF_K + (i + 1));

                scoreMap.merge(docId, score, Double::sum); // 如果已存在则累加
                docMap.putIfAbsent(docId, hit.source());
            }
            // 4.2 处理 BM25 结果
            for (int i = 0; i < bm25Hits.size(); i++) {
                Hit<QaDocument> hit = bm25Hits.get(i);
                String docId = hit.id();
                double score = 1.0 / (RRF_K + (i + 1));

                scoreMap.merge(docId, score, Double::sum);
                docMap.putIfAbsent(docId, hit.source());
            }
            // 5. 排序 & 手动 Collapse (去重)
            // 我们需要把 map 转成 list 进行排序
            List<Map.Entry<String, Double>> sortedDocs = new ArrayList<>(scoreMap.entrySet());
            // 按分数降序排列
            sortedDocs.sort(Map.Entry.<String, Double>comparingByValue().reversed());
            List<QaDocument> finalResults = new ArrayList<>();
            Set<String> seenGroupIds = new HashSet<>();
            for (Map.Entry<String, Double> entry : sortedDocs) {
                QaDocument doc = docMap.get(entry.getKey());
                if (doc == null || doc.getGroupId() == null) {
                    continue;
                }
                // 这里做 Collapse 去重逻辑
                if (!seenGroupIds.contains(doc.getGroupId())) {
                    finalResults.add(doc);
                    seenGroupIds.add(doc.getGroupId());
                }
                // 取够 Top 5 就停
                if (finalResults.size() >= 5) {
                    break;
                }
            }
            return finalResults;
        } catch (Exception e) {
            throw new RuntimeException("RRF Search failed", e);
        }
    }

    // 辅助方法：纯向量查询
    private List<Hit<QaDocument>> searchVectorOnly(List<Float> vector, int size) {
        try {
            return esClient.search(s -> s
                            .index("qa_knowledge_base")
                            .knn(k -> k
                                    .field("questionVector")
                                    .queryVector(vector)
                                    .k(size)
                                    .numCandidates(size * 2) // 稍微大一点
                            )
                            .size(size) // 只要 ID 和 Source，不要去重，去重交给 Java
                            .source(src -> src.filter(f -> f.includes("id", "groupId", "question", "answer"))),
                    QaDocument.class
            ).hits().hits();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    // 辅助方法：纯 BM25 查询
    private List<Hit<QaDocument>> searchBm25Only(String query, int size) {
        try {
            return esClient.search(s -> s
                            .index("qa_knowledge_base")
                            .query(q -> q
                                    .match(m -> m
                                            .field("question")
                                            .query(query)
                                    )
                            )
                            .size(size)
                            .source(src -> src.filter(f -> f.includes("id", "groupId", "question", "answer"))),
                    QaDocument.class
            ).hits().hits();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * 场景一：更新某个 groupId 下所有文档的 answer
     * - ES: update-by-query 批量更新
     * - MySQL: 同步更新 sys_qa_info.answer
     */
    @Transactional
    public void updateAnswer(RagQAUpdateAnswerDTO dto) {
        if (dto == null) {
            throw new IllegalArgumentException("dto不能为空");
        }
        if (dto.getGroupId() == null || dto.getGroupId().isBlank()) {
            throw new IllegalArgumentException("groupId不能为空");
        }
        if (dto.getAnswer() == null || dto.getAnswer().isBlank()) {
            throw new IllegalArgumentException("answer不能为空");
        }
        String groupIdStr = dto.getGroupId().trim();
        Long groupId;
        try {
            groupId = Long.valueOf(groupIdStr);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("groupId格式不合法");
        }
        String answer = dto.getAnswer().trim();

        try {
            UpdateByQueryResponse resp = esClient.updateByQuery(u -> u
                    .index("qa_knowledge_base")
                    .query(q -> q.term(t -> t.field("groupId").value(groupIdStr)))
                    .script(s -> s
                            .lang("painless")
                            .source("ctx._source.answer = params.answer")
                            .params(Map.of("answer", JsonData.of(answer)))
                    )
                    .conflicts(Conflicts.Proceed)
                    .refresh(true)
            );
            if (resp.failures() != null && !resp.failures().isEmpty()) {
                log.warn("ES update-by-query failures, groupId={}, failures={}", groupIdStr, resp.failures().size());
                throw new RuntimeException("ES批量更新answer失败");
            }
        } catch (Exception e) {
            throw new RuntimeException("ES批量更新answer失败", e);
        }

        qaInfoMapper.update(null, new LambdaUpdateWrapper<QaInfo>()
                .set(QaInfo::getAnswer, answer)
                .eq(QaInfo::getGroupId, groupId));
    }

    /**
     * 场景二：更新某个 questionId 的 question（必须同时更新向量）
     * - ES: update 单文档部分更新（question + questionVector）
     * - MySQL: 同步更新 sys_qa_elastic_map.question
     */
    @Transactional
    public void updateQuestion(RagQAUpdateQuestionDTO dto) {
        if (dto == null) {
            throw new IllegalArgumentException("dto不能为空");
        }
        if (dto.getQuestionId() == null || dto.getQuestionId().isBlank()) {
            throw new IllegalArgumentException("questionId不能为空");
        }
        if (dto.getQuestion() == null || dto.getQuestion().isBlank()) {
            throw new IllegalArgumentException("question不能为空");
        }
        String questionIdStr = dto.getQuestionId().trim();
        Long questionId;
        try {
            questionId = Long.valueOf(questionIdStr);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("questionId格式不合法");
        }
        String question = dto.getQuestion().trim();
        List<Float> questionVector = vectorToList(embeddingModel.embed(question));

        try {
            Map<String, Object> partial = new HashMap<>();
            partial.put("question", question);
            partial.put("questionVector", questionVector);
            UpdateResponse<QaDocument> resp = esClient.update(u -> u
                    .index("qa_knowledge_base")
                    .id(questionIdStr)
                    .doc(partial)
                    .refresh(Refresh.True)
            , QaDocument.class);
            if (resp.result() == null) {
                throw new RuntimeException("ES更新question失败");
            }
        } catch (Exception e) {
            throw new RuntimeException("ES更新question失败", e);
        }

        qaElasticMapMapper.update(null, new LambdaUpdateWrapper<QaElasticMap>()
                .set(QaElasticMap::getQuestion, question)
                .eq(QaElasticMap::getElasticId, questionId));
    }
}
