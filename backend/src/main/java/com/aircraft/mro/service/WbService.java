package com.aircraft.mro.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.aircraft.mro.common.BizException;
import com.aircraft.mro.domain.Aircraft;
import com.aircraft.mro.domain.WbConfigAdjustment;
import com.aircraft.mro.domain.WbConfigVersion;
import com.aircraft.mro.domain.WbEnvelopeNode;
import com.aircraft.mro.domain.WbEvent;
import com.aircraft.mro.domain.WbRulePivot;
import com.aircraft.mro.domain.WbRuleVersion;
import com.aircraft.mro.domain.WbWeighing;
import com.aircraft.mro.domain.WbWeighingItem;
import com.aircraft.mro.dto.Views;
import com.aircraft.mro.dto.Views.WbCalcView;
import com.aircraft.mro.dto.Views.WbRuleView;
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
import com.aircraft.mro.service.WbCalculator.CorrectionResult;
import com.aircraft.mro.service.WbCalculator.ItemInput;
import com.aircraft.mro.service.WbCalculator.PivotResult;
import com.aircraft.mro.service.WbCalculator.Result;
import com.aircraft.mro.service.WbCalculator.RuleData;

import static com.aircraft.mro.service.WbCalculator.AFT;
import static com.aircraft.mro.service.WbCalculator.FORWARD;
import static com.aircraft.mro.service.WbCalculator.INSIDE;
import static com.aircraft.mro.service.WbCalculator.OVER_WEIGHT;
import static com.aircraft.mro.service.WbCalculator.UNDER_WEIGHT;

/**
 * 模块八：飞机称重与重心。
 *
 * <p>所有判定都在服务端，用 {@link WbCalculator} 的无状态 Decimal 计算完成：
 * 试算接口与落库共用同一份输入→结果逻辑，结果快照在登记时写死，之后规则再修订、
 * 构型再派生，都不回算覆盖历史称重与历史构型版本。
 */
@Service
public class WbService {

    private final WbRuleVersionRepository ruleRepository;
    private final WbRulePivotRepository pivotRepository;
    private final WbEnvelopeNodeRepository nodeRepository;
    private final WbWeighingRepository weighingRepository;
    private final WbWeighingItemRepository itemRepository;
    private final WbConfigVersionRepository configRepository;
    private final WbConfigAdjustmentRepository adjustmentRepository;
    private final WbEventRepository eventRepository;
    private final AircraftRepository aircraftRepository;

    public WbService(WbRuleVersionRepository ruleRepository,
                     WbRulePivotRepository pivotRepository,
                     WbEnvelopeNodeRepository nodeRepository,
                     WbWeighingRepository weighingRepository,
                     WbWeighingItemRepository itemRepository,
                     WbConfigVersionRepository configRepository,
                     WbConfigAdjustmentRepository adjustmentRepository,
                     WbEventRepository eventRepository,
                     AircraftRepository aircraftRepository) {
        this.ruleRepository = ruleRepository;
        this.pivotRepository = pivotRepository;
        this.nodeRepository = nodeRepository;
        this.weighingRepository = weighingRepository;
        this.itemRepository = itemRepository;
        this.configRepository = configRepository;
        this.adjustmentRepository = adjustmentRepository;
        this.eventRepository = eventRepository;
        this.aircraftRepository = aircraftRepository;
    }

    // ====================================================================
    // 字典名
    // ====================================================================

    public static String ruleStatusName(String s) {
        return switch (s) {
            case "DRAFT" -> "草稿";
            case "EFFECTIVE" -> "生效中";
            case "SUPERSEDED" -> "已被取代（冻结）";
            default -> s;
        };
    }

    public static String envelopeName(String s) {
        return switch (s) {
            case INSIDE -> "包线内";
            case "OUTSIDE" -> "包线外";
            case "UNDETERMINED" -> "无法判定";
            default -> s;
        };
    }

    public static String violationName(String k) {
        if (k == null) {
            return null;
        }
        return switch (k) {
            case FORWARD -> "偏前（超出前重心边界）";
            case AFT -> "偏后（超出后重心边界）";
            case OVER_WEIGHT -> "超过许用总重上界";
            case UNDER_WEIGHT -> "低于许用总重下界";
            default -> k;
        };
    }

    private static String weighingStatusName(String s) {
        return "DRAFT".equals(s) ? "待复核" : "CONFIRMED".equals(s) ? "已复核确认" : s;
    }

    private static String effectName(String e) {
        return "ADD".equals(e) ? "补入" : "DEDUCT".equals(e) ? "扣除" : e;
    }

    private static String itemTypeName(String t) {
        return switch (t) {
            case "PIVOT" -> "支点";
            case "FUEL" -> "燃油";
            case "OIL" -> "滑油";
            case "TEMP" -> "临时项";
            case "CONDITION" -> "条件项";
            default -> t;
        };
    }

    // ====================================================================
    // 规则版本
    // ====================================================================

    @Transactional(readOnly = true)
    public List<WbRuleView> listRules() {
        List<WbRuleVersion> rules = ruleRepository.findAllByOrderByModelAscRevisionNoAsc();
        return rules.stream().map(r -> toRuleView(r, loadNodes(r.getId()), loadPivots(r.getId()))).toList();
    }

    @Transactional(readOnly = true)
    public WbRuleView getRule(Long id) {
        WbRuleVersion rule = mustRule(id);
        return toRuleView(rule, loadNodes(id), loadPivots(id));
    }

    @Transactional
    public WbRuleView createRule(WbDtos.RuleCreateReq req) {
        String model = req.getModel().trim().toUpperCase();
        if (!ruleRepository.findByModelAndRevisionNo(model, "R0").isEmpty()) {
            throw new BizException("机型 " + model + " 已存在 R0 规则，修订请使用「出新版本」");
        }
        LocalDateTime now = LocalDateTime.now();
        WbRuleVersion rule = new WbRuleVersion();
        rule.setModel(model);
        rule.setRevisionNo("R0");
        rule.setStatus(Boolean.TRUE.equals(req.getPublish()) ? "EFFECTIVE" : "DRAFT");
        rule.setCreatedAt(now);
        rule.setPublishedAt("EFFECTIVE".equals(rule.getStatus()) ? now : null);
        applyContent(rule, req.getContent());
        rule = ruleRepository.save(rule);
        saveContentChildren(rule, req.getContent());
        event(null, "RULE_PUBLISH", rule.getId(), null, null, "SYSTEM",
                "机型 " + model + " 称重规则 R0 " + ("EFFECTIVE".equals(rule.getStatus()) ? "生效" : "保存为草稿"));
        return getRule(rule.getId());
    }

