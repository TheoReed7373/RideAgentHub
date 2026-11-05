package com.fancy.rideagenthub.domain.entity;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.elasticsearch.annotations.Document;
import org.springframework.data.elasticsearch.annotations.Field;
import org.springframework.data.elasticsearch.annotations.FieldType;

import java.util.List;

@Data
@Document(indexName = "qa_knowledge_base")
public class QaDocument {

    @Id
    private String id; // 唯一ID，例如: "q1_v1"

    @Field(type = FieldType.Keyword)
    private String groupId; // 答案组ID，用于去重 (Collapse)，例如: "answer_001"

    @Field(type = FieldType.Text, analyzer = "ik_smart", searchAnalyzer = "ik_smart")
    private String question; // 问题文本，用于 BM25 检索

    // dims 根据你的模型来定，例如 OpenAI text-embedding-3-small 是 1536
    @Field(type = FieldType.Dense_Vector, dims = 1024, index = true, similarity = "cosine")
    private List<Float> questionVector; // 向量数据

    @Field(type = FieldType.Text, index = false)
    private String answer; // 答案内容 (不需要索引，只用于展示)
}
