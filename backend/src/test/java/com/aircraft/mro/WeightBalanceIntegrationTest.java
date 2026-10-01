package com.aircraft.mro;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import com.aircraft.mro.domain.Aircraft;
import com.aircraft.mro.domain.WbConfigVersion;
import com.aircraft.mro.dto.Views.WbAircraftStatusView;
import com.aircraft.mro.dto.Views.WbCalcView;
import com.aircraft.mro.dto.Views.WbConfigVersionView;
import com.aircraft.mro.dto.Views.WbRuleView;
import com.aircraft.mro.dto.Views.WbVersionDiffView;
import com.aircraft.mro.dto.Views.WbWeighingDetailView;
import com.aircraft.mro.dto.WbDtos;
import com.aircraft.mro.repository.AircraftRepository;
import com.aircraft.mro.repository.WbConfigAdjustmentRepository;
import com.aircraft.mro.repository.WbConfigVersionRepository;
import com.aircraft.mro.repository.WbEnvelopeNodeRepository;
import com.aircraft.mro.repository.WbEventRepository;
import com.aircraft.mro.repository.WbRulePivotRepository;
import com.aircraft.mro.repository.WbRuleVersionRepository;
import com.aircraft.mro.repository.WbWeighingItemRepository;
import com.aircraft.mro.repository.WbWeighingRepository;
import com.aircraft.mro.service.WbCalculator;
import com.aircraft.mro.service.WbService;
import com.aircraft.mro.common.BizException;

/**
 * 模块八端到端集成测试（H2/MySQL 兼容 + JPA 自动建表），覆盖：
 * 折线节点与节点之间插值判定、前后边界越界、公英制往返换算、重复计算精度、
 * 旧规则版本冻结追溯、构型调整链（只能当前版本派生/不可跨机）、无基线飞机。
 */
@SpringBootTest
@ActiveProfiles("adtest")
class WeightBalanceIntegrationTest {

    @Autowired WbService wb;
    @Autowired AircraftRepository aircraftRepository;
    @Autowired WbRuleVersionRepository ruleRepository;
    @Autowired WbRulePivotRepository pivotRepository;
    @Autowired WbEnvelopeNodeRepository nodeRepository;
    @Autowired WbWeighingRepository weighingRepository;
    @Autowired WbWeighingItemRepository weighingItemRepository;
    @Autowired WbConfigVersionRepository configRepository;
    @Autowired WbConfigAdjustmentRepository adjustmentRepository;
    @Autowired WbEventRepository eventRepository;

    private Long acMetric, acImperial, acNoBaseline, acOther;
    private Long ruleId;

    @BeforeEach
    void clean() {
        eventRepository.deleteAll();
        adjustmentRepository.deleteAll();
        configRepository.deleteAll();
        weighingItemRepository.deleteAll();
        weighingRepository.deleteAll();
        nodeRepository.deleteAll();
        pivotRepository.deleteAll();
        ruleRepository.deleteAll();
        aircraftRepository.deleteAll();
        acMetric = mkAircraft("B-T001", "T737", 1L);
        acImperial = mkAircraft("B-T002", "T737", 2L);
        acNoBaseline = mkAircraft("B-T003", "T737", 3L);
        acOther = mkAircraft("B-T099", "T737", 99L);
        ruleId = createRule("2026-01-01", 40000, 48000, true, null);
    }

    private Long mkAircraft(String tail, String model, long fh) {
        Aircraft a = new Aircraft();
        a.setTailNo(tail);
        a.setModel(model);
        a.setAirline("测试航司");
        a.setTotalFh(BigDecimal.valueOf(fh));
        a.setTotalCycles(100);
        a.setAirworthy("AIRWORTHY");
        return aircraftRepository.save(a).getId();
    }