    @Transactional
    public WbRuleView revise(Long baseId, WbDtos.RuleReviseReq req) {
        WbRuleVersion base = mustRule(baseId);
        if (!"EFFECTIVE".equals(base.getStatus())) {
            throw new BizException("只能在已生效版本上修订出新版本");
        }
        String nextNo = nextRevisionNo(base.getModel());
        LocalDateTime now = LocalDateTime.now();
        WbRuleVersion rule = new WbRuleVersion();
        rule.setModel(base.getModel());
        rule.setRevisionNo(nextNo);
        boolean publishNow = !Boolean.FALSE.equals(req.getPublish())
                && !req.getContent().getEffectiveDate().isAfter(LocalDate.now());
        if (Boolean.FALSE.equals(req.getPublish())) {
            // 草稿：旧生效版保持 EFFECTIVE（等草稿发布时再冻结），旧称重口径不变
            rule.setStatus("DRAFT");
            rule.setPublishedAt(null);
        } else {
            // 直接发布：新版生效日 <= 今天，旧版立即冻结
            rule.setStatus("EFFECTIVE");
            rule.setPublishedAt(now);
            base.setStatus("SUPERSEDED");
            ruleRepository.save(base);
        }
        rule.setCreatedAt(now);
        applyContent(rule, req.getContent());
        rule = ruleRepository.save(rule);
        saveContentChildren(rule, req.getContent());
        event(null, "RULE_PUBLISH", rule.getId(), null, null, "SYSTEM",
                "机型 " + base.getModel() + " 称重规则 " + nextNo
                        + ("EFFECTIVE".equals(rule.getStatus())
                                ? " 生效，旧版 " + base.getRevisionNo() + " 转冻结；已完成称重继续引用旧版结果"
                                : " 草稿已建（未发布不影响生效版与历史称重）"));
        return getRule(rule.getId());
    }

    @Transactional
    public WbRuleView updateDraft(Long id, WbDtos.RuleDraftUpdateReq req) {
        WbRuleVersion rule = mustRule(id);
        if (!"DRAFT".equals(rule.getStatus())) {
            throw new BizException("只有草稿版本可以修改；生效/冻结版本不可改写，请重新修订");
        }
        applyContent(rule, req.getContent());
        ruleRepository.save(rule);
        pivotRepository.deleteByRuleRevisionId(id);
        nodeRepository.deleteByRuleRevisionId(id);
        saveContentChildren(rule, req.getContent());
        return getRule(id);
    }

    @Transactional
    public WbRuleView publish(Long id, String operator) {
        // 串行化：同机型加锁，防止两版并发生效
        WbRuleVersion draft = mustRule(id);
        List<WbRuleVersion> locked = ruleRepository.findAllByModelForUpdate(draft.getModel());
        if (!"DRAFT".equals(draft.getStatus())) {
            throw new BizException("只有草稿版本可以发布");
        }
        boolean currentNow = !draft.getEffectiveDate().isAfter(LocalDate.now());
        if (currentNow) {
            // 立即生效：冻结此前的生效版；未来日期才生效的新版不动它们（历史日期称重仍取旧版）
            for (WbRuleVersion r : locked) {
                if ("EFFECTIVE".equals(r.getStatus()) && !r.getId().equals(id)) {
                    r.setStatus("SUPERSEDED");
                    ruleRepository.save(r);
                }
            }
        }
        draft.setStatus("EFFECTIVE");
        draft.setPublishedAt(LocalDateTime.now());
        ruleRepository.save(draft);
        event(null, "RULE_PUBLISH", draft.getId(), null, null, operator,
                "机型 " + draft.getModel() + " 称重规则 " + draft.getRevisionNo()
                        + (currentNow ? " 生效；旧版冻结，历史称重结果不变"
                                : " 已发布，生效日期 " + draft.getEffectiveDate()
                                + "；该日期前的称重仍引用旧版"));
        return getRule(id);
    }

    /** 称重日期当天生效的规则版本：取已发布(EFFECTIVE)且生效日 <= 称重日期中最新的一版。
     *  未来日期才生效的新版（状态已 EFFECTIVE）不会被历史/当天称重选中；旧版保留。 */
    @Transactional(readOnly = true)
    public WbRuleVersion effectiveRuleOn(String model, LocalDate date) {
        return ruleRepository.findAllByStatusOrderByEffectiveDateDesc("EFFECTIVE").stream()
                .filter(r -> r.getModel().equals(model) && !r.getEffectiveDate().isAfter(date))
                .max(Comparator.comparing(WbRuleVersion::getEffectiveDate)
                        .thenComparing(WbRuleVersion::getId))
                .orElseThrow(() -> new BizException(
                        "机型 " + model + " 在 " + date + " 没有已发布且到生效日期的称重规则，无法登记该日期的称重"));
    }

    private void applyContent(WbRuleVersion rule, WbDtos.RuleContentReq c) {
        rule.setEffectiveDate(c.getEffectiveDate());
        rule.setDatumName(c.getDatumName().trim());
        String weightUnit = normalizeWeightUnit(c.getWeightUnit());
        BigDecimal min = parseWeight(c.getMinWeight(), weightUnit, "许用总重下界");
        BigDecimal max = parseWeight(c.getMaxWeight(), weightUnit, "许用总重上界");
        if (min.compareTo(max) >= 0) {
            throw new BizException("许用总重下界必须小于上界");
        }
        rule.setMinWeightKg(min);
        rule.setMaxWeightKg(max);
        rule.setRevisionNote(c.getRevisionNote());
    }

    private void saveContentChildren(WbRuleVersion rule, WbDtos.RuleContentReq c) {
        List<String> codes = new ArrayList<>();
        int idx = 0;
        for (WbDtos.PivotReq p : c.getPivots()) {
            idx++;
            String code = p.getCode().trim().toUpperCase();
            if (!codes.add(code)) {
                throw new BizException("支点编号重复：" + code);
            }
            BigDecimal arm = parseArm(p.getArm(), normalizeLengthUnit(p.getLengthUnit()),
                    "支点 " + code + " 臂长");
            WbRulePivot e = new WbRulePivot();
            e.setRuleRevisionId(rule.getId());
            e.setPivotCode(code);
            e.setPivotName(p.getName().trim());
            e.setArmMm(arm);
            pivotRepository.save(e);
        }
        if (idx < 2) {
            throw new BizException("起落架支点至少要配置两个");
        }
        saveNodes(rule, c.getNodes(), "FRONT");
        saveNodes(rule, c.getNodes(), "AFT");
    }

    private void saveNodes(WbRuleVersion rule, List<WbDtos.EnvelopeNodeReq> all, String kind) {
        List<WbDtos.EnvelopeNodeReq> mine = all.stream()
                .filter(n -> kind.equals(n.getKind().trim().toUpperCase()))
                .toList();
        if (mine.size() < 2) {
            throw new BizException((kind.equals("FRONT") ? "前重心" : "后重心")
                    + "包线至少需要 2 个重量节点（折线）");
        }
        BigDecimal prevW = null;
        for (WbDtos.EnvelopeNodeReq n : mine) {
            BigDecimal w = parseWeight(n.getWeight(), normalizeWeightUnit(n.getWeightUnit()),
                    kind + " 包线节点重量");
            if (prevW != null && w.compareTo(prevW) <= 0) {
                throw new BizException((kind.equals("FRONT") ? "前重心" : "后重心")
                        + "包线节点重量必须严格递增");
            }
            prevW = w;
            BigDecimal a = parseArm(n.getArm(), normalizeLengthUnit(n.getLengthUnit()),
                    kind + " 包线节点重心臂");
            WbEnvelopeNode e = new WbEnvelopeNode();
            e.setRuleRevisionId(rule.getId());
            e.setBoundaryKind(kind);
            e.setWeightKg(w);
            e.setArmMm(a);
            nodeRepository.save(e);
        }
    }

