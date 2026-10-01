package com.aircraft.mro.controller;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.aircraft.mro.common.ApiResponse;
import com.aircraft.mro.dto.Views;
import com.aircraft.mro.dto.WbDtos;
import com.aircraft.mro.service.WbService;

import jakarta.validation.Valid;

/**
 * 模块八：飞机称重与重心。
 *
 * <p>规则版本化、称重计算、双人复核基线、构型派生与版本对比全部判定在服务端；
 * 前端只做原始单位/统一单位两套数字的切换展示，两套数字来自同一份计算快照。
 */
@RestController
@RequestMapping("/api/weight-balance")
public class WbController {

    private final WbService wbService;

    public WbController(WbService wbService) {
        this.wbService = wbService;
    }

    /** 机队档案总览（含未建立基线标记） */
    @GetMapping("/overview")
    public ApiResponse<Views.WbOverview> overview() {
        return ApiResponse.success(wbService.overview());
    }

    /** 全机队称重档案：每架飞机显式标注是否已建立基线（无基线不猜默认重心） */
    @GetMapping("/fleet")
    public ApiResponse<List<Views.WbAircraftStatusView>> fleet() {
        return ApiResponse.success(wbService.fleet());
    }

    @GetMapping("/aircraft/{aircraftId}")
    public ApiResponse<Views.WbAircraftStatusView> aircraftStatus(@PathVariable("aircraftId") Long aircraftId) {
        return ApiResponse.success(wbService.aircraftStatus(aircraftId));
    }

    // ---------------- 规则版本 ----------------

    @GetMapping("/rules")
    public ApiResponse<List<Views.WbRuleView>> rules() {
        return ApiResponse.success(wbService.listRules());
    }

    @GetMapping("/rules/{id}")
    public ApiResponse<Views.WbRuleView> rule(@PathVariable("id") Long id) {
        return ApiResponse.success(wbService.getRule(id));
    }

    @PostMapping("/rules")
    public ApiResponse<Views.WbRuleView> createRule(@Valid @RequestBody WbDtos.RuleCreateReq req) {
        return ApiResponse.success("称重规则已保存", wbService.createRule(req));
    }

    /** 在生效版本上修订出新版本（旧版冻结，历史称重继续引用旧版） */
    @PostMapping("/rules/{id}/revise")
    public ApiResponse<Views.WbRuleView> reviseRule(@PathVariable("id") Long id,
                                                     @Valid @RequestBody WbDtos.RuleReviseReq req) {
        return ApiResponse.success("新版规则已建立，旧版已冻结", wbService.revise(id, req));
    }

    @PostMapping("/rules/{id}/draft")
    public ApiResponse<Views.WbRuleView> updateDraft(@PathVariable("id") Long id,
                                                      @Valid @RequestBody WbDtos.RuleDraftUpdateReq req) {
        return ApiResponse.success("草稿规则已更新", wbService.updateDraft(id, req));
    }

    @PostMapping("/rules/{id}/publish")
    public ApiResponse<Views.WbRuleView> publish(@PathVariable("id") Long id,
                                                  @Valid @RequestBody WbDtos.RulePublishReq req) {
        return ApiResponse.success("规则版本已生效", wbService.publish(id, req.getOperator()));
    }

    // ---------------- 称重 ----------------

    /** 不保存的试算：录入时实时预览，与登记共用同一计算逻辑 */
    @PostMapping("/weighings/preview")
    public ApiResponse<Views.WbCalcView> preview(@RequestBody WbDtos.WeighingPreviewReq req) {
        return ApiResponse.success(wbService.preview(req));
    }

    @PostMapping("/weighings")
    public ApiResponse<Views.WbWeighingDetailView> createWeighing(
            @Valid @RequestBody WbDtos.WeighingCreateReq req) {
        return ApiResponse.success("称重已登记（待复核）", wbService.createWeighing(req));
    }

    @GetMapping("/weighings")
    public ApiResponse<List<Views.WbWeighingView>> weighings(
            @RequestParam(value = "aircraftId", required = false) Long aircraftId) {
        return ApiResponse.success(wbService.listWeighings(aircraftId));
    }

    @GetMapping("/weighings/{id}")
    public ApiResponse<Views.WbWeighingDetailView> weighing(@PathVariable("id") Long id) {
        return ApiResponse.success(wbService.getWeighing(id));
    }

    /** 不同人复核确认；setBaseline=true 且包线内则成为当前基线 */
    @PostMapping("/weighings/{id}/confirm")
    public ApiResponse<Views.WbWeighingDetailView> confirm(@PathVariable("id") Long id,
                                                           @Valid @RequestBody WbDtos.WeighingConfirmReq req) {
        return ApiResponse.success("称重已复核确认", wbService.confirm(id, req));
    }

    /** 把已复核且包线内的称重置为当前基线（重置构型链，旧链归档） */
    @PostMapping("/weighings/{id}/baseline")
    public ApiResponse<Views.WbAircraftStatusView> baseline(@PathVariable("id") Long id,
                                                             @RequestBody WbDtos.RulePublishReq req) {
        return ApiResponse.success("已设为当前称重基线", wbService.setBaseline(id, req.getOperator()));
    }

    // ---------------- 构型调整链 ----------------

    /** 只能从当前构型版本继续派生（旧版本分叉、跨机并入都在服务端拒绝） */
    @PostMapping("/aircraft/{aircraftId}/config/derive")
    public ApiResponse<Views.WbConfigVersionView> derive(@PathVariable("aircraftId") Long aircraftId,
                                                         @Valid @RequestBody WbDtos.ConfigDeriveReq req) {
        return ApiResponse.success("已由当前构型版本派生新版本", wbService.derive(aircraftId, req));
    }

    @GetMapping("/config/{id}")
    public ApiResponse<Views.WbConfigVersionView> config(@PathVariable("id") Long id) {
        return ApiResponse.success(wbService.getConfigVersion(id));
    }

    /** 同机构型链上任意两版本对比 */
    @GetMapping("/aircraft/{aircraftId}/config/diff")
    public ApiResponse<Views.WbVersionDiffView> diff(@PathVariable("aircraftId") Long aircraftId,
                                                     @RequestParam("fromId") Long fromId,
                                                     @RequestParam("toId") Long toId) {
        return ApiResponse.success(wbService.compare(aircraftId, fromId, toId));
    }

    // ---------------- 审计事件 ----------------

    @GetMapping("/events")
    public ApiResponse<List<Views.WbEventView>> events(
            @RequestParam(value = "aircraftId", required = false) Long aircraftId) {
        return ApiResponse.success(wbService.events(aircraftId));
    }
}