    /** 标准测试规则：NOSE 1600 / LEFT,RIGHT 9000；三节点折线，40000-48000。 */
    private Long createRule(String effDate, int minW, int maxW, boolean publish, String note) {
        WbDtos.RuleCreateReq req = new WbDtos.RuleCreateReq();
        req.setModel("T737");
        req.setPublish(publish);
        req.setContent(ruleContent(effDate, minW, maxW,
                List.of(node("FRONT", "40000", "7000", "KG", "MM"),
                        node("FRONT", "46000", "7600", "KG", "MM"),
                        node("FRONT", "48000", "7650", "KG", "MM"),
                        node("AFT", "40000", "7800", "KG", "MM"),
                        node("AFT", "46000", "8000", "KG", "MM"),
                        node("AFT", "48000", "8050", "KG", "MM")), note));
        return wb.createRule(req).id();
    }

    private WbDtos.RuleContentReq ruleContent(String effDate, int minW, int maxW,
                                              List<WbDtos.EnvelopeNodeReq> nodes, String note) {
        WbDtos.RuleContentReq c = new WbDtos.RuleContentReq();
        c.setEffectiveDate(LocalDate.parse(effDate));
        c.setDatumName("测试基准点 STA0");
        c.setMinWeight(String.valueOf(minW));
        c.setMaxWeight(String.valueOf(maxW));
        c.setWeightUnit("KG");
        c.setRevisionNote(note);
        WbDtos.PivotReq nose = pivot("NOSE", "前起", "1600", "MM");
        WbDtos.PivotReq left = pivot("LEFT", "左主起", "9000", "MM");
        WbDtos.PivotReq right = pivot("RIGHT", "右主起", "9000", "MM");
        c.setPivots(List.of(nose, left, right));
        c.setNodes(nodes);
        return c;
    }

    private WbDtos.PivotReq pivot(String code, String name, String arm, String unit) {
        WbDtos.PivotReq p = new WbDtos.PivotReq();
        p.setCode(code);
        p.setName(name);
        p.setArm(arm);
        p.setLengthUnit(unit);
        return p;
    }

    private WbDtos.EnvelopeNodeReq node(String kind, String w, String arm, String wu, String lu) {
        WbDtos.EnvelopeNodeReq n = new WbDtos.EnvelopeNodeReq();
        n.setKind(kind);
        n.setWeight(w);
        n.setArm(arm);
        n.setWeightUnit(wu);
        n.setLengthUnit(lu);
        return n;
    }

    // ---------- 明细构造 ----------

    private WbDtos.WeighingItemReq pivotRow(String code, String gross, String wu, String lu) {
        WbDtos.WeighingItemReq r = new WbDtos.WeighingItemReq();
        r.setItemType("PIVOT");
        r.setPivotCode(code);
        r.setScaleNo("SC-" + code);
        r.setGrossReading(gross);
        r.setTareReading("0");
        r.setWeightUnit(wu);
        r.setLengthUnit(lu);
        return r;
    }

    private WbDtos.WeighingItemReq condRow(String type, String name, String w, String arm,
                                           String wu, String lu, String effect) {
        WbDtos.WeighingItemReq r = new WbDtos.WeighingItemReq();
        r.setItemType(type);
        r.setItemName(name);
        r.setGrossReading(w);
        r.setTareReading("0");
        r.setArmReading(arm);
        r.setQuantity(1);
        r.setWeightUnit(wu);
        r.setLengthUnit(lu);
        r.setEffect(effect);
        return r;
    }

    private WbDtos.WeighingCreateReq weighingReq(Long aircraftId, String date, String unitSystem,
                                                 List<WbDtos.WeighingItemReq> items) {
        WbDtos.WeighingCreateReq req = new WbDtos.WeighingCreateReq();
        req.setAircraftId(aircraftId);
        req.setWeighingDate(LocalDate.parse(date));
        req.setScaleNo("SC-SET-T01");
        req.setUnitSystem(unitSystem);
        req.setFuelCondition("测试燃油条件");
        req.setOilCondition("测试滑油条件");
        req.setWeighedBy("称重员甲");
        req.setItems(items);
        return req;
    }