    private String nextRevisionNo(String model) {
        int max = 0;
        for (WbRuleVersion r : ruleRepository.findAllByModelOrderByRevisionNoAsc(model)) {
            if (r.getRevisionNo().startsWith("R")) {
                try {
                    max = Math.max(max, Integer.parseInt(r.getRevisionNo().substring(1)));
                } catch (NumberFormatException ignore) {
                    // 非标准编号不参与
                }
            }
        }
        return "R" + (max + 1);
    }

    private WbRuleVersion mustRule(Long id) {
        return ruleRepository.findById(id)
                .orElseThrow(() -> new BizException("称重规则版本不存在：id=" + id));
    }

    private List<WbRulePivot> loadPivots(Long ruleId) {
        return pivotRepository.findAllByRuleRevisionIdOrderByIdAsc(ruleId);
    }

    private List<WbEnvelopeNode> loadNodes(Long ruleId) {
        return nodeRepository.findAllByRuleRevisionIdOrderByBoundaryKindAscWeightKgAsc(ruleId);
    }

    private WbRuleView toRuleView(WbRuleVersion r, List<WbEnvelopeNode> nodes, List<WbRulePivot> pivots) {
        // current = 当天正在适用：已发布且生效日已到（未来才生效的新版不算当天适用）
        boolean current = "EFFECTIVE".equals(r.getStatus())
                && !r.getEffectiveDate().isAfter(LocalDate.now());
        List<Views.WbPivotView> pv = pivots.stream()
                .map(p -> new Views.WbPivotView(p.getPivotCode(), p.getPivotName(), p.getArmMm()))
                .toList();
        List<Views.WbNodeView> front = nodes.stream()
                .filter(n -> "FRONT".equals(n.getBoundaryKind()))
                .map(n -> new Views.WbNodeView("FRONT", n.getWeightKg(), n.getArmMm()))
                .toList();
        List<Views.WbNodeView> aft = nodes.stream()
                .filter(n -> "AFT".equals(n.getBoundaryKind()))
                .map(n -> new Views.WbNodeView("AFT", n.getWeightKg(), n.getArmMm()))
                .toList();
        return new WbRuleView(r.getId(), r.getModel(), r.getRevisionNo(), r.getStatus(),
                ruleStatusName(r.getStatus()), r.getEffectiveDate(), r.getDatumName(),
                r.getMinWeightKg(), r.getMaxWeightKg(), r.getRevisionNote(), pv, front, aft,
                r.getCreatedAt(), r.getPublishedAt(), current);
    }

    // ====================================================================
    // 试算（不落库）
    // ====================================================================

    @Transactional(readOnly = true)
    public WbCalcView preview(WbDtos.WeighingPreviewReq req) {
        String model;
        WbRuleVersion rule;
        if (req.getRuleRevisionId() != null) {
            rule = mustRule(req.getRuleRevisionId());
            model = rule.getModel();
        } else {
            Aircraft ac = aircraftRepository.findById(req.getAircraftId())
                    .orElseThrow(() -> new BizException("飞机不存在"));
            model = ac.getModel();
            rule = effectiveRuleOn(model, LocalDate.now());
        }
        String unitSystem = "IMPERIAL".equalsIgnoreCase(req.getUnitSystem()) ? "IMPERIAL" : "METRIC";
        List<ItemInput> inputs = toInputs(req.getItems(), unitSystem);
        Result result = WbCalculator.weigh(toRuleData(rule, loadPivots(rule.getId()), loadNodes(rule.getId())),
                inputs);
        return toCalcView(result, inputs, null);
    }

    // ====================================================================
    // 称重登记 / 复核 / 基线
    // ====================================================================

    @Transactional
    public Views.WbWeighingDetailView createWeighing(WbDtos.WeighingCreateReq req) {
        Aircraft ac = aircraftRepository.findById(req.getAircraftId())
                .orElseThrow(() -> new BizException("飞机不存在：id=" + req.getAircraftId()));
        if (req.getItems() == null || req.getItems().isEmpty()) {
            throw new BizException("称重明细不能为空");
        }
        WbRuleVersion rule = req.getRuleRevisionId() != null
                ? mustRule(req.getRuleRevisionId())
                : effectiveRuleOn(ac.getModel(), req.getWeighingDate());
        if (!rule.getModel().equals(ac.getModel())) {
            throw new BizException("规则机型 " + rule.getModel() + " 与飞机机型 " + ac.getModel() + " 不一致");
        }
        String unitSystem = "IMPERIAL".equalsIgnoreCase(req.getUnitSystem()) ? "IMPERIAL" : "METRIC";
        List<ItemInput> inputs = toInputs(req.getItems(), unitSystem);

        LocalDateTime now = LocalDateTime.now();
        WbWeighing w = new WbWeighing();
        w.setWeighingNo(generateWeighingNo());
        w.setAircraftId(ac.getId());
        w.setTailNo(ac.getTailNo());
        w.setModel(ac.getModel());
        w.setWeighingDate(req.getWeighingDate());
        w.setScaleNo(req.getScaleNo().trim());
        w.setRuleRevisionId(rule.getId());
        w.setRuleRevisionNo(rule.getRevisionNo());
        w.setUnitSystem(unitSystem);
        w.setFuelCondition(req.getFuelCondition());
        w.setOilCondition(req.getOilCondition());
        w.setRemark(req.getRemark());
        w.setStatus("DRAFT");
        w.setWeighedBy(req.getWeighedBy().trim());
        w.setCreatedAt(now);
        w.setUpdatedAt(now);

        RuleData data = toRuleData(rule, loadPivots(rule.getId()), loadNodes(rule.getId()));
        Result result = WbCalculator.weigh(data, inputs);
        snapshotResult(w, result);
        w = weighingRepository.save(w);

        int line = 0;
        int pivotIdx = 0;
        int corrIdx = 0;
        for (ItemInput in : inputs) {
            line++;
            PivotResult pivotRow = "PIVOT".equals(in.itemType)
                    ? result.pivots.get(pivotIdx++) : null;
            CorrectionResult corrRow = "PIVOT".equals(in.itemType) ? null
                    : result.corrections.get(corrIdx++);
            itemRepository.save(toItemEntity(w.getId(), in, line, pivotRow, corrRow));
        }

        event(ac.getId(), "WEIGHING_CREATE", rule.getId(), w.getId(), null, w.getWeighedBy(),
                "登记称重 " + w.getWeighingNo() + "（" + ac.getTailNo() + "，引用规则 "
                        + rule.getRevisionNo() + "），判定：" + envelopeName(w.getEnvelopeStatus()));
        return getWeighing(w.getId());
    }

    @Transactional
    public Views.WbWeighingDetailView confirm(Long id, WbDtos.WeighingConfirmReq req) {
        WbWeighing w = weighingRepository.findById(id)
                .orElseThrow(() -> new BizException("称重记录不存在：id=" + id));
        if ("CONFIRMED".equals(w.getStatus())) {
            throw new BizException("该称重已复核确认，不可重复复核");
        }
        String reviewer = req.getReviewedBy().trim();
        if (reviewer.equals(w.getWeighedBy())) {
            throw new BizException("复核人与称证人必须是不同的人");
        }
        w.setStatus("CONFIRMED");
        w.setReviewedBy(reviewer);
        w.setReviewedAt(LocalDateTime.now());
        w.setUpdatedAt(LocalDateTime.now());
        weighingRepository.save(w);
        event(w.getAircraftId(), "WEIGHING_CONFIRM", w.getRuleRevisionId(), w.getId(), null, reviewer,
                "称重 " + w.getWeighingNo() + " 经 " + reviewer + " 复核确认，判定："
                        + envelopeName(w.getEnvelopeStatus()));

        if (Boolean.TRUE.equals(req.getSetBaseline())) {
            doSetBaseline(w, reviewer);
        }
        return getWeighing(w.getId());
    }

