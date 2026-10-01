package com.aircraft.mro;

import java.math.BigDecimal;
import java.util.List;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.aircraft.mro.domain.Aircraft;
import com.aircraft.mro.dto.WbDtos;
import com.aircraft.mro.repository.AircraftRepository;
import com.aircraft.mro.service.WbService;

/**
 * 模块八端到端冒烟专用（仅 wbsmoke profile 装配）：
 * 用真实 service 流程（建规则→公制/英制称重→双人复核基线→构型越界派生）造验收数据，
 * 保证 curl 冒烟打到的全部是系统自身算出来的结果，而不是写死的快照。
 */
@Component
@Profile("wbsmoke")
class WeightBalanceSmokeSeeder implements ApplicationRunner {

    private final AircraftRepository aircraftRepository;
    private final WbService wb;

    WeightBalanceSmokeSeeder(AircraftRepository aircraftRepository, WbService wb) {
        this.aircraftRepository = aircraftRepository;
        this.wb = wb;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        Long ac1 = aircraft("B-SMK1", "B737");
        Long ac2 = aircraft("B-SMK2", "B737");
        aircraft("B-SMK3", "A330"); // 无规则无基线
        aircraftRepository.flush();

        // B737 R0 旧版（已冻结）
        WbDtos.RuleCreateReq r0 = rule("2025-01-01", 40000, 46000,
                nodes(46000, "7600", "8000"));
        r0.setPublish(true);
        Long r0Id = wb.createRule(r0).id();

        // 修订 R1 生效（48000）
        WbDtos.RuleReviseReq r1 = new WbDtos.RuleReviseReq();
        r1.setPublish(true);
        r1.setContent(content("2025-06-01", 40000, 48000,
                List.of(n("FRONT", "40000", "7000"), n("FRONT", "46000", "7600"), n("FRONT", "48000", "7650"),
                        n("AFT", "40000", "7800"), n("AFT", "46000", "8000"), n("AFT", "48000", "8050")),
                "R1 放大包线"));
        Long r1Id = wb.revise(r0Id, r1).id();

        // 公制称重 → 复核 → 基线
        Long w1 = wb.createWeighing(weighing(ac1, "2026-09-10", "METRIC", null,
                List.of(pivot("NOSE", "7200", "KG", "MM"), pivot("LEFT", "18725", "KG", "MM"),
                        pivot("RIGHT", "18725", "KG", "MM"),
                        cond("FUEL", "剩余燃油", "800", "8200", "KG", "MM", "DEDUCT"),
                        cond("OIL", "滑油", "50", "7800", "KG", "MM", "DEDUCT")))).header().id();
        wb.confirm(w1, confirm("复核员乙", true));
        // 构型链：v1 偏后
        wb.derive(ac1, derive(List.of(adj("INSTALL", "后货舱天线", "3000", "11800", "KG", "MM")),
                "后货舱加装", "WC-SMK-1", "改装员丙"));

        // 英制称重 → 复核基线
        Long w2 = wb.createWeighing(weighing(ac2, "2026-09-12", "IMPERIAL", r1Id,
                List.of(pivot("NOSE", "15873", "LB", "IN"), pivot("LEFT", "41281", "LB", "IN"),
                        pivot("RIGHT", "41281", "LB", "IN"),
                        cond("FUEL", "剩余燃油", "1764", "322.834646", "LB", "IN", "DEDUCT"),
                        cond("OIL", "滑油", "110", "307.086614", "LB", "IN", "DEDUCT")))).header().id();
        wb.confirm(w2, confirm("复核员丁", true));
    }

    private Long aircraft(String tail, String model) {
        Aircraft a = new Aircraft();
        a.setTailNo(tail);
        a.setModel(model);
        a.setAirline("冒烟航司");
        a.setTotalFh(BigDecimal.TEN);
        a.setTotalCycles(5);
        a.setAirworthy("AIRWORTHY");
        return aircraftRepository.save(a).getId();
    }