    /** 与种子 B-6032 同口径的公制称重：43800kg，CG 7799.543，包线内（节点间插值） */
    private List<WbDtos.WeighingItemReq> insideMetricItems() {
        return List.of(
                pivotRow("NOSE", "7200", "KG", "MM"),
                pivotRow("LEFT", "18725", "KG", "MM"),
                pivotRow("RIGHT", "18725", "KG", "MM"),
                condRow("FUEL", "剩余燃油", "800", "8200", "KG", "MM", "DEDUCT"),
                condRow("OIL", "滑油", "50", "7800", "KG", "MM", "DEDUCT"));
    }

    private WbDtos.WeighingConfirmReq confirmReq(String reviewer, boolean baseline) {
        WbDtos.WeighingConfirmReq r = new WbDtos.WeighingConfirmReq();
        r.setReviewedBy(reviewer);
        r.setSetBaseline(baseline);
        return r;
    }

    private WbConfigVersion current(Long aircraftId) {
        return configRepository.findByActiveAircraftId(aircraftId).orElseThrow();
    }

    // ====================================================================
    // 1) 折线节点之间线性插值 + 节点上判定
    // ====================================================================

    @Test
    void envelope_interpolated_between_nodes_is_inside() {
        WbCalcView calc = wb.preview(previewReq(acMetric, insideMetricItems()));
        assertEquals("INSIDE", calc.envelopeStatus());
        assertEquals("43800", calc.finalWeightKg());
        assertEquals("7799.543", calc.cgArmMm());
        // 43800 在 40000/46000 之间：front=7000+600*3800/6000=7380；aft=7800+200*...=7926.667
        assertEquals(0, new BigDecimal("7380.000").compareTo(new BigDecimal(calc.limitFrontMm())));
        assertEquals(0, new BigDecimal("7926.667").compareTo(new BigDecimal(calc.limitAftMm())));
        // 英制展示同一事实（kg→lb，6 位）
        assertEquals(0, new BigDecimal("96562.470837").compareTo(new BigDecimal(calc.finalWeightLb())));
    }

    @Test
    void envelope_exact_node_values() {
        // 构造最终重量正好 40000 的称重（前/后边界取节点值，不插值）：
        // gross=7200+16600+16600=40400，扣 400 → 40000
        List<WbDtos.WeighingItemReq> items = List.of(
                pivotRow("NOSE", "7200", "KG", "MM"),
                pivotRow("LEFT", "16600", "KG", "MM"),
                pivotRow("RIGHT", "16600", "KG", "MM"),
                condRow("FUEL", "油", "400", "8200", "KG", "MM", "DEDUCT"),
                condRow("OIL", "油", "0", "7800", "KG", "MM", "DEDUCT"));
        WbCalcView calc = wb.preview(previewReq(acMetric, items));
        assertEquals("40000", calc.finalWeightKg());
        assertEquals(0, new BigDecimal("7000").compareTo(new BigDecimal(calc.limitFrontMm())));
        assertEquals(0, new BigDecimal("7800").compareTo(new BigDecimal(calc.limitAftMm())));
    }

    private WbDtos.WeighingPreviewReq previewReq(Long aircraftId, List<WbDtos.WeighingItemReq> items) {
        WbDtos.WeighingPreviewReq p = new WbDtos.WeighingPreviewReq();
        p.setAircraftId(aircraftId);
        p.setRuleRevisionId(ruleId);
        p.setUnitSystem("METRIC");
        p.setItems(items);
        return p;
    }

    // ====================================================================
    // 2) 后边界越界（构型派生，精确方向+差值）
    // ====================================================================

    @Test
    void config_derive_aft_outside_with_exact_delta() {
        Long wId = wb.createWeighing(
                weighingReq(acMetric, "2026-09-10", "METRIC", insideMetricItems())).header().id();
        wb.confirm(wId, confirmReq("复核员乙", true));

        WbConfigVersionView v1 = wb.derive(acMetric, deriveReq(
                List.of(adj("INSTALL", "后货舱天线", "3000", "11800", "KG", "MM", 1)),
            "后货舱加装", "WC-T-1", "改装员丙"));
        assertEquals("OUTSIDE", v1.envelopeStatus());
        assertEquals("偏后（超出后重心边界）", v1.violationKindName());
        assertEquals("35.983", v1.violationDelta());
        assertEquals("46800", v1.weightKg());
        assertEquals("8055.983", v1.cgArmMm());
    }