    /** 复核通过的称重设为当前基线：派生/重置 ROOT 构型链。只有包线内可以成为基线。 */
    @Transactional
    public Views.WbAircraftStatusView setBaseline(Long weighingId, String operator) {
        WbWeighing w = weighingRepository.findById(weighingId)
                .orElseThrow(() -> new BizException("称重记录不存在：id=" + weighingId));
        doSetBaseline(w, operator);
        return aircraftStatus(w.getAircraftId());
    }

    private void doSetBaseline(WbWeighing w, String operator) {
        if (!"CONFIRMED".equals(w.getStatus())) {
            throw new BizException("只有复核确认通过的称重才能成为基线");
        }
        if (!INSIDE.equals(w.getEnvelopeStatus())) {
            throw new BizException("该称重判定为「" + envelopeName(w.getEnvelopeStatus())
                    + "」，不能设为当前基线；请先纠正读数/扣补项后重新登记");
        }
        // 锁整条链，确保基线替换与构型派生不并发
        List<WbConfigVersion> chain = configRepository.findChainForUpdate(w.getAircraftId());
        LocalDateTime now = LocalDateTime.now();
        for (WbConfigVersion v : chain) {
            if (v.getActiveAircraftId() != null) {
                v.setActiveAircraftId(null);
                configRepository.save(v);
            }
        }
        WbConfigVersion root = new WbConfigVersion();
        root.setAircraftId(w.getAircraftId());
        root.setTailNo(w.getTailNo());
        root.setModel(w.getModel());
        root.setVersionNo(0);
        root.setKind("ROOT");
        root.setRootWeighingId(w.getId());
        root.setSourceWeighingId(w.getId());
        root.setRuleRevisionId(w.getRuleRevisionId());
        root.setRuleRevisionNo(w.getRuleRevisionNo());
        root.setChangeReason("称重基线：" + w.getWeighingNo());
        root.setOperatorName(operator);
        root.setWeightKg(w.getFinalWeightKg());
        root.setMoment(w.getFinalMoment());
        root.setCgArmMm(w.getCgArmMm());
        root.setEnvelopeStatus(w.getEnvelopeStatus());
        root.setViolationKind(w.getViolationKind());
        root.setViolationDelta(w.getViolationDelta());
        root.setLimitFrontMm(w.getLimitFrontMm());
        root.setLimitAftMm(w.getLimitAftMm());
        root.setActiveAircraftId(w.getAircraftId());
        root.setCreatedAt(now);
        root = configRepository.save(root);

        w.setBaselinedAt(now);
        w.setUpdatedAt(now);
        weighingRepository.save(w);
        event(w.getAircraftId(), "BASELINE_SET", w.getRuleRevisionId(), w.getId(), root.getId(), operator,
                "称重 " + w.getWeighingNo() + " 成为 " + w.getTailNo() + " 当前称重基线（构型 v0），"
                        + "旧构型链归档保留，历史结果不变");
    }

    @Transactional(readOnly = true)
    public List<Views.WbWeighingView> listWeighings(Long aircraftId) {
        List<WbWeighing> list = aircraftId == null
                ? weighingRepository.findAllByOrderByWeighingDateDescIdDesc()
                : weighingRepository.findAllByAircraftIdOrderByWeighingDateDescIdDesc(aircraftId);
        Map<Long, Long> baselineWeighingIds = currentBaselineWeighingIds(list.stream()
                .map(WbWeighing::getAircraftId).distinct().toList());
        return list.stream().map(w -> toWeighingView(w, baselineWeighingIds.get(w.getAircraftId()))).toList();
    }

    @Transactional(readOnly = true)
    public Views.WbWeighingDetailView getWeighing(Long id) {
        WbWeighing w = weighingRepository.findById(id)
                .orElseThrow(() -> new BizException("称重记录不存在：id=" + id));
        List<WbWeighingItem> items = itemRepository.findAllByWeighingIdOrderByLineNoAsc(id);
        WbRuleVersion rule = mustRule(w.getRuleRevisionId());
        Map<Long, Long> base = currentBaselineWeighingIds(List.of(w.getAircraftId()));
        Views.WbWeighingView header = toWeighingView(w, base.get(w.getAircraftId()));
        List<Views.WbItemView> itemViews = items.stream().map(this::toItemView).toList();
        // 计算明细从结果快照字段重建（永不被新规则回算）
        WbCalcView calc = rebuildCalcView(w, items, rule);
        return new Views.WbWeighingDetailView(header, w.getFuelCondition(), w.getOilCondition(),
                w.getRemark(), itemViews, calc, toRuleView(rule, loadNodes(rule.getId()),
                        loadPivots(rule.getId())));
    }

    // ====================================================================
    // 构型派生
    // ====================================================================

