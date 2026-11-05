package com.fancy.rideagenthub.controller;

import com.fancy.rideagenthub.agentbase.rag.RagService;
import com.fancy.rideagenthub.annotation.RequirePermission;
import com.fancy.rideagenthub.domain.dto.RagQAAddDTO;
import com.fancy.rideagenthub.domain.dto.RagQADelDTO;
import com.fancy.rideagenthub.domain.dto.RagQAQueryDTO;
import com.fancy.rideagenthub.domain.dto.RagQAUpdateAnswerDTO;
import com.fancy.rideagenthub.domain.dto.RagQAUpdateQuestionDTO;
import com.fancy.rideagenthub.domain.entity.QaDocument;
import com.fancy.rideagenthub.domain.response.PageResult;
import com.fancy.rideagenthub.domain.response.Result;
import com.fancy.rideagenthub.domain.vo.RagQAQueryVO;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/rag")
@RequiredArgsConstructor
public class RagController {

	private final RagService ragService;

	/**
	 * RAG 知识库问答对 分页查询（仅 ADMIN）
	 */
	@RequirePermission({"ADMIN"})
	@PostMapping("/qa/page")
	public Result page(@RequestBody RagQAQueryDTO dto) {
		PageResult<RagQAQueryVO> page = ragService.queryRagQA(dto);
		return Result.ok(page);
	}

	/**
	 * 新增单组问答（questions + answer）（仅 ADMIN）
	 */
	@RequirePermission({"ADMIN"})
	@PostMapping("/qa/add")
	public Result add(@RequestBody RagQAAddDTO dto) {
		ragService.addQA(dto);
		return Result.ok();
	}

	/**
	 * 批量新增问答（仅 ADMIN）
	 */
	@RequirePermission({"ADMIN"})
	@PostMapping("/qa/add-batch")
	public Result addBatch(@RequestBody List<RagQAAddDTO> dtos) {
		ragService.addQAs(dtos);
		return Result.ok();
	}

	/**
	 * 删除问答（按 groupIds 或 questionIds）（仅 ADMIN）
	 */
	@RequirePermission({"ADMIN"})
	@PostMapping("/admin/qa/delete")
	public Result delete(@RequestBody RagQADelDTO dto) {
		ragService.deleteQA(dto);
		return Result.ok();
	}

	/**
	 * 更新某个 groupId 下所有问题的 answer（仅 ADMIN）
	 */
	@RequirePermission({"ADMIN"})
	@PostMapping("/admin/qa/update-answer")
	public Result updateAnswer(@RequestBody RagQAUpdateAnswerDTO dto) {
		ragService.updateAnswer(dto);
		return Result.ok();
	}

	/**
	 * 更新某个 questionId 的 question（必须同步更新向量）（仅 ADMIN）
	 */
	@RequirePermission({"ADMIN"})
	@PostMapping("/admin/qa/update-question")
	public Result updateQuestion(@RequestBody RagQAUpdateQuestionDTO dto) {
		ragService.updateQuestion(dto);
		return Result.ok();
	}

	/**
	 * RAG 知识库问答搜索（仅 ADMIN）
	 */
	@RequirePermission({"ADMIN"})
	@GetMapping("/search")
	public Result search(@RequestParam String question) {
		List<QaDocument> results = ragService.searchAnswers(question);
		return Result.ok(results);
	}
}
