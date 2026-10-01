package com.aircraft.mro.dto;

import java.time.LocalDate;
import java.util.List;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/** 模块八（飞机称重与重心）入参。 */
public final class WbDtos {

    private WbDtos() {
    }

    // ---------------- 称重规则 ----------------

    @Data
    public static class PivotReq {
        @NotBlank(message = "支点编号不能为空")
        private String code;
        @NotBlank(message = "支点名称不能为空")
        private String name;
        /** 臂长原文（kg/mm 统一口径下也以字符串提交，保留录入原样） */
        @NotBlank(message = "支点臂长不能为空")
        private String arm;
        /** MM / IN，默认 MM */
        private String lengthUnit;
    }

    @Data
    public static class EnvelopeNodeReq {
        @NotBlank(message = "边界类型不能为空")
        private String kind; // FRONT / AFT
        @NotBlank(message = "节点重量不能为空")
        private String weight;
        @NotBlank(message = "节点重心臂不能为空")
        private String arm;
        /** KG / LB，默认 KG；长度 MM / IN，默认 MM */
        private String weightUnit;
        private String lengthUnit;
    }

    @Data
    public static class RuleContentReq {
        /** 生效日期（首版/新版本各自指定；旧版不受影响） */
        @NotNull(message = "生效日期不能为空")
        private LocalDate effectiveDate;
        @NotBlank(message = "基准点说明不能为空")
        private String datumName;
        @NotBlank(message = "许用总重下界不能为空")
        private String minWeight;
        @NotBlank(message = "许用总重上界不能为空")
        private String maxWeight;
        /** KG / LB，默认 KG */
        private String weightUnit;
        private String revisionNote;
        @NotNull(message = "至少要有两个起落架支点")
        private List<PivotReq> pivots;
        @NotNull(message = "至少要配置包线折线节点")
        private List<EnvelopeNodeReq> nodes;
    }

    /** 新建机型首版规则（R0），publish=true 直接生效 */
    @Data
    public static class RuleCreateReq {
        @NotBlank(message = "机型不能为空")
        private String model;
        @NotNull(message = "首版规则内容不能为空")
        private RuleContentReq content;
        private Boolean publish;
    }

    /** 在已生效版本基础上修订出新版本（默认草稿，不影响历史称重） */
    @Data
    public static class RuleReviseReq {
        @NotNull(message = "新版规则内容不能为空")
        private RuleContentReq content;
        private Boolean publish;
    }

    /** 草稿编辑 */
    @Data
    public static class RuleDraftUpdateReq {
        @NotNull(message = "规则内容不能为空")
        private RuleContentReq content;
    }

    @Data
    public static class RulePublishReq {
        @NotBlank(message = "发布操作人不能为空")
        private String operator;
    }

    // ---------------- 称重 ----------------

    @Data
    public static class WeighingItemReq {
        @NotBlank(message = "明细类型不能为空")
        private String itemType; // PIVOT / FUEL / OIL / TEMP / CONDITION
        private String pivotCode;
        private String scaleNo;
        private String itemName;
        private String grossReading;
        private String tareReading;
        private String armReading;
        private Integer quantity;
        /** KG / LB；缺省取表头单位制 */
        private String weightUnit;
        /** MM / IN；缺省取表头单位制 */
        private String lengthUnit;
        /** ADD 补入 / DEDUCT 扣除；FUEL/OIL 缺省 DEDUCT，TEMP 必填 */
        private String effect;
        private String reason;
    }

    @Data
    public static class WeighingCreateReq {
        @NotNull(message = "飞机不能为空")
        private Long aircraftId;
        @NotNull(message = "称重日期不能为空")
        private LocalDate weighingDate;
        @NotBlank(message = "秤具编号不能为空")
        private String scaleNo;
        /** 显式指定规则版本；不传则取称重日期当天生效的版本 */
        private Long ruleRevisionId;
        /** METRIC kg/mm（默认）/ IMPERIAL lb/in */
        private String unitSystem;
        private String fuelCondition;
        private String oilCondition;
        private String remark;
        @NotBlank(message = "称重操作人不能为空")
        private String weighedBy;
        @NotNull(message = "称重明细不能为空")
        private List<WeighingItemReq> items;
    }

    @Data
    public static class WeighingConfirmReq {
        @NotBlank(message = "复核人不能为空")
        private String reviewedBy;
        /** true=复核通过后直接把该称重置为本机当前基线 */
        private Boolean setBaseline;
    }

    /** 不保存，按给定规则试算（表单实时预览共用同一计算事实） */
    @Data
    public static class WeighingPreviewReq {
        private Long aircraftId;
        private Long ruleRevisionId;
        private String unitSystem;
        private List<WeighingItemReq> items;
    }

    // ---------------- 构型调整 ----------------

    @Data
    public static class AdjustmentReq {
        @NotBlank(message = "调整动作不能为空")
        private String action; // INSTALL / REMOVE
        @NotBlank(message = "设备名称不能为空")
        private String itemName;
        @NotBlank(message = "单位重量不能为空")
        private String weight;
        @NotBlank(message = "臂长不能为空")
        private String arm;
        private String weightUnit;
        private String lengthUnit;
        @NotNull(message = "数量不能为空")
        private Integer quantity;
        private String reason;
        private String workCardNo;
    }

    @Data
    public static class ConfigDeriveReq {
        @NotBlank(message = "调整原因不能为空")
        private String reason;
        private String workCardNo;
        @NotBlank(message = "操作人不能为空")
        private String operator;
        @NotNull(message = "调整项不能为空")
        private List<AdjustmentReq> adjustments;
    }
}