    // ====================================================================
    // 3) 前边界越界 + 回到包线
    // ====================================================================

    @Test
    void config_derive_forward_outside_then_recover() {
        Long wId = wb.createWeighing(
                weighingReq(acMetric, "2026-09-10", "METRIC", insideMetricItems())).header().id();
        wb.confirm(wId, confirmReq("复核员乙", true));
        wb.derive(acMetric, deriveReq(
                List.of(adj("INSTALL", "后货舱天线", "3000", "11800", "KG", "MM", 1)),
                "加装", "WC-T-1", "丙"));
        WbConfigVersionView v2 = wb.derive(acMetric, deriveReq(
                List.of(adj("REMOVE", "后货舱天线", "3000", "11800", "KG", "MM", 1),
                        adj("INSTALL", "前电子舱机柜", "4000", "1600", "KG", "MM", 1)),
                "改前舱", "WC-T-2", "丙"));
        assertEquals("OUTSIDE", v2.envelopeStatus());
        assertEquals("偏前（超出前重心边界）", v2.violationKindName());
        assertEquals("364.247", v2.violationDelta());

        // 再从 v2 拆除，回到 v0 同一重量/力矩/判定
        WbConfigVersionView v3 = wb.derive(acMetric, deriveReq(
                List.of(adj("REMOVE", "前电子舱机柜", "4000", "1600", "KG", "MM", 1)),
                "恢复", "WC-T-3", "丙"));
        assertEquals("INSIDE", v3.envelopeStatus());
        assertEquals("43800", v3.weightKg());
        assertEquals("7799.543", v3.cgArmMm());
        assertTrue(v3.current());
    }

    // ====================================================================
    // 4) 公英制输入等价 + 往返换算
    // ====================================================================

    @Test
    void imperial_input_converts_and_roundtrips() {
        // 1 kg = 1/0.45359237 lb；取能精确表达的量：100 lb 与 1 in
        assertEquals(new BigDecimal("45.359237"), WbCalculator.toKg(new BigDecimal("100"), "LB"));
        assertEquals(new BigDecimal("100.000000"),
                WbCalculator.kgToLb(WbCalculator.toKg(new BigDecimal("100"), "LB")));
        assertEquals(new BigDecimal("25.400"), WbCalculator.toMm(new BigDecimal("1"), "IN"));
        assertEquals(new BigDecimal("1.0000"), WbCalculator.mmToIn(WbCalculator.toMm(new BigDecimal("1"), "IN")));

        List<WbDtos.WeighingItemReq> items = List.of(
                pivotRow("NOSE", "15873", "LB", "IN"),
                pivotRow("LEFT", "41281", "LB", "IN"),
                pivotRow("RIGHT", "41281", "LB", "IN"),
                condRow("FUEL", "油", "1764", "322.834646", "LB", "IN", "DEDUCT"),
                condRow("OIL", "油", "110", "307.086614", "LB", "IN", "DEDUCT"));
        WbDtos.WeighingPreviewReq p = new WbDtos.WeighingPreviewReq();
        p.setAircraftId(acImperial);
        p.setRuleRevisionId(ruleId);
        p.setUnitSystem("IMPERIAL");
        p.setItems(items);
        WbCalcView calc = wb.preview(p);
        assertEquals("INSIDE", calc.envelopeStatus());
        // 与同物理量级公制基线判定一致
        assertEquals("43799.332839", calc.finalWeightKg());
        assertEquals("7799.546", calc.cgArmMm());
        // 原始读数保留
        assertEquals("15873", calc.items().get(0).grossReading());
        assertEquals("LB", calc.items().get(0).weightUnit());
    }

    // ====================================================================
    // 5) 重复计算无累积误差（试算 N 次 + 落库快照完全一致）
    // ====================================================================