    @Transactional
    public Views.WbConfigVersionView derive(Long aircraftId, WbDtos.ConfigDeriveReq req) {
        Aircraft ac = aircraftRepository.findById(aircraftId)
                .orElseThrow(() -> new BizException("飞机不存在：id=" + aircraftId));
        // 锁链：只能从当前版本继续派生
        List<WbConfigVersion> chain = configRepository.findChainForUpdate(aircraftId);
        WbConfigVersion current = chain.stream()
                .filter(v -> v.getActiveAircraftId() != null)
                .findFirst()
                .orElseThrow(() -> new BizException("该机尚未建立称重基线，无法登记构型调整；"
                        + "请先完成实机称重与双人复核"));
        if (req.getAdjustments() == null || req.getAdjustments().isEmpty()) {
            throw new BizException("构型调整项不能为空");
        }
        WbRuleVersion rule = mustRule(current.getRuleRevisionId());

        List<Object[]> calcInputs = new ArrayList<>();
        List<WbConfigAdjustment> entities = new ArrayList<>();
        int line = 0;
        for (WbDtos.AdjustmentReq a : req.getAdjustments()) {
            line++;
            String action = "REMOVE".equalsIgnoreCase(a.getAction()) ? "REMOVE"
                    : ("INSTALL".equalsIgnoreCase(a.getAction()) ? "INSTALL" : null);
            if (action == null) {
                throw new BizException("第 " + line + " 行调整动作只能是 INSTALL 安装或 REMOVE 拆除");
            }
            String wu = normalizeWeightUnit(a.getWeightUnit());
            String lu = normalizeLengthUnit(a.getLengthUnit());
            BigDecimal unitWeight;
            BigDecimal arm;
            try {
                unitWeight = WbCalculator.toKg(WbCalculator.parse(a.getWeight()), wu);
                arm = WbCalculator.toMm(WbCalculator.parse(a.getArm()), lu);
            } catch (WbCalculator.IllegalNumberException e) {
                throw new BizException("第 " + line + " 行「" + a.getItemName() + "」：" + e.getMessage());
            }
            if (unitWeight.signum() <= 0) {
                throw new BizException("第 " + line + " 行设备重量必须为正数");
            }
            if (arm.signum() < 0) {
                throw new BizException("第 " + line + " 行臂长不能为负");
            }
            int qty = a.getQuantity() == null || a.getQuantity() <= 0 ? 1 : a.getQuantity();
            calcInputs.add(new Object[]{action, unitWeight, arm, qty, a.getItemName()});

            WbConfigAdjustment e = new WbConfigAdjustment();
            e.setAircraftId(aircraftId); // 冗余落库，跨机并入在入口与数据层双重拒绝
            e.setAction(action);
            e.setItemName(a.getItemName().trim());
            e.setWeightReading(a.getWeight().trim());
            e.setArmReading(a.getArm().trim());
            e.setWeightUnit(wu);
            e.setLengthUnit(lu);
            e.setQuantity(qty);
            e.setUnitWeightKg(unitWeight);
            e.setArmMm(arm);
            BigDecimal total = unitWeight.multiply(BigDecimal.valueOf(qty));
            e.setTotalWeightKg(total);
            e.setMoment(total.multiply(arm).setScale(WbCalculator.MOMENT_SCALE,
                    java.math.RoundingMode.HALF_UP));
            e.setReason(a.getReason());
            e.setWorkCardNo(a.getWorkCardNo());
            e.setLineNo(line);
            entities.add(e);
        }

        RuleData data = toRuleData(rule, loadPivots(rule.getId()), loadNodes(rule.getId()));
        Result r = WbCalculator.derive(data, current.getWeightKg(), current.getMoment(), calcInputs);

        current.setActiveAircraftId(null);
        configRepository.save(current);

        WbConfigVersion v = new WbConfigVersion();
        v.setAircraftId(aircraftId);
        v.setTailNo(ac.getTailNo());
        v.setModel(ac.getModel());
        v.setVersionNo(current.getVersionNo() + 1);
        v.setKind("DERIVED");
        v.setRootWeighingId(current.getRootWeighingId());
        v.setParentVersionId(current.getId());
        v.setRuleRevisionId(rule.getId());
        v.setRuleRevisionNo(rule.getRevisionNo());
        v.setChangeReason(req.getReason().trim());
        v.setWorkCardNo(req.getWorkCardNo());
        v.setOperatorName(req.getOperator().trim());
        v.setWeightKg(r.finalWeightKg);
        v.setMoment(r.finalMoment);
        v.setCgArmMm(r.cgArmMm);
        v.setEnvelopeStatus(r.envelopeStatus);
        v.setViolationKind(r.violationKind);
        v.setViolationDelta(r.violationDelta);
        v.setLimitFrontMm(r.limitFrontMm);
        v.setLimitAftMm(r.limitAftMm);
        v.setActiveAircraftId(aircraftId);
        v.setCreatedAt(LocalDateTime.now());
        v = configRepository.save(v);
        for (WbConfigAdjustment e : entities) {
            e.setConfigVersionId(v.getId());
            adjustmentRepository.save(e);
        }

        String detail = "由构型 v" + current.getVersionNo() + " 派生 v" + v.getVersionNo()
                + "（" + entities.size() + " 项调整，工卡 "
                + (req.getWorkCardNo() == null ? "无" : req.getWorkCardNo()) + "），判定："
                + envelopeName(r.envelopeStatus);
        if (!INSIDE.equals(r.envelopeStatus) && r.violationKind != null) {
            String unit = FORWARD.equals(r.violationKind) || AFT.equals(r.violationKind) ? " mm" : " kg";
            detail += "；越界方向：" + violationName(r.violationKind) + "，差值 "
                    + r.violationDelta.stripTrailingZeros().toPlainString() + unit;
        }
        event(aircraftId, "CONFIG_DERIVE", rule.getId(), null, v.getId(), req.getOperator().trim(), detail);
        return getConfigVersion(v.getId());
    }

    @Transactional(readOnly = true)
    public Views.WbConfigVersionView getConfigVersion(Long id) {
        WbConfigVersion v = configRepository.findById(id)
                .orElseThrow(() -> new BizException("构型版本不存在：id=" + id));
        List<WbConfigAdjustment> adjs = adjustmentRepository
                .findAllByConfigVersionIdOrderByLineNoAsc(id);
        return toConfigView(v, adjs);
    }

    /** 版本对比：必须是同一架飞机链上的两个版本 */
    @Transactional(readOnly = true)
    public Views.WbVersionDiffView compare(Long aircraftId, Long fromId, Long toId) {
        WbConfigVersion from = configRepository.findById(fromId)
                .orElseThrow(() -> new BizException("对比版本不存在：id=" + fromId));
        WbConfigVersion to = configRepository.findById(toId)
                .orElseThrow(() -> new BizException("对比版本不存在：id=" + toId));
        if (!from.getAircraftId().equals(aircraftId) || !to.getAircraftId().equals(aircraftId)) {
            throw new BizException("只能对比同一架飞机构型链上的版本");
        }
        Map<Long, List<WbConfigAdjustment>> adjMap = adjustmentRepository
                .findAllByConfigVersionIdInOrderByLineNoAsc(List.of(fromId, toId)).stream()
                .collect(Collectors.groupingBy(WbConfigAdjustment::getConfigVersionId));
        Views.WbConfigVersionView fv = toConfigView(from, adjMap.getOrDefault(fromId, List.of()));
        Views.WbConfigVersionView tv = toConfigView(to, adjMap.getOrDefault(toId, List.of()));
        BigDecimal dw = to.getWeightKg().subtract(from.getWeightKg());
        BigDecimal dm = to.getMoment().subtract(from.getMoment());
        BigDecimal dc = to.getCgArmMm().subtract(from.getCgArmMm());
        return new Views.WbVersionDiffView(fv, tv,
                fmt(dw), fmt(WbCalculator.kgToLb(dw)), fmt(dm), fmt(dc),
                fmt(WbCalculator.mmToIn(dc)),
                envelopeName(from.getEnvelopeStatus()), envelopeName(to.getEnvelopeStatus()));
    }

    // ====================================================================
    // 飞机档案 / 总览 / 事件
    // ====================================================================

    @Transactional(readOnly = true)
    public List<Views.WbAircraftStatusView> fleet() {
        return aircraftRepository.findAll().stream().map(a -> aircraftStatus(a.getId())).toList();
    }