    private WbDtos.RuleCreateReq rule(String eff, int minW, int maxW, List<WbDtos.EnvelopeNodeReq> nodes) {
        WbDtos.RuleCreateReq req = new WbDtos.RuleCreateReq();
        req.setModel("B737");
        req.setContent(content(eff, minW, maxW, nodes, "冒烟规则"));
        return req;
    }

    private WbDtos.RuleContentReq content(String eff, int minW, int maxW,
                                          List<WbDtos.EnvelopeNodeReq> nodes, String note) {
        WbDtos.RuleContentReq c = new WbDtos.RuleContentReq();
        c.setEffectiveDate(java.time.LocalDate.parse(eff));
        c.setDatumName("STA0");
        c.setMinWeight(String.valueOf(minW));
        c.setMaxWeight(String.valueOf(maxW));
        c.setWeightUnit("KG");
        c.setRevisionNote(note);
        c.setPivots(List.of(p("NOSE", "前起", "1600"), p("LEFT", "左主起", "9000"), p("RIGHT", "右主起", "9000")));
        c.setNodes(nodes);
        return c;
    }

    private List<WbDtos.EnvelopeNodeReq> nodes(int maxW, String frontArm, String aftArm) {
        return List.of(n("FRONT", "40000", "7000"), n("FRONT", String.valueOf(maxW), frontArm),
                n("AFT", "40000", "7800"), n("AFT", String.valueOf(maxW), aftArm));
    }

    private WbDtos.PivotReq p(String code, String name, String arm) {
        WbDtos.PivotReq p = new WbDtos.PivotReq();
        p.setCode(code);
        p.setName(name);
        p.setArm(arm);
        p.setLengthUnit("MM");
        return p;
    }

    private WbDtos.EnvelopeNodeReq n(String kind, String w, String arm) {
        WbDtos.EnvelopeNodeReq n = new WbDtos.EnvelopeNodeReq();
        n.setKind(kind);
        n.setWeight(w);
        n.setArm(arm);
        n.setWeightUnit("KG");
        n.setLengthUnit("MM");
        return n;
    }

    private WbDtos.WeighingItemReq pivot(String code, String gross, String wu, String lu) {
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

    private WbDtos.WeighingItemReq cond(String type, String name, String w, String arm,
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

    private WbDtos.WeighingCreateReq weighing(Long ac, String date, String units, Long ruleId,
                                              List<WbDtos.WeighingItemReq> items) {
        WbDtos.WeighingCreateReq req = new WbDtos.WeighingCreateReq();
        req.setAircraftId(ac);
        req.setWeighingDate(java.time.LocalDate.parse(date));
        req.setScaleNo("SC-SMK");
        req.setUnitSystem(units);
        req.setRuleRevisionId(ruleId);
        req.setFuelCondition("冒烟燃油");
        req.setOilCondition("冒烟滑油");
        req.setWeighedBy("称重员甲");
        req.setItems(items);
        return req;
    }

    private WbDtos.WeighingConfirmReq confirm(String who, boolean baseline) {
        WbDtos.WeighingConfirmReq r = new WbDtos.WeighingConfirmReq();
        r.setReviewedBy(who);
        r.setSetBaseline(baseline);
        return r;
    }

    private WbDtos.ConfigDeriveReq derive(List<WbDtos.AdjustmentReq> adjs, String reason,
                                         String card, String op) {
        WbDtos.ConfigDeriveReq r = new WbDtos.ConfigDeriveReq();
        r.setAdjustments(adjs);
        r.setReason(reason);
        r.setWorkCardNo(card);
        r.setOperator(op);
        return r;
    }

    private WbDtos.AdjustmentReq adj(String action, String name, String w, String arm,
                                    String wu, String lu) {
        WbDtos.AdjustmentReq a = new WbDtos.AdjustmentReq();
        a.setAction(action);
        a.setItemName(name);
        a.setWeight(w);
        a.setArm(arm);
        a.setWeightUnit(wu);
        a.setLengthUnit(lu);
        a.setQuantity(1);
        a.setReason("冒烟调整");
        return a;
    }
}