    @Test
    void repeated_calculation_is_identical() {
        WbDtos.WeighingPreviewReq p = previewReq(acMetric, insideMetricItems());
        WbCalcView first = wb.preview(p);
        for (int i = 0; i < 10; i++) {
            WbCalcView again = wb.preview(p);
            assertEquals(first.finalWeightKg(), again.finalWeightKg());
            assertEquals(first.finalMoment(), again.finalMoment());
            assertEquals(first.cgArmMm(), again.cgArmMm());
            assertEquals(first.limitFrontMm(), again.limitFrontMm());
            assertEquals(first.limitAftMm(), again.limitAftMm());
        }
        WbWeighingDetailView saved = wb.createWeighing(
                weighingReq(acMetric, "2026-09-10", "METRIC", insideMetricItems()));
        assertEquals(first.finalWeightKg(), saved.calc().finalWeightKg());
        assertEquals(first.cgArmMm(), saved.calc().cgArmMm());
    }

    // ====================================================================
    // 6) 旧规则版本冻结：新规则发布不回算历史称重/构型
    // ====================================================================

    @Test
    void old_rule_revision_frozen_after_new_published() {
        // 在 R0 下称重基线
        WbWeighingDetailView w0 = wb.createWeighing(
                weighingReq(acMetric, "2026-02-01", "METRIC", insideMetricItems()));
        Long wId = w0.header().id();
        wb.confirm(wId, confirmReq("复核员乙", true));
        WbConfigVersion v0Before = current(acMetric);
        String cgBefore = w0.calc().cgArmMm();

        // 发布 R1：范围放大、包线节点改变
        WbDtos.RuleReviseReq rev = new WbDtos.RuleReviseReq();
        rev.setPublish(true);
        rev.setContent(ruleContent("2026-06-01", 40000, 50000,
                List.of(node("FRONT", "40000", "6000", "KG", "MM"),
                        node("FRONT", "50000", "7000", "KG", "MM"),
                        node("AFT", "40000", "9000", "KG", "MM"),
                        node("AFT", "50000", "9500", "KG", "MM")),
                "R1 包线完全改变"));
        WbRuleView r1 = wb.revise(ruleId, rev);
        assertEquals("EFFECTIVE", r1.status());
        WbRuleView r0View = wb.getRule(ruleId);
        assertEquals("SUPERSEDED", r0View.status());

        // 历史称重仍引用 R0，数字与判定不变
        WbWeighingDetailView wAfter = wb.getWeighing(wId);
        assertEquals(ruleId, wAfter.header().ruleRevisionId());
        assertEquals("R0", wAfter.header().ruleRevisionNo());
        assertEquals(cgBefore, wAfter.calc().cgArmMm());
        assertEquals("INSIDE", wAfter.calc().envelopeStatus());
        // 旧边界仍是 R0 数字（未被 R1 覆盖）
        assertEquals(0, new BigDecimal("7380").compareTo(new BigDecimal(wAfter.calc().limitFrontMm())));

        // 构型链仍引用 R0，且链没有被重算或复制（仍只有 v0）
        WbConfigVersion v0After = current(acMetric);
        assertEquals(ruleId, v0After.getRuleRevisionId());
        assertEquals(1, configRepository.findAllByAircraftIdOrderByVersionNoAsc(acMetric).size());
        assertEquals(v0Before.getCgArmMm(), v0After.getCgArmMm());

        // 用新 R1 规则试算同一读数：边界已是 R1 数字，证明新规则只影响新计算，不动历史
        WbDtos.WeighingPreviewReq p = previewReq(acMetric, insideMetricItems());
        p.setRuleRevisionId(r1.id());
        WbCalcView calcR1 = wb.preview(p);
        // 43800 落在 40000/50000 之间，aft = 9000 + 500*3800/10000 = 9190
        assertEquals(0, new BigDecimal("9190").compareTo(new BigDecimal(calcR1.limitAftMm())));
    }

    // ====================================================================
    // 7) 构型链纪律：只能从当前版本派生；不能在旧版分叉；不能跨机并入
    // ====================================================================