    @Transactional(readOnly = true)
    public Views.WbAircraftStatusView aircraftStatus(Long aircraftId) {
        Aircraft ac = aircraftRepository.findById(aircraftId)
                .orElseThrow(() -> new BizException("飞机不存在：id=" + aircraftId));
        List<WbWeighing> weighings = weighingRepository
                .findAllByAircraftIdOrderByWeighingDateDescIdDesc(aircraftId);
        List<WbConfigVersion> chain = configRepository.findAllByAircraftIdOrderByVersionNoAsc(aircraftId);
        Optional<WbConfigVersion> current = chain.stream()
                .filter(v -> v.getActiveAircraftId() != null).findFirst();

        // 只有"当前链的 v0 根称重"标为当前基线
        Long baselineWeighingId = current.map(c -> c.getVersionNo() == 0 ? c.getRootWeighingId() : null)
                .orElse(null);
        List<Views.WbWeighingView> wv = weighings.stream()
                .map(w -> toWeighingView(w, baselineWeighingId))
                .toList();
        Map<Long, List<WbConfigAdjustment>> adjMap = chain.isEmpty() ? Map.of()
                : adjustmentRepository
                        .findAllByConfigVersionIdInOrderByLineNoAsc(
                                chain.stream().map(WbConfigVersion::getId).toList())
                        .stream().collect(Collectors.groupingBy(WbConfigAdjustment::getConfigVersionId));
        List<Views.WbConfigVersionView> cv = chain.stream()
                .sorted(Comparator.comparing(WbConfigVersion::getVersionNo))
                .map(v -> toConfigView(v, adjMap.getOrDefault(v.getId(), List.of())))
                .toList();

        boolean established = current.isPresent();
        String label = established ? "基线版本 v" + current.get().getVersionNo() : "未建立基线";
        return new Views.WbAircraftStatusView(ac.getId(), ac.getTailNo(), ac.getModel(), ac.getAirline(),
                established, label,
                current.map(WbConfigVersion::getId).orElse(null),
                current.map(WbConfigVersion::getVersionNo).orElse(null),
                current.map(v -> fmt(v.getWeightKg())).orElse(null),
                current.map(v -> fmt(v.getCgArmMm())).orElse(null),
                current.map(WbConfigVersion::getEnvelopeStatus).orElse(null),
                current.map(v -> envelopeName(v.getEnvelopeStatus())).orElse(null),
                current.map(WbConfigVersion::getRuleRevisionNo).orElse(null),
                wv, cv);
    }

    @Transactional(readOnly = true)
    public Views.WbOverview overview() {
        List<Aircraft> all = aircraftRepository.findAll();
        long baseline = 0;
        long outside = 0;
        long undetermined = 0;
        for (Aircraft ac : all) {
            var cur = configRepository.findByActiveAircraftId(ac.getId());
            if (cur.isPresent()) {
                baseline++;
                if ("OUTSIDE".equals(cur.get().getEnvelopeStatus())) {
                    outside++;
                }
                if ("UNDETERMINED".equals(cur.get().getEnvelopeStatus())) {
                    undetermined++;
                }
            }
        }
        long ruleModels = ruleRepository.findAllByStatusOrderByEffectiveDateDesc("EFFECTIVE").stream()
                .map(WbRuleVersion::getModel).distinct().count();
        return new Views.WbOverview(all.size(), baseline, all.size() - baseline,
                outside, undetermined, ruleModels);
    }

    @Transactional(readOnly = true)
    public List<Views.WbEventView> events(Long aircraftId) {
        Map<Long, String> tailMap = aircraftRepository.findAll().stream()
                .collect(Collectors.toMap(Aircraft::getId, Aircraft::getTailNo));
        List<WbEvent> list = aircraftId == null
                ? eventRepository.findAllByOrderByEventAtDescIdDesc()
                : eventRepository.findAllByAircraftIdOrderByEventAtDescIdDesc(aircraftId);
        return list.stream().map(e -> new Views.WbEventView(e.getId(), e.getAircraftId(),
                e.getAircraftId() == null ? null : tailMap.get(e.getAircraftId()),
                e.getEventType(), e.getOperatorName(), e.getDetail(), e.getEventAt())).toList();
    }

    // ====================================================================
    // 内部装配
    // ====================================================================

    private RuleData toRuleData(WbRuleVersion rule, List<WbRulePivot> pivots, List<WbEnvelopeNode> nodes) {
        Map<String, BigDecimal> armMap = new HashMap<>();
        for (WbRulePivot p : pivots) {
            armMap.put(p.getPivotCode(), p.getArmMm());
        }
        List<BigDecimal[]> front = nodes.stream()
                .filter(n -> "FRONT".equals(n.getBoundaryKind()))
                .sorted(Comparator.comparing(WbEnvelopeNode::getWeightKg))
                .map(n -> new BigDecimal[]{n.getWeightKg(), n.getArmMm()}).toList();
        List<BigDecimal[]> aft = nodes.stream()
                .filter(n -> "AFT".equals(n.getBoundaryKind()))
                .sorted(Comparator.comparing(WbEnvelopeNode::getWeightKg))
                .map(n -> new BigDecimal[]{n.getWeightKg(), n.getArmMm()}).toList();
        return new RuleData(rule.getModel(), rule.getRevisionNo(), armMap,
                rule.getMinWeightKg(), rule.getMaxWeightKg(), front, aft);
    }

    private List<ItemInput> toInputs(List<WbDtos.WeighingItemReq> rows, String unitSystem) {
        List<ItemInput> list = new ArrayList<>();
        int line = 0;
        for (WbDtos.WeighingItemReq r : rows) {
            line++;
            ItemInput in = new ItemInput();
            String type = r.getItemType().trim().toUpperCase();
            if (!List.of("PIVOT", "FUEL", "OIL", "TEMP", "CONDITION").contains(type)) {
                throw new BizException("第 " + line + " 行明细类型非法：" + r.getItemType());
            }
            in.itemType = type;
            in.pivotCode = r.getPivotCode() == null ? null : r.getPivotCode().trim().toUpperCase();
            in.scaleNo = r.getScaleNo();
            in.itemName = r.getItemName();
            in.grossReading = r.getGrossReading();
            in.tareReading = r.getTareReading();
            in.armReading = r.getArmReading();
            in.quantity = r.getQuantity();
            in.weightUnit = normalizeWeightUnit(r.getWeightUnit() != null ? r.getWeightUnit()
                    : ("IMPERIAL".equals(unitSystem) ? "LB" : "KG"));
            in.lengthUnit = normalizeLengthUnit(r.getLengthUnit() != null ? r.getLengthUnit()
                    : ("IMPERIAL".equals(unitSystem) ? "IN" : "MM"));
            if (type.equals("PIVOT")) {
                in.effect = null;
            } else {
                in.effect = r.getEffect() == null
                        ? (type.equals("TEMP") || type.equals("CONDITION") ? "ADD" : "DEDUCT")
                        : ("ADD".equalsIgnoreCase(r.getEffect()) ? "ADD"
                                : "DEDUCT".equalsIgnoreCase(r.getEffect()) ? "DEDUCT" : null);
                if (in.effect == null) {
                    throw new BizException("第 " + line + " 行扣补方向只能是 ADD 补入或 DEDUCT 扣除");
                }
            }
            in.reason = r.getReason();
            in.lineNo = line;
            list.add(in);
        }
        return list;
    }

    private void snapshotResult(WbWeighing w, Result r) {
        w.setGrossWeightKg(r.grossWeightKg);
        w.setGrossMoment(r.grossMoment);
        w.setFinalWeightKg(r.finalWeightKg);
        w.setFinalMoment(r.finalMoment);
        w.setCgArmMm(r.cgArmMm);
        w.setEnvelopeStatus(r.envelopeStatus);
        w.setViolationKind(r.violationKind);
        w.setViolationDelta(r.violationDelta);
        w.setLimitFrontMm(r.limitFrontMm);
        w.setLimitAftMm(r.limitAftMm);
        w.setCalcIssues(r.issues.isEmpty() ? null : String.join("；", r.issues));
    }

