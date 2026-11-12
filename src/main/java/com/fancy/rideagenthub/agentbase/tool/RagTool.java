package com.fancy.rideagenthub.agentbase.tool;

import com.fancy.rideagenthub.agentbase.rag.RagService;
import com.fancy.rideagenthub.domain.entity.QaDocument;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.List;

@Component
@Slf4j
@RequiredArgsConstructor
public class RagTool {
    private final RagService ragService;

    @Tool(description = "知识库搜索引擎。当用户询问关于系统操作、业务规则或常见问题时，必须调用此工具获取准确信息。请勿凭空编造答案。")
    public String searchKnowledgeBase(@ToolParam(description = "重写后的查询语句。必须是脱离上下文也能看懂的完整问题。例如将'怎么退'重写为'网约车订单退款流程'。")
                               String queryStr,
                           ToolContext toolContext){
        log.info("[RagTool]: searchKnowledgeBase(queryStr={})", queryStr);
        ToolNotifySupport.notifyToolListener(toolContext, "正在调用知识库搜索……" + queryStr);
        try {
            List<QaDocument> docs = ragService.searchAnswers(queryStr);
            if (docs.isEmpty()) return "知识库未找到有效的相关信息，请明确告知用户并按需转人工处理。";
            StringBuilder sb = new StringBuilder();
            sb.append("找到以下相关知识库条目：\n");
            for (int i = 0; i < docs.size(); i++) {
                QaDocument doc = docs.get(i);
                sb.append(String.format("--- 条目 %d ---\n", i + 1));
                sb.append("参考问题：").append(doc.getQuestion()).append("\n");
                sb.append("标准回答：").append(doc.getAnswer()).append("\n");
            }
            return sb.toString();
        } catch (RuntimeException e) {
            log.error("Error occurred while rag callback: {}", e.getMessage());
            return "知识库暂时不可用。";
        }
    }

}