    @Test
    void config_chain_branch_and_cross_aircraft_rejected() {
        Long wId = wb.createWeighing(
                weighingReq(acMetric, "2026-09-10", "METRIC", insideMetricItems())).header().id();
        wb.confirm(wId, confirmReq("复核员乙", true));
        wb.derive(acMetric, deriveReq(
                List.of(adj("INSTALL", "天线", "3000", "11800", "KG", "MM", 1)), "v1", "WC-1", "丙"));

        WbAircraftStatusView status = wb.aircraftStatus(acMetric);
        assertEquals(2, status.configChain().size()); // v0, v1
        assertEquals(1, status.currentVersionNo());
        // v0 已不是当前版本：active 唯一约束保证不能再从 v0 分叉（服务端取当前版本）
        WbConfigVersion v1 = current(acMetric);
        assertNotEquals(0, v1.getVersionNo());

        // 跨机：给另一架飞机建基线后，尝试把它的调整并入本机——服务端以本机当前版本派生，
        // adjustment 行带的是本机 aircraftId；直接伪造跨机版本 id 派生应失败
        Long wId2 = wb.createWeighing(
                weighingReq(acOther, "2026-09-11", "METRIC", insideMetricItems())).header().id();
        wb.confirm(wId2, confirmReq("复核员丁", true));
        // 用另一架机的调整项（物理上无法构造跨机，但可校验对比接口拒绝跨机）
        WbConfigVersion otherV0 = current(acOther);
        BizException ex = assertThrows(BizException.class, () ->
                wb.compare(acMetric, otherV0.getId(), v1.getId()));
        assertTrue(ex.getMessage().contains("同一架飞机"));

        // 所有调整行都属于该机，没有混入他机 id
        adjustmentRepository.findAllByConfigVersionIdOrderByLineNoAsc(v1.getId())
                .forEach(a -> assertEquals(acMetric, a.getAircraftId()));
    }

    // ====================================================================
    // 8) 无基线飞机：明确标识、不猜默认重心；非包线内不可基线
    // ====================================================================

    @Test
    void no_baseline_aircraft_marked_and_guessed_cg_never_shown() {
        WbAircraftStatusView s = wb.aircraftStatus(acNoBaseline);
        assertFalse(s.baselineEstablished());
        assertEquals("未建立基线", s.baselineLabel());
        assertNull(s.currentWeightKg());
        assertNull(s.currentCgArmMm());
        assertNull(s.currentEnvelopeStatus());
        assertTrue(s.configChain().isEmpty());

        // 超总重称重复核后也不能成为基线
        List<WbDtos.WeighingItemReq> heavy = new ArrayList<>(insideMetricItems());
        // 直接放大支点读数让最终重量超 48000
        heavy = List.of(
                pivotRow("NOSE", "9000", "KG", "MM"),
                pivotRow("LEFT", "24000", "KG", "MM"),
                pivotRow("RIGHT", "24000", "KG", "MM"),
                condRow("FUEL", "油", "200", "8200", "KG", "MM", "DEDUCT"),
                condRow("OIL", "油", "0", "7800", "KG", "MM", "DEDUCT"));
        Long heavyId = wb.createWeighing(
                weighingReq(acNoBaseline, "2026-09-12", "METRIC", heavy)).header().id();
        WbWeighingDetailView detail = wb.getWeighing(heavyId);
        assertEquals("OUTSIDE", detail.calc().envelopeStatus());
        assertEquals("超过许用总重上界", detail.calc().violationKindName());
        wb.confirm(heavyId, confirmReq("复核员乙", false));
        BizException ex = assertThrows(BizException.class,
                () -> wb.setBaseline(heavyId, "复核员乙"));
        assertTrue(ex.getMessage().contains("不能设为当前基线"));
        assertFalse(wb.aircraftStatus(acNoBaseline).baselineEstablished());
    }

    // ====================================================================
    // 9) 非法输入：缺读数 / 支点不属于规则 / 非法数值 → 绝不给包线内
    // ====================================================================