    private WbWeighingItem toItemEntity(Long weighingId, ItemInput in, int line,
                                        PivotResult pivotRow, CorrectionResult corrRow) {
        WbWeighingItem e = new WbWeighingItem();
        e.setWeighingId(weighingId);
        e.setItemType(in.itemType);
        e.setPivotCode(in.pivotCode);
        e.setScaleNo(in.scaleNo);
        e.setItemName(in.itemName);
        e.setGrossReading(in.grossReading);
        e.setTareReading(in.tareReading == null ? "0" : in.tareReading);
        e.setArmReading(in.armReading);
        e.setQuantity(in.quantity == null ? 1 : in.quantity);
        e.setWeightUnit(in.weightUnit);
        e.setLengthUnit(in.lengthUnit);
        e.setConditionEffect(in.effect);
        e.setReason(in.reason);
        e.setLineNo(line);
        // 统一单位快照：与计算结果中对应行一致（同一计算事实）
        if ("PIVOT".equals(in.itemType)) {
            if (pivotRow != null) {
                e.setGrossKg(pivotRow.grossKg);
                e.setTareKg(pivotRow.tareKg);
                e.setNetKg(pivotRow.netKg);
                e.setArmMm(pivotRow.armMm);
            }
        } else if (corrRow != null) {
            e.setGrossKg(corrRow.unitWeightKg);
            e.setNetKg(corrRow.totalWeightKg);
            e.setArmMm(corrRow.armMm);
        }
        return e;
    }

    private WbCalcView rebuildCalcView(WbWeighing w, List<WbWeighingItem> items, WbRuleVersion rule) {
        // 支点净重/扣补行从明细快照恢复；总量与判定从表头快照读取（不重新判包线）
        List<Views.WbPivotNet> nets = new ArrayList<>();
        List<Views.WbCorrectionView> corrections = new ArrayList<>();
        for (WbWeighingItem it : items) {
            if ("PIVOT".equals(it.getItemType())) {
                nets.add(new Views.WbPivotNet(it.getPivotCode(), it.getScaleNo(),
                        fmt(it.getNetKg()),
                        it.getNetKg() == null ? null : fmt(WbCalculator.kgToLb(it.getNetKg())),
                        fmt(it.getArmMm()),
                        it.getArmMm() == null ? null : fmt(WbCalculator.mmToIn(it.getArmMm())),
                        (it.getNetKg() == null || it.getArmMm() == null) ? null
                                : fmt(it.getNetKg().multiply(it.getArmMm()))));
            } else {
                BigDecimal signedMo = null;
                if (it.getNetKg() != null && it.getArmMm() != null) {
                    int sign = "ADD".equals(it.getConditionEffect()) ? 1 : -1;
                    signedMo = it.getNetKg().multiply(it.getArmMm())
                            .multiply(BigDecimal.valueOf(sign));
                }
                corrections.add(new Views.WbCorrectionView(it.getItemType(), it.getItemName(),
                        it.getConditionEffect(), it.getQuantity(),
                        fmt(it.getNetKg()),
                        it.getNetKg() == null ? null : fmt(WbCalculator.kgToLb(it.getNetKg())),
                        fmt(it.getArmMm()),
                        it.getArmMm() == null ? null : fmt(WbCalculator.mmToIn(it.getArmMm())),
                        signedMo == null ? null : fmt(signedMo)));
            }
        }
        List<String> issues = w.getCalcIssues() == null ? List.of()
                : List.of(w.getCalcIssues().split("；"));
        return new WbCalcView(
                items.stream().map(this::toItemView).toList(),
                nets, corrections,
                fmt(w.getGrossWeightKg()),
                w.getGrossWeightKg() == null ? null : fmt(WbCalculator.kgToLb(w.getGrossWeightKg())),
                fmt(w.getGrossMoment()),
                fmt(w.getFinalWeightKg()),
                w.getFinalWeightKg() == null ? null : fmt(WbCalculator.kgToLb(w.getFinalWeightKg())),
                fmt(w.getFinalMoment()),
                fmt(w.getCgArmMm()),
                w.getCgArmMm() == null ? null : fmt(WbCalculator.mmToIn(w.getCgArmMm())),
                w.getEnvelopeStatus(), envelopeName(w.getEnvelopeStatus()),
                w.getViolationKind(), violationName(w.getViolationKind()),
                fmt(w.getViolationDelta()),
                FORWARD.equals(w.getViolationKind()) || AFT.equals(w.getViolationKind()) ? "mm"
                        : (OVER_WEIGHT.equals(w.getViolationKind()) || UNDER_WEIGHT.equals(w.getViolationKind())
                                ? "kg" : null),
                fmt(w.getLimitFrontMm()), fmt(w.getLimitAftMm()),
                w.getLimitFrontMm() == null ? null : fmt(WbCalculator.mmToIn(w.getLimitFrontMm())),
                w.getLimitAftMm() == null ? null : fmt(WbCalculator.mmToIn(w.getLimitAftMm())),
                issues);
    }

    private WbCalcView toCalcView(Result r, List<ItemInput> inputs, Object ignored) {
        List<Views.WbPivotNet> nets = r.pivots.stream()
                .map(p -> new Views.WbPivotNet(p.pivotCode, p.scaleNo,
                        fmt(p.netKg), p.netKg == null ? null : fmt(WbCalculator.kgToLb(p.netKg)),
                        fmt(p.armMm), p.armMm == null ? null : fmt(WbCalculator.mmToIn(p.armMm)),
                        fmt(p.moment)))
                .toList();
        List<Views.WbCorrectionView> cs = r.corrections.stream()
                .map(c -> new Views.WbCorrectionView(c.itemType, c.itemName, c.effect, c.quantity,
                        fmt(c.totalWeightKg),
                        c.totalWeightKg == null ? null : fmt(WbCalculator.kgToLb(c.totalWeightKg)),
                        fmt(c.armMm),
                        c.armMm == null ? null : fmt(WbCalculator.mmToIn(c.armMm)),
                        fmt(c.signedMoment)))
                .toList();
        // 结果按 PIVOT / 非 PIVOT 两组分别保序，按输入顺序对齐
        List<Views.WbItemView> itemViews = new ArrayList<>();
        int pivotIdx = 0;
        int corrIdx = 0;
        for (ItemInput in : inputs) {
            BigDecimal grossKg = null, tareKg = null, netKg = null, armMm = null, moment = null;
            if ("PIVOT".equals(in.itemType)) {
                if (pivotIdx < r.pivots.size()) {
                    PivotResult p = r.pivots.get(pivotIdx++);
                    grossKg = p.grossKg;
                    tareKg = p.tareKg;
                    netKg = p.netKg;
                    armMm = p.armMm;
                    moment = p.moment;
                }
            } else if (corrIdx < r.corrections.size()) {
                CorrectionResult c = r.corrections.get(corrIdx++);
                grossKg = c.unitWeightKg;
                netKg = c.totalWeightKg;
                armMm = c.armMm;
                moment = c.signedMoment;
            }
            itemViews.add(new Views.WbItemView(null, in.itemType, in.pivotCode, in.scaleNo, in.itemName,
                    in.grossReading, in.tareReading, in.armReading, in.quantity,
                    in.weightUnit, in.lengthUnit, in.effect, in.reason, in.lineNo,
                    grossKg, tareKg, netKg, armMm, moment));
        }
        return new WbCalcView(itemViews, nets, cs,
                fmt(r.grossWeightKg), fmt(WbCalculator.kgToLb(r.grossWeightKg)), fmt(r.grossMoment),
                fmt(r.finalWeightKg), fmt(WbCalculator.kgToLb(r.finalWeightKg)), fmt(r.finalMoment),
                fmt(r.cgArmMm), r.cgArmMm == null ? null : fmt(WbCalculator.mmToIn(r.cgArmMm)),
                r.envelopeStatus, envelopeName(r.envelopeStatus),
                r.violationKind, violationName(r.violationKind),
                fmt(r.violationDelta),
                FORWARD.equals(r.violationKind) || AFT.equals(r.violationKind) ? "mm"
                        : (OVER_WEIGHT.equals(r.violationKind) || UNDER_WEIGHT.equals(r.violationKind)
                                ? "kg" : null),
                fmt(r.limitFrontMm), fmt(r.limitAftMm),
                r.limitFrontMm == null ? null : fmt(WbCalculator.mmToIn(r.limitFrontMm)),
                r.limitAftMm == null ? null : fmt(WbCalculator.mmToIn(r.limitAftMm)),
                r.issues);
    }