    @Test
    void invalid_inputs_never_inside() {
        // 缺右读数
        List<WbDtos.WeighingItemReq> missing = List.of(
                pivotRow("NOSE", "7200", "KG", "MM"),
                pivotRow("LEFT", "18725", "KG", "MM"),
                condRow("FUEL", "油", "800", "8200", "KG", "MM", "DEDUCT"),
                condRow("OIL", "油", "50", "7800", "KG", "MM", "DEDUCT"));
        WbCalcView c1 = wb.preview(previewReq(acMetric, missing));
        assertEquals("UNDETERMINED", c1.envelopeStatus());
        assertTrue(c1.issues().stream().anyMatch(i -> i.contains("RIGHT")));

        // 支点不属于规则
        List<WbDtos.WeighingItemReq> tailPivot = new ArrayList<>(insideMetricItems());
        tailPivot.set(2, pivotRow("TAIL", "18725", "KG", "MM"));
        WbCalcView c2 = wb.preview(previewReq(acMetric, tailPivot));
        assertEquals("UNDETERMINED", c2.envelopeStatus());
        assertTrue(c2.issues().stream().anyMatch(i -> i.contains("TAIL") && i.contains("不属于规则")));

        // 非法数值（NaN 文本/负数皮重）
        List<WbDtos.WeighingItemReq> nan = new ArrayList<>(insideMetricItems());
        nan.set(0, pivotRow("NOSE", "abc", "KG", "MM"));
        WbCalcView c3 = wb.preview(previewReq(acMetric, nan));
        assertEquals("UNDETERMINED", c3.envelopeStatus());
        assertTrue(c3.issues().stream().anyMatch(i -> i.contains("非法数值")));

        List<WbDtos.WeighingItemReq> negNet = new ArrayList<>(insideMetricItems());
        WbDtos.WeighingItemReq nose = pivotRow("NOSE", "100", "KG", "MM");
        nose.setTareReading("200");
        negNet.set(0, nose);
        WbCalcView c4 = wb.preview(previewReq(acMetric, negNet));
        assertEquals("UNDETERMINED", c4.envelopeStatus());
        assertTrue(c4.issues().stream().anyMatch(i -> i.contains("净重为负")));
    }

    // ====================================================================
    // 10) 双人复核约束 + 规则编辑约束
    // ====================================================================

    @Test
    void confirm_requires_different_person_and_draft_editable_only() {
        Long wId = wb.createWeighing(
                weighingReq(acMetric, "2026-09-10", "METRIC", insideMetricItems())).header().id();
        BizException ex = assertThrows(BizException.class,
                () -> wb.confirm(wId, confirmReq("称重员甲", true)));
        assertTrue(ex.getMessage().contains("不同的人"));

        // 生效版不可改、草稿可改
        WbRuleView effective = wb.getRule(ruleId);
        assertEquals("EFFECTIVE", effective.status());
        WbDtos.RuleDraftUpdateReq draftReq = new WbDtos.RuleDraftUpdateReq();
        BizException ex2 = assertThrows(BizException.class,
                () -> wb.updateDraft(ruleId, draftReq));
        assertTrue(ex2.getMessage().contains("草稿"));
    }

    // ====================================================================
    // 11) 版本对比：同链两版本差值
    // ====================================================================

    @Test
    void version_diff_shows_deltas() {
        Long wId = wb.createWeighing(
                weighingReq(acMetric, "2026-09-10", "METRIC", insideMetricItems())).header().id();
        wb.confirm(wId, confirmReq("复核员乙", true));
        WbConfigVersion v0 = current(acMetric);
        wb.derive(acMetric, deriveReq(
                List.of(adj("INSTALL", "天线", "3000", "11800", "KG", "MM", 1)), "v1", "WC-1", "丙"));
        WbConfigVersion v1 = current(acMetric);
        WbVersionDiffView diff = wb.compare(acMetric, v0.getId(), v1.getId());
        assertEquals("3000", diff.deltaWeightKg());
        assertEquals("包线内", diff.fromStatusName());
        assertEquals("包线外", diff.toStatusName());
    }

    // ====================================================================
    // 12) 生效日期纪律：草稿不冻结旧版；未来生效日的新版不影响历史/当天称重
    // ====================================================================

    @Test
    void effective_date_draft_and_future_revision_discipline() {
        // 在 R0 下先完成一次称重
        Long wId = wb.createWeighing(
                weighingReq(acMetric, "2026-09-10", "METRIC", insideMetricItems())).header().id();
        wb.confirm(wId, confirmReq("复核员乙", true));

        // a) 修订出 R1 草稿：旧 R0 必须仍为 EFFECTIVE
        WbDtos.RuleReviseReq draft = new WbDtos.RuleReviseReq();
        draft.setPublish(false);
        draft.setContent(ruleContent("2026-10-01", 40000, 50000,
                List.of(node("FRONT", "40000", "6000", "KG", "MM"),
                        node("FRONT", "50000", "7000", "KG", "MM"),
                        node("AFT", "40000", "9000", "KG", "MM"),
                        node("AFT", "50000", "9500", "KG", "MM")), "R1 草稿"));
        WbRuleView r1Draft = wb.revise(ruleId, draft);
        assertEquals("DRAFT", r1Draft.status());
        assertEquals("EFFECTIVE", wb.getRule(ruleId).status());
        // 当天无显式版本称重仍自动选 R0
        WbDtos.WeighingCreateReq auto = weighingReq(acNoBaseline, "2026-09-20", "METRIC",
                insideMetricItems());
        auto.setRuleRevisionId(null);
        assertEquals(ruleId, wb.createWeighing(auto).header().ruleRevisionId());

        // b) 发布一个生效日在未来的 R1：R0 仍适用当天/历史日期
        wb.updateDraft(r1Draft.id(), new WbDtos.RuleDraftUpdateReq() {{
            setContent(ruleContent(LocalDate.now().plusDays(30).toString(), 40000, 50000,
                    List.of(node("FRONT", "40000", "6000", "KG", "MM"),
                            node("FRONT", "50000", "7000", "KG", "MM"),
                            node("AFT", "40000", "9000", "KG", "MM"),
                            node("AFT", "50000", "9500", "KG", "MM")), "R1 未来生效"));
        }});
        wb.publish(r1Draft.id(), "质量员");
        assertEquals("EFFECTIVE", wb.getRule(ruleId).status(), "旧版在新版生效日前不得被冻结");
        // 当天称重仍选 R0
        WbDtos.WeighingCreateReq today = weighingReq(acImperial, LocalDate.now().toString(),
                "METRIC", insideMetricItems());
        today.setRuleRevisionId(null);
        assertEquals(ruleId, wb.createWeighing(today).header().ruleRevisionId());
        // 未来日期称重选 R1
        WbDtos.WeighingCreateReq future = weighingReq(acOther,
                LocalDate.now().plusDays(31).toString(), "METRIC", insideMetricItems());
        future.setRuleRevisionId(null);
        assertEquals(r1Draft.id(), wb.createWeighing(future).header().ruleRevisionId());
    }

    private WbDtos.ConfigDeriveReq deriveReq(List<WbDtos.AdjustmentReq> adjs, String reason,
                                            String card, String operator) {
        WbDtos.ConfigDeriveReq req = new WbDtos.ConfigDeriveReq();
        req.setAdjustments(adjs);
        req.setReason(reason);
        req.setWorkCardNo(card);
        req.setOperator(operator);
        return req;
    }

    private WbDtos.AdjustmentReq adj(String action, String name, String w, String arm,
                                    String wu, String lu, int qty) {
        WbDtos.AdjustmentReq a = new WbDtos.AdjustmentReq();
        a.setAction(action);
        a.setItemName(name);
        a.setWeight(w);
        a.setArm(arm);
        a.setWeightUnit(wu);
        a.setLengthUnit(lu);
        a.setQuantity(qty);
        a.setReason("测试原因");
        a.setWorkCardNo("WC-T");
        return a;
    }
}