    private Views.WbItemView toItemView(WbWeighingItem it) {
        BigDecimal moment = null;
        if ("PIVOT".equals(it.getItemType()) && it.getNetKg() != null && it.getArmMm() != null) {
            moment = it.getNetKg().multiply(it.getArmMm());
        }
        return new Views.WbItemView(it.getId(), it.getItemType(), it.getPivotCode(), it.getScaleNo(),
                it.getItemName(), it.getGrossReading(), it.getTareReading(), it.getArmReading(),
                it.getQuantity(), it.getWeightUnit(), it.getLengthUnit(), it.getConditionEffect(),
                it.getReason(), it.getLineNo(),
                it.getGrossKg(), it.getTareKg(), it.getNetKg(), it.getArmMm(), moment);
    }

    private Views.WbWeighingView toWeighingView(WbWeighing w, Long currentBaselineWeighingId) {
        boolean baseline = currentBaselineWeighingId != null
                && currentBaselineWeighingId.equals(w.getId());
        return new Views.WbWeighingView(w.getId(), w.getWeighingNo(), w.getAircraftId(), w.getTailNo(),
                w.getModel(), w.getWeighingDate(), w.getScaleNo(), w.getRuleRevisionId(),
                w.getRuleRevisionNo(), w.getUnitSystem(), w.getStatus(), weighingStatusName(w.getStatus()),
                w.getWeighedBy(), w.getReviewedBy(),
                fmt(w.getFinalWeightKg()), fmt(w.getCgArmMm()),
                w.getEnvelopeStatus(), envelopeName(w.getEnvelopeStatus()),
                violationName(w.getViolationKind()), fmt(w.getViolationDelta()),
                baseline, w.getReviewedAt(), w.getCreatedAt());
    }

    private Views.WbConfigVersionView toConfigView(WbConfigVersion v, List<WbConfigAdjustment> adjs) {
        List<Views.WbAdjustmentView> av = adjs.stream()
                .map(a -> new Views.WbAdjustmentView(a.getId(), a.getAction(), a.getItemName(),
                        a.getWeightReading(), a.getArmReading(), a.getWeightUnit(), a.getLengthUnit(),
                        a.getQuantity(), a.getReason(), a.getWorkCardNo(), a.getLineNo(),
                        fmt(a.getUnitWeightKg()), fmt(a.getArmMm()), fmt(a.getTotalWeightKg()),
                        fmt(a.getMoment())))
                .toList();
        return new Views.WbConfigVersionView(v.getId(), v.getAircraftId(), v.getTailNo(), v.getModel(),
                v.getVersionNo(), v.getKind(), v.getParentVersionId(), v.getRootWeighingId(),
                v.getRuleRevisionId(), v.getRuleRevisionNo(), v.getChangeReason(), v.getWorkCardNo(),
                v.getOperatorName(),
                fmt(v.getWeightKg()), fmt(WbCalculator.kgToLb(v.getWeightKg())),
                fmt(v.getMoment()), fmt(v.getCgArmMm()), fmt(WbCalculator.mmToIn(v.getCgArmMm())),
                v.getEnvelopeStatus(), envelopeName(v.getEnvelopeStatus()),
                violationName(v.getViolationKind()), fmt(v.getViolationDelta()),
                fmt(v.getLimitFrontMm()), fmt(v.getLimitAftMm()),
                v.getActiveAircraftId() != null, v.getCreatedAt(), av);
    }

    private Map<Long, Long> currentBaselineWeighingIds(List<Long> aircraftIds) {
        Map<Long, Long> map = new HashMap<>();
        for (Long id : aircraftIds) {
            configRepository.findByActiveAircraftId(id).ifPresent(v -> {
                if (v.getVersionNo() == 0) {
                    map.put(id, v.getRootWeighingId());
                }
            });
        }
        return map;
    }

    private void event(Long aircraftId, String type, Long ruleId, Long weighingId, Long configId,
                       String operator, String detail) {
        WbEvent e = new WbEvent();
        e.setAircraftId(aircraftId);
        e.setEventType(type);
        e.setRuleRevisionId(ruleId);
        e.setWeighingId(weighingId);
        e.setConfigVersionId(configId);
        e.setOperatorName(operator);
        e.setDetail(detail.length() > 500 ? detail.substring(0, 500) : detail);
        e.setEventAt(LocalDateTime.now());
        eventRepository.save(e);
    }

    private String generateWeighingNo() {
        String prefix = "WB-" + LocalDate.now().toString().replace("-", "") + "-";
        long seq = weighingRepository.count() + 1;
        String no;
        do {
            no = prefix + String.format("%03d", seq++);
        } while (weighingRepository.findByWeighingNo(no).isPresent());
        return no;
    }

    // ---- 单位与数字 ----

    private static String normalizeWeightUnit(String u) {
        if (u == null || u.isBlank()) {
            return "KG";
        }
        String up = u.trim().toUpperCase();
        if (!"KG".equals(up) && !"LB".equals(up)) {
            throw new BizException("重量单位只能是 KG 或 LB：" + u);
        }
        return up;
    }

    private static String normalizeLengthUnit(String u) {
        if (u == null || u.isBlank()) {
            return "MM";
        }
        String up = u.trim().toUpperCase();
        if (!"MM".equals(up) && !"IN".equals(up)) {
            throw new BizException("长度单位只能是 MM 或 IN：" + u);
        }
        return up;
    }

    private static BigDecimal parseWeight(String raw, String unit, String label) {
        try {
            return WbCalculator.toKg(WbCalculator.parse(raw), unit);
        } catch (WbCalculator.IllegalNumberException e) {
            throw new BizException(label + "：" + e.getMessage());
        }
    }

    private static BigDecimal parseArm(String raw, String unit, String label) {
        try {
            return WbCalculator.toMm(WbCalculator.parse(raw), unit);
        } catch (WbCalculator.IllegalNumberException e) {
            throw new BizException(label + "：" + e.getMessage());
        }
    }

    static String fmt(BigDecimal v) {
        return v == null ? null : v.stripTrailingZeros().toPlainString();
    }
}
