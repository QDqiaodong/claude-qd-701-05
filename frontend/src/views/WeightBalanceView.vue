<template>
  <div class="wb-page">
    <!-- 顶部总览 + 单位切换：两种展示对应同一份后端计算事实 -->
    <section class="panel">
      <div class="panel-head">
        <h3>飞机称重与重心</h3>
        <span class="panel-sub">
          统一精度换算（kg 6 位 / mm 3 位 HALF_UP）· 原始读数与原单位永久保留 ·
          切换单位只换展示，不重算
        </span>
        <div class="panel-extra">
          <el-radio-group v-model="unitMode" size="small">
            <el-radio-button label="METRIC">统一单位 kg/mm</el-radio-button>
            <el-radio-button label="IMPERIAL">统一换算 lb/in</el-radio-button>
            <el-radio-button label="ORIGINAL">原始单位</el-radio-button>
          </el-radio-group>
          <el-button size="small" @click="reloadAll">刷新</el-button>
        </div>
      </div>
      <div class="wb-overview">
        <div class="wb-chip"><b>{{ overview.aircraftTotal || 0 }}</b><span>在册飞机</span></div>
        <div class="wb-chip"><b>{{ overview.baselineCount || 0 }}</b><span>已建立基线</span></div>
        <div class="wb-chip warn"><b>{{ overview.noBaselineCount || 0 }}</b><span>未建立基线</span></div>
        <div class="wb-chip danger"><b>{{ overview.outsideCount || 0 }}</b><span>当前包线外</span></div>
        <div class="wb-chip warn"><b>{{ overview.undeterminedCount || 0 }}</b><span>无法判定</span></div>
        <div class="wb-chip"><b>{{ overview.effectiveRuleModelCount || 0 }}</b><span>有生效规则机型</span></div>
      </div>
    </section>

    <el-tabs v-model="tab" class="wb-tabs">
      <!-- ============ 1. 机队基线 ============ -->
      <el-tab-pane label="① 机队基线档案" name="fleet">
        <section class="panel">
          <div class="panel-head">
            <h3>称重基线档案（兼容现有台账：没有基线的飞机照常显示，明确标注，不猜默认重心）</h3>
          </div>
          <el-table :data="fleet" border stripe size="small">
            <el-table-column prop="tailNo" label="机号" width="95" />
            <el-table-column prop="model" label="机型" width="75" />
            <el-table-column label="基线状态" width="170">
              <template #default="{ row }">
                <el-tag v-if="row.baselineEstablished" type="success" size="small">
                  基线 v{{ row.currentVersionNo }}（规则 {{ row.ruleRevisionNo }}）
                </el-tag>
                <el-tag v-else type="info" size="small">未建立基线</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="当前重量" width="150">
              <template #default="{ row }">
                <span v-if="row.currentWeightKg">{{ w(row.currentWeightKg, 'KG') }}</span>
                <span v-else class="muted">—</span>
              </template>
            </el-table-column>
            <el-table-column label="当前重心臂" width="150">
              <template #default="{ row }">
                <span v-if="row.currentCgArmMm">{{ w(row.currentCgArmMm, 'MM') }}</span>
                <span v-else class="muted">—</span>
              </template>
            </el-table-column>
            <el-table-column label="包线判定" width="110">
              <template #default="{ row }">
                <el-tag v-if="row.currentEnvelopeStatus" size="small"
                        :type="envType(row.currentEnvelopeStatus)">
                  {{ row.currentEnvelopeStatusName }}
                </el-tag>
                <span v-else class="muted">—</span>
              </template>
            </el-table-column>
            <el-table-column label="称重记录" width="90">
              <template #default="{ row }">{{ row.weighings.length }} 次</template>
            </el-table-column>
            <el-table-column label="构型版本" width="90">
              <template #default="{ row }">{{ row.configChain.length }} 个</template>
            </el-table-column>
            <el-table-column label="操作" min-width="180">
              <template #default="{ row }">
                <el-button link type="primary" @click="openAircraft(row)">称重与构型</el-button>
                <el-button link @click="openWeighForm(row)">登记称重</el-button>
              </template>
            </el-table-column>
          </el-table>
        </section>
      </el-tab-pane>

      <!-- ============ 2. 称重规则版本 ============ -->
      <el-tab-pane label="② 称重规则版本" name="rules">
        <section class="panel">
          <div class="panel-head">
            <h3>机型称重规则（版本化：草稿可改，生效后冻结，新版不回算历史称重）</h3>
            <div class="panel-extra">
              <el-button size="small" type="primary" @click="openRuleCreate">新增机型规则</el-button>
            </div>
          </div>
          <el-collapse v-model="openRules">
            <el-collapse-item v-for="r in rules" :key="r.id"
                              :title="`${r.model} ${r.revisionNo} · ${ruleStatusName(r.status)} · 生效 ${r.effectiveDate}`"
                              :name="r.id">
              <div class="rule-head">
                <el-tag size="small" :type="r.status === 'EFFECTIVE' ? 'success' : (r.status === 'DRAFT' ? 'warning' : 'info')">
                  {{ r.statusName }}
                </el-tag>
                <span class="muted">{{ r.datumName }}</span>
                <span>许用总重：{{ w(r.minWeightKg, 'KG') }} ~ {{ w(r.maxWeightKg, 'KG') }}</span>
                <span v-if="r.revisionNote" class="muted">{{ r.revisionNote }}</span>
                <el-button v-if="r.status === 'DRAFT'" link type="primary" @click="openRuleEdit(r)">编辑草稿</el-button>
                <el-button v-if="r.status === 'DRAFT'" link type="success" @click="openPublish(r)">发布生效</el-button>
                <el-button v-if="r.status === 'EFFECTIVE'" link type="warning" @click="openRevise(r)">修订新版本</el-button>
              </div>
              <div class="rule-cols">
                <div>
                  <h4>起落架支点臂长（相对基准点）</h4>
                  <el-table :data="r.pivots" border size="small">
                    <el-table-column prop="code" label="支点" width="80" />
                    <el-table-column prop="name" label="名称" width="110" />
                    <el-table-column label="臂长">
                      <template #default="{ row }">{{ w(row.armMm, 'MM') }}</template>
                    </el-table-column>
                  </el-table>
                </div>
                <div>
                  <h4>重心包线折线（节点之间线性插值）</h4>
                  <el-table :data="envelopeRows(r)" border size="small">
                    <el-table-column prop="kindName" label="边界" width="80" />
                    <el-table-column label="重量节点">
                      <template #default="{ row }">{{ w(row.weightKg, 'KG') }}</template>
                    </el-table-column>
                    <el-table-column label="重心臂节点">
                      <template #default="{ row }">{{ w(row.armMm, 'MM') }}</template>
                    </el-table-column>
                  </el-table>
                </div>
              </div>
            </el-collapse-item>
          </el-collapse>
        </section>
      </el-tab-pane>

      <!-- ============ 3. 称重记录 ============ -->
      <el-tab-pane label="③ 称重记录" name="weighings">
        <section class="panel">
          <div class="panel-head">
            <h3>实机称重记录（历史结果冻结展示，不被新规则回算覆盖）</h3>
            <div class="panel-extra">
              <el-select v-model="weighingFilter" placeholder="全部飞机" clearable size="small" style="width: 180px"
                         @change="loadWeighings">
                <el-option v-for="a in fleet" :key="a.aircraftId"
                           :label="a.tailNo + ' · ' + a.model" :value="a.aircraftId" />
              </el-select>
              <el-button size="small" type="primary" @click="openWeighForm(null)">登记称重</el-button>
            </div>
          </div>
          <el-table :data="weighings" border stripe size="small">
            <el-table-column prop="weighingNo" label="称重单号" width="150" />
            <el-table-column prop="tailNo" label="机号" width="85" />
            <el-table-column prop="model" label="机型" width="70" />
            <el-table-column prop="weighingDate" label="日期" width="105" />
            <el-table-column prop="scaleNo" label="秤具" width="130" />
            <el-table-column label="引用规则" width="80">
              <template #default="{ row }">
                <el-tag size="small" :type="row.ruleRevisionNo === latestRuleNo(row.model) ? 'success' : 'info'">
                  {{ row.ruleRevisionNo }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="单位制" width="80">
              <template #default="{ row }">{{ row.unitSystem === 'IMPERIAL' ? 'lb/in' : 'kg/mm' }}</template>
            </el-table-column>
            <el-table-column label="最终重量" width="140">
              <template #default="{ row }">{{ w(row.finalWeightKg, 'KG') }}</template>
            </el-table-column>
            <el-table-column label="重心臂" width="130">
              <template #default="{ row }">{{ w(row.cgArmMm, 'MM') }}</template>
            </el-table-column>
            <el-table-column label="判定" width="100">
              <template #default="{ row }">
                <el-tag size="small" :type="envType(row.envelopeStatus)">{{ row.envelopeStatusName }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="复核" width="90">
              <template #default="{ row }">
                <el-tag size="small" :type="row.status === 'CONFIRMED' ? 'success' : 'warning'">{{ row.statusName }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="基线" width="70">
              <template #default="{ row }">
                <el-tag v-if="row.baseline" size="small" type="success">当前</el-tag>
                <span v-else class="muted">—</span>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="90" fixed="right">
              <template #default="{ row }">
                <el-button link type="primary" @click="openWeighingDetail(row)">明细</el-button>
              </template>
            </el-table-column>
          </el-table>
        </section>
      </el-tab-pane>

      <!-- ============ 4. 飞机构型档案（调整链+对比，抽屉） ============ -->
      <el-tab-pane :label="activeAircraft ? `④ 构型调整链 · ${activeAircraft.tailNo}` : '④ 构型调整链'" name="chain">
        <section v-if="!activeAircraft" class="panel">
          <p class="muted">请先在「① 机队基线档案」选择一架飞机。</p>
        </section>
        <template v-else>
          <section class="panel">
            <div class="panel-head">
              <h3>{{ activeAircraft.tailNo }} · {{ activeAircraft.model }} 构型调整链</h3>
              <span class="panel-sub">
                状态：
                <el-tag v-if="activeAircraft.baselineEstablished" type="success" size="small">{{ activeAircraft.baselineLabel }}</el-tag>
                <el-tag v-else type="info" size="small">未建立基线</el-tag>
              </span>
              <div class="panel-extra">
                <el-button size="small" type="primary" :disabled="!activeAircraft.baselineEstablished"
                           @click="openDerive">登记构型调整（从当前版本派生）</el-button>
              </div>
            </div>
            <el-timeline class="chain-line">
              <el-timeline-item v-for="v in activeAircraft.configChain" :key="v.id"
                                :timestamp="fmtTime(v.createdAt)" placement="top"
                                :type="v.current ? 'success' : (v.envelopeStatus === 'OUTSIDE' ? 'danger' : 'info')">
                <div class="chain-card" :class="{ current: v.current, outside: v.envelopeStatus === 'OUTSIDE' }">
                  <div class="chain-title">
                    <b>v{{ v.versionNo }} {{ v.kind === 'ROOT' ? '称重基线' : '构型派生' }}</b>
                    <el-tag size="small" :type="envType(v.envelopeStatus)">{{ v.envelopeStatusName }}</el-tag>
                    <el-tag v-if="v.current" size="small" type="success">当前版本（只可从此版继续派生）</el-tag>
                    <el-tag v-else size="small" type="info">历史（只读，不可分叉）</el-tag>
                    <el-tag size="small">{{ v.ruleRevisionNo }}</el-tag>
                  </div>
                  <div class="chain-metrics">
                    <span>重量 <b>{{ w(v.weightKg, 'KG') }}</b></span>
                    <span>重心臂 <b>{{ w(v.cgArmMm, 'MM') }}</b></span>
                    <span>力矩 <b>{{ v.moment }} kg·mm</b></span>
                    <span v-if="v.violationKindName" class="danger-text">
                      {{ v.violationKindName }} 差值 {{ violationText(v) }}
                    </span>
                  </div>
                  <div v-if="v.changeReason" class="muted">原因：{{ v.changeReason }}
                    <span v-if="v.workCardNo"> · 工卡 {{ v.workCardNo }}</span> · {{ v.operatorName }}
                  </div>
                  <el-table v-if="v.adjustments.length" :data="v.adjustments" border size="small" class="adj-table">
                    <el-table-column label="动作" width="80">
                      <template #default="{ row }">
                        <el-tag size="small" :type="row.action === 'INSTALL' ? 'warning' : 'info'">
                          {{ row.action === 'INSTALL' ? '安装 +' : '拆除 −' }}
                        </el-tag>
                      </template>
                    </el-table-column>
                    <el-table-column prop="itemName" label="设备" min-width="130" />
                    <el-table-column label="单位重量" width="150">
                      <template #default="{ row }">{{ adjW(row.weightReading, row.weightUnit, row.unitWeightKg, 'KG') }}</template>
                    </el-table-column>
                    <el-table-column label="臂长" width="140">
                      <template #default="{ row }">{{ adjW(row.armReading, row.lengthUnit, row.armMm, 'MM') }}</template>
                    </el-table-column>
                    <el-table-column prop="quantity" label="数量" width="60" />
                    <el-table-column label="合计重量" width="140">
                      <template #default="{ row }">{{ w(row.totalWeightKg, 'KG') }}</template>
                    </el-table-column>
                    <el-table-column prop="workCardNo" label="工卡" width="120" />
                    <el-table-column prop="reason" label="原因" min-width="120" show-overflow-tooltip />
                  </el-table>
                </div>
              </el-timeline-item>
            </el-timeline>
          </section>

          <section class="panel">
            <div class="panel-head"><h3>版本对比（同一架飞机链内）</h3></div>
            <div class="diff-bar">
              <el-select v-model="diffFrom" placeholder="起始版本" size="small" style="width: 200px">
                <el-option v-for="v in activeAircraft.configChain" :key="'f'+v.id"
                           :label="`v${v.versionNo} ${v.envelopeStatusName}`" :value="v.id" />
              </el-select>
              <span>→</span>
              <el-select v-model="diffTo" placeholder="目标版本" size="small" style="width: 200px">
                <el-option v-for="v in activeAircraft.configChain" :key="'t'+v.id"
                           :label="`v${v.versionNo} ${v.envelopeStatusName}`" :value="v.id" />
              </el-select>
              <el-button size="small" type="primary" :disabled="!diffFrom || !diffTo" @click="loadDiff">对比</el-button>
            </div>
            <el-table v-if="diff" :data="diffRows" border size="small">
              <el-table-column prop="label" label="指标" width="140" />
              <el-table-column prop="from" label="起始版本" />
              <el-table-column prop="to" label="目标版本" />
              <el-table-column prop="delta" label="差值（统一 kg/mm）" />
            </el-table>
          </section>
        </template>
      </el-tab-pane>

      <!-- ============ 5. 审计事件 ============ -->
      <el-tab-pane label="⑤ 审计事件" name="events">
        <section class="panel">
          <div class="panel-head"><h3>规则发布 / 称重 / 复核 / 基线 / 构型派生 审计时间线（append-only）</h3></div>
          <el-timeline>
            <el-timeline-item v-for="e in events" :key="e.id" :timestamp="fmtTime(e.eventAt)" placement="top">
              <b>{{ e.tailNo || '全机队' }}</b> · {{ eventName(e.eventType) }} · {{ e.operatorName }}
              <div class="muted">{{ e.detail }}</div>
            </el-timeline-item>
          </el-timeline>
        </section>
      </el-tab-pane>
    </el-tabs>

    <!-- ============ 登记称重抽屉（唯一重量录入入口，服务端计算） ============ -->
    <el-drawer v-model="weighFormVisible" title="登记实机称重（服务端统一换算与判定）" size="62%">
      <div class="drawer-body">
        <el-form label-width="92px" size="small">
          <div class="form-row">
            <el-form-item label="飞机" style="flex:1">
              <el-select v-model="wForm.aircraftId" placeholder="选择飞机" style="width:100%">
                <el-option v-for="a in fleet" :key="a.aircraftId"
                           :label="a.tailNo + ' · ' + a.model" :value="a.aircraftId"
                           @click="onPickWeighAircraft(a)" />
              </el-select>
            </el-form-item>
            <el-form-item label="称重日期" style="flex:1">
              <el-date-picker v-model="wForm.weighingDate" type="date" value-format="YYYY-MM-DD" style="width:100%" />
            </el-form-item>
          </div>
          <div class="form-row">
            <el-form-item label="秤具编号" style="flex:1">
              <el-input v-model="wForm.scaleNo" placeholder="如 SCALE-SET-B737-02" />
            </el-form-item>
            <el-form-item label="单位制" style="flex:1">
              <el-radio-group v-model="wForm.unitSystem">
                <el-radio label="METRIC">千克 / 毫米</el-radio>
                <el-radio label="IMPERIAL">磅 / 英寸</el-radio>
              </el-radio-group>
            </el-form-item>
          </div>
          <div class="form-row">
            <el-form-item label="引用规则" style="flex:1">
              <el-select v-model="wForm.ruleRevisionId" placeholder="默认按称重日期取生效版" style="width:100%" clearable>
                <el-option v-for="r in rulesForModel" :key="r.id"
                           :label="`${r.revisionNo}（${r.statusName}，生效 ${r.effectiveDate}）`" :value="r.id" />
              </el-select>
            </el-form-item>
            <el-form-item label="称重人" style="flex:1"><el-input v-model="wForm.weighedBy" /></el-form-item>
          </div>
          <el-form-item label="燃油条件"><el-input v-model="wForm.fuelCondition" /></el-form-item>
          <el-form-item label="滑油条件"><el-input v-model="wForm.oilCondition" /></el-form-item>
        </el-form>

        <el-table :data="wForm.items" border size="small">
          <el-table-column label="类型" width="118">
            <template #default="{ row }">
              <el-select v-model="row.itemType" size="small" @change="onItemType(row)">
                <el-option label="支点" value="PIVOT" />
                <el-option label="燃油" value="FUEL" />
                <el-option label="滑油" value="OIL" />
                <el-option label="临时项" value="TEMP" />
                <el-option label="条件项" value="CONDITION" />
              </el-select>
            </template>
          </el-table-column>
          <el-table-column label="支点/名称" width="130">
            <template #default="{ row }">
              <el-select v-if="row.itemType === 'PIVOT'" v-model="row.pivotCode" size="small">
                <el-option label="NOSE 前起" value="NOSE" />
                <el-option label="LEFT 左主" value="LEFT" />
                <el-option label="RIGHT 右主" value="RIGHT" />
              </el-select>
              <el-input v-else v-model="row.itemName" size="small" placeholder="名称" />
            </template>
          </el-table-column>
          <el-table-column label="秤具" width="110">
            <template #default="{ row }"><el-input v-model="row.scaleNo" size="small" /></template>
          </el-table-column>
          <el-table-column label="毛读(原单位)" width="115">
            <template #default="{ row }"><el-input v-model="row.grossReading" size="small" placeholder="缺失留空" /></template>
          </el-table-column>
          <el-table-column label="皮重" width="85">
            <template #default="{ row }"><el-input v-model="row.tareReading" size="small" /></template>
          </el-table-column>
          <el-table-column label="臂长原文" width="105">
            <template #default="{ row }"><el-input v-model="row.armReading" size="small" :disabled="row.itemType==='PIVOT'" placeholder="支点取规则" /></template>
          </el-table-column>
          <el-table-column label="数量" width="68">
            <template #default="{ row }"><el-input-number v-model="row.quantity" :min="1" :controls="false" size="small" style="width:60px" /></template>
          </el-table-column>
          <el-table-column label="重单位" width="80">
            <template #default="{ row }">
              <el-select v-model="row.weightUnit" size="small"><el-option label="kg" value="KG" /><el-option label="lb" value="LB" /></el-select>
            </template>
          </el-table-column>
          <el-table-column label="长单位" width="80">
            <template #default="{ row }">
              <el-select v-model="row.lengthUnit" size="small"><el-option label="mm" value="MM" /><el-option label="in" value="IN" /></el-select>
            </template>
          </el-table-column>
          <el-table-column label="扣/补" width="92">
            <template #default="{ row }">
              <el-select v-if="row.itemType !== 'PIVOT'" v-model="row.effect" size="small">
                <el-option label="扣除" value="DEDUCT" /><el-option label="补入" value="ADD" />
              </el-select>
            </template>
          </el-table-column>
          <el-table-column label="" width="46">
            <template #default="{ $index }">
              <el-button link type="danger" size="small" @click="wForm.items.splice($index,1)">×</el-button>
            </template>
          </el-table-column>
        </el-table>
        <div class="row-actions">
          <el-button size="small" @click="addItem('PIVOT')">加支点行</el-button>
          <el-button size="small" @click="addItem('FUEL')">加燃油项</el-button>
          <el-button size="small" @click="addItem('OIL')">加滑油项</el-button>
          <el-button size="small" @click="addItem('TEMP')">加临时项</el-button>
          <el-button size="small" type="primary" :loading="previewLoading" @click="doPreview">服务端试算</el-button>
        </div>

        <div v-if="preview" class="preview-box">
          <h4>试算结果（与登记后保存的是同一份计算事实）</h4>
          <calc-result :calc="preview" :unit-mode="unitMode" />
        </div>
      </div>
      <template #footer>
        <el-button @click="weighFormVisible = false">取消</el-button>
        <el-button type="primary" @click="submitWeighing">登记（待复核）</el-button>
      </template>
    </el-drawer>

    <!-- ============ 称重明细抽屉 ============ -->
    <el-drawer v-model="detailVisible" title="称重计算明细（历史结果快照）" size="62%">
      <div v-if="detail" class="drawer-body">
        <el-descriptions :column="3" border size="small">
          <el-descriptions-item label="单号">{{ detail.header.weighingNo }}</el-descriptions-item>
          <el-descriptions-item label="飞机">{{ detail.header.tailNo }} {{ detail.header.model }}</el-descriptions-item>
          <el-descriptions-item label="日期">{{ detail.header.weighingDate }}</el-descriptions-item>
          <el-descriptions-item label="秤具">{{ detail.header.scaleNo }}</el-descriptions-item>
          <el-descriptions-item label="引用规则">
            <el-tag size="small">{{ detail.header.ruleRevisionNo }}</el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="原始单位制">{{ detail.header.unitSystem === 'IMPERIAL' ? '磅/英寸' : '千克/毫米' }}</el-descriptions-item>
          <el-descriptions-item label="燃油条件">{{ detail.fuelCondition }}</el-descriptions-item>
          <el-descriptions-item label="滑油条件">{{ detail.oilCondition }}</el-descriptions-item>
          <el-descriptions-item label="称重/复核">
            {{ detail.header.weighedBy }} / {{ detail.header.reviewedBy || '未复核' }}
          </el-descriptions-item>
        </el-descriptions>

        <calc-result :calc="detail.calc" :unit-mode="unitMode" />

        <h4>明细行（原始读数 / 原单位 + 统一单位快照）</h4>
        <el-table :data="detail.items" border size="small">
          <el-table-column label="类型" width="70">
            <template #default="{ row }">{{ itemTypeName(row.itemType) }}</template>
          </el-table-column>
          <el-table-column label="支点" width="70">
            <template #default="{ row }">{{ row.pivotCode || row.itemName || '—' }}</template>
          </el-table-column>
          <el-table-column label="秤具" width="100">
            <template #default="{ row }">{{ row.scaleNo || '—' }}</template>
          </el-table-column>
          <el-table-column label="毛读(原文)" width="100">
            <template #default="{ row }">{{ row.grossReading || '—' }} {{ row.weightUnit || '' }}</template>
          </el-table-column>
          <el-table-column label="皮重(原文)" width="90">
            <template #default="{ row }">{{ row.tareReading || '0' }} {{ row.weightUnit || '' }}</template>
          </el-table-column>
          <el-table-column label="臂长(原文)" width="110">
            <template #default="{ row }">
              {{ row.itemType === 'PIVOT' ? '按规则' : (row.armReading || '—') }}
              <span v-if="row.itemType !== 'PIVOT'">{{ row.lengthUnit || '' }}</span>
            </template>
          </el-table-column>
          <el-table-column label="净重(kg)" width="120">
            <template #default="{ row }">{{ row.netKg || '—' }}</template>
          </el-table-column>
          <el-table-column label="臂长(mm)" width="100">
            <template #default="{ row }">{{ row.armMm || '—' }}</template>
          </el-table-column>
          <el-table-column label="力矩 kg·mm" min-width="130">
            <template #default="{ row }">{{ row.moment || '—' }}</template>
          </el-table-column>
        </el-table>

        <div class="detail-actions">
          <el-tag :type="envType(detail.header.envelopeStatus)" size="large">
            {{ detail.header.envelopeStatusName }}
            <span v-if="detail.header.violationKindName">：{{ detail.header.violationKindName }}</span>
          </el-tag>
          <el-button v-if="detail.header.status === 'DRAFT'" type="primary" @click="openConfirm(detail.header)">
            不同人复核确认
          </el-button>
          <el-tag v-else type="success" size="small">已复核（{{ detail.header.reviewedBy }}）</el-tag>
        </div>
      </div>
    </el-drawer>

    <!-- ============ 复核对话框 ============ -->
    <el-dialog v-model="confirmVisible" title="复核确认（须与称重人不同）" width="420px">
      <el-form label-width="80px">
        <el-form-item label="称重人">{{ confirmRow.weighedBy }}</el-form-item>
        <el-form-item label="复核人"><el-input v-model="reviewer" /></el-form-item>
        <el-form-item label="判定">
          <el-tag :type="envType(confirmRow.envelopeStatus)">{{ confirmRow.envelopeStatusName }}</el-tag>
          <span v-if="confirmRow.violationKindName" class="danger-text">
            （{{ confirmRow.violationKindName }} {{ confirmRow.violationDelta }}）
          </span>
        </el-form-item>
        <el-form-item>
          <el-checkbox v-model="setBaselineOnConfirm" :disabled="confirmRow.envelopeStatus !== 'INSIDE'">
            复核通过后设为当前称重基线（仅包线内可选）
          </el-checkbox>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="confirmVisible = false">取消</el-button>
        <el-button type="primary" @click="doConfirm">确认复核</el-button>
      </template>
    </el-dialog>

    <!-- ============ 构型派生对话框 ============ -->
    <el-dialog v-model="deriveVisible" title="从当前构型版本派生新版本" width="720px">
      <el-form label-width="82px" size="small">
        <el-form-item label="原因"><el-input v-model="dForm.reason" /></el-form-item>
        <el-form-item label="关联工卡"><el-input v-model="dForm.workCardNo" placeholder="如 WC-2026-0320" /></el-form-item>
        <el-form-item label="操作人"><el-input v-model="dForm.operator" /></el-form-item>
      </el-form>
      <el-table :data="dForm.adjustments" border size="small">
        <el-table-column label="动作" width="100">
          <template #default="{ row }">
            <el-select v-model="row.action" size="small">
              <el-option label="安装" value="INSTALL" /><el-option label="拆除" value="REMOVE" />
            </el-select>
          </template>
        </el-table-column>
        <el-table-column label="设备" min-width="130">
          <template #default="{ row }"><el-input v-model="row.itemName" size="small" /></template>
        </el-table-column>
        <el-table-column label="单位重量" width="110">
          <template #default="{ row }"><el-input v-model="row.weight" size="small" /></template>
        </el-table-column>
        <el-table-column label="臂长" width="100">
          <template #default="{ row }"><el-input v-model="row.arm" size="small" /></template>
        </el-table-column>
        <el-table-column label="重单位" width="75">
          <template #default="{ row }">
            <el-select v-model="row.weightUnit" size="small"><el-option label="kg" value="KG" /><el-option label="lb" value="LB" /></el-select>
          </template>
        </el-table-column>
        <el-table-column label="长单位" width="75">
          <template #default="{ row }">
            <el-select v-model="row.lengthUnit" size="small"><el-option label="mm" value="MM" /><el-option label="in" value="IN" /></el-select>
          </template>
        </el-table-column>
        <el-table-column label="数量" width="70">
          <template #default="{ row }"><el-input-number v-model="row.quantity" :min="1" :controls="false" size="small" style="width:60px" /></template>
        </el-table-column>
        <el-table-column label="原因" min-width="120">
          <template #default="{ row }"><el-input v-model="row.reason" size="small" /></template>
        </el-table-column>
        <el-table-column width="44">
          <template #default="{ $index }">
            <el-button link type="danger" size="small" @click="dForm.adjustments.splice($index,1)">×</el-button>
          </template>
        </el-table-column>
      </el-table>
      <div class="row-actions">
        <el-button size="small" @click="addAdjustment">加调整项</el-button>
      </div>
      <template #footer>
        <el-button @click="deriveVisible = false">取消</el-button>
        <el-button type="primary" @click="doDerive">派生新版本</el-button>
      </template>
    </el-dialog>

    <!-- ============ 规则编辑对话框 ============ -->
    <el-dialog v-model="ruleDialogVisible" :title="ruleDialogTitle" width="760px">
      <el-form label-width="104px" size="small">
        <div v-if="ruleForm.isCreate" class="form-row">
          <el-form-item label="机型" style="flex:1">
            <el-input v-model="ruleForm.model" placeholder="如 B737 / A320 / A330 / ARJ21" />
          </el-form-item>
          <el-form-item label="生效日期" style="flex:1">
            <el-date-picker v-model="ruleForm.content.effectiveDate" type="date" value-format="YYYY-MM-DD" style="width:100%" />
          </el-form-item>
        </div>
        <el-form-item v-else label="生效日期">
          <el-date-picker v-model="ruleForm.content.effectiveDate" type="date" value-format="YYYY-MM-DD" />
        </el-form-item>
        <el-form-item label="基准点"><el-input v-model="ruleForm.content.datumName" /></el-form-item>
        <div class="form-row">
          <el-form-item label="许用总重下界" style="flex:1">
            <el-input v-model="ruleForm.content.minWeight">
              <template #append>
                <el-select v-model="ruleForm.content.weightUnit" style="width:64px"><el-option label="kg" value="KG" /><el-option label="lb" value="LB" /></el-select>
              </template>
            </el-input>
          </el-form-item>
          <el-form-item label="上界" style="flex:1">
            <el-input v-model="ruleForm.content.maxWeight">
              <template #append>{{ ruleForm.content.weightUnit }}</template>
            </el-input>
          </el-form-item>
        </div>
        <el-form-item label="修订说明"><el-input v-model="ruleForm.content.revisionNote" /></el-form-item>
        <el-form-item label="起落架支点">
          <div style="width:100%">
            <el-table :data="ruleForm.content.pivots" border size="small">
              <el-table-column label="编号" width="110">
                <template #default="{ row }"><el-input v-model="row.code" size="small" /></template>
              </el-table-column>
              <el-table-column label="名称" min-width="120">
                <template #default="{ row }"><el-input v-model="row.name" size="small" /></template>
              </el-table-column>
              <el-table-column label="臂长" width="170">
                <template #default="{ row }">
                  <el-input v-model="row.arm" size="small">
                    <template #append>
                      <el-select v-model="row.lengthUnit" style="width:62px"><el-option label="mm" value="MM" /><el-option label="in" value="IN" /></el-select>
                    </template>
                  </el-input>
                </template>
              </el-table-column>
              <el-table-column width="44">
                <template #default="{ $index }">
                  <el-button link type="danger" size="small" @click="ruleForm.content.pivots.splice($index,1)">×</el-button>
                </template>
              </el-table-column>
            </el-table>
            <el-button size="small" @click="ruleForm.content.pivots.push({code:'',name:'',arm:'',lengthUnit:'MM'})">加支点</el-button>
          </div>
        </el-form-item>
        <el-form-item label="包线折线节点">
          <div style="width:100%">
            <el-table :data="ruleForm.content.nodes" border size="small">
              <el-table-column label="边界" width="100">
                <template #default="{ row }">
                  <el-select v-model="row.kind" size="small"><el-option label="前边界" value="FRONT" /><el-option label="后边界" value="AFT" /></el-select>
                </template>
              </el-table-column>
              <el-table-column label="重量" width="160">
                <template #default="{ row }">
                  <el-input v-model="row.weight" size="small">
                    <template #append>
                      <el-select v-model="row.weightUnit" style="width:62px"><el-option label="kg" value="KG" /><el-option label="lb" value="LB" /></el-select>
                    </template>
                  </el-input>
                </template>
              </el-table-column>
              <el-table-column label="重心臂" width="170">
                <template #default="{ row }">
                  <el-input v-model="row.arm" size="small">
                    <template #append>
                      <el-select v-model="row.lengthUnit" style="width:62px"><el-option label="mm" value="MM" /><el-option label="in" value="IN" /></el-select>
                    </template>
                  </el-input>
                </template>
              </el-table-column>
              <el-table-column width="44">
                <template #default="{ $index }">
                  <el-button link type="danger" size="small" @click="ruleForm.content.nodes.splice($index,1)">×</el-button>
                </template>
              </el-table-column>
            </el-table>
            <el-button size="small" @click="addRuleNode('FRONT')">加前边界节点</el-button>
            <el-button size="small" @click="addRuleNode('AFT')">加后边界节点</el-button>
          </div>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="ruleDialogVisible = false">取消</el-button>
        <el-button type="primary" @click="submitRule">{{ ruleForm.isCreate ? (ruleForm.publish ? '创建并生效' : '存草稿') : '修订出新版本' }}</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, h, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox, ElTable, ElTableColumn } from 'element-plus'
import { wbApi } from '../api'

/**
 * 计算结果子组件：原始/统一两种视图共用。
 * 统一值（kg/mm）全部来自后端快照；ORIGINAL 模式下从明细行读原始读数。
 */
const CalcResult = {
  props: ['calc', 'unitMode'],
  setup(props) {
    const w = (v, dim) => {
      if (v === null || v === undefined || v === '') return '—'
      const n = Number(v)
      if (!Number.isFinite(n)) return v
      if (props.unitMode === 'IMPERIAL') {
        return dim === 'KG' ? `${(n / 0.45359237).toFixed(3)} lb` : `${(n / 25.4).toFixed(3)} in`
      }
      return dim === 'KG' ? `${Number(v).toLocaleString()} kg` : `${Number(v).toLocaleString()} mm`
    }
    const envType = (s) => (s === 'INSIDE' ? 'success' : s === 'OUTSIDE' ? 'danger' : 'warning')
    const wuName = (u) => (u === 'LB' ? 'lb' : 'kg')
    const luName = (u) => (u === 'IN' ? 'in' : 'mm')
    // ORIGINAL 模式：明细行里的原始读数/原单位原样展示（支点臂长始终取规则）
    const rawItems = props.calc.items || []
    const corrItems = rawItems.filter((it) => it.itemType !== 'PIVOT')
    const pivotRaw = (row) => {
      const it = rawItems.find((x) => x.itemType === 'PIVOT' && x.pivotCode === row.pivotCode
        && (x.scaleNo || '') === (row.scaleNo || ''))
      return it ? `毛 ${it.grossReading || '缺失'} − 皮 ${it.tareReading || '0'} ${wuName(it.weightUnit)}` : '—'
    }
    return () => h('div', { class: 'calc-result' }, [
      h('div', { class: 'calc-verdict' }, [
        h('span', { class: `env-tag ${props.calc.envelopeStatus}` }, props.calc.envelopeStatusName),
        props.calc.violationKindName
          ? h('span', { class: 'violation' },
              `${props.calc.violationKindName}，差值 ${props.calc.violationDelta} ${props.calc.violationUnit || ''}`)
          : null
      ]),
      props.unitMode === 'ORIGINAL'
        ? h('div', { class: 'orig-note' }, '原始单位视图：读数原样展示；总重/力矩/重心与包线判定见统一单位视图（同一计算事实）')
        : null,
      h('div', { class: 'calc-grid' }, [
        h('div', ['毛重', b(w(props.calc.grossWeightKg, 'KG'))]),
        h('div', ['毛重总力矩', b(props.unitMode === 'ORIGINAL' ? '见统一视图' : props.calc.grossMoment + ' kg·mm')]),
        h('div', ['最终(空重口径)重量', b(w(props.calc.finalWeightKg, 'KG'))]),
        h('div', ['最终总力矩', b(props.unitMode === 'ORIGINAL' ? '见统一视图' : props.calc.finalMoment + ' kg·mm')]),
        h('div', ['重心臂', b(w(props.calc.cgArmMm, 'MM'))]),
        h('div', ['前边界(插值)', w(props.calc.limitFrontMm, 'MM')]),
        h('div', ['后边界(插值)', w(props.calc.limitAftMm, 'MM')])
      ]),
      h(ElTable, { data: props.calc.pivotNets, border: true, size: 'small' }, () => [
        h(ElTableColumn, { prop: 'pivotCode', label: '支点', width: '80' }),
        h(ElTableColumn, { prop: 'scaleNo', label: '秤具', width: '100' }),
        h(ElTableColumn, { label: '净重' }, {
          default: ({ row }) => props.unitMode === 'ORIGINAL'
            ? pivotRaw(row)
            : (props.unitMode === 'IMPERIAL' ? `${row.netLb} lb` : `${row.netKg} kg`)
        }),
        h(ElTableColumn, { label: '臂长' }, {
          default: ({ row }) => props.unitMode === 'ORIGINAL'
            ? '按规则'
            : (props.unitMode === 'IMPERIAL' ? `${row.armIn} in` : `${row.armMm} mm`)
        }),
        h(ElTableColumn, { prop: 'moment', label: '力矩 kg·mm', 'min-width': '130' })
      ]),
      props.calc.corrections && props.calc.corrections.length
        ? h(ElTable, { data: props.calc.corrections, border: true, size: 'small', style: 'margin-top:8px' }, () => [
            h(ElTableColumn, { prop: 'itemType', label: '类型', width: '70' }),
            h(ElTableColumn, { prop: 'itemName', label: '项目', minWidth: '120' }),
            h(ElTableColumn, { label: '扣/补', width: '70' }, {
              default: ({ row }) => row.effect === 'ADD' ? '补入 +' : '扣除 −'
            }),
            h(ElTableColumn, { prop: 'quantity', label: '数量', width: '60' }),
            h(ElTableColumn, { label: '合计重量' }, {
              default: ({ row }, idx) => props.unitMode === 'ORIGINAL'
                ? (() => { const it = corrItems[idx]; return it ? `${it.grossReading} × ${it.quantity || 1} ${wuName(it.weightUnit)}` : '—' })()
                : (props.unitMode === 'IMPERIAL' ? `${row.totalWeightLb} lb` : `${row.totalWeightKg} kg`)
            }),
            h(ElTableColumn, { label: '臂长' }, {
              default: ({ row }, idx) => props.unitMode === 'ORIGINAL'
                ? (() => { const it = corrItems[idx]; return it && it.armReading ? `${it.armReading} ${luName(it.lengthUnit)}` : '—' })()
                : (!row.armMm ? '—' : (props.unitMode === 'IMPERIAL' ? `${row.armIn} in` : `${row.armMm} mm`))
            })
          ])
        : null,
      props.calc.issues && props.calc.issues.length
        ? h('div', { class: 'calc-issues' }, [h('b', '不能给出包线内结论的原因：'),
            ...props.calc.issues.map((i) => h('div', '· ' + i))])
        : null
    ])
  }
}

function b(text) { return h('b', text) }

const tab = ref('fleet')
const unitMode = ref('METRIC')
const overview = ref({})
const fleet = ref([])
const rules = ref([])
const openRules = ref([])
const weighings = ref([])
const weighingFilter = ref(null)
const events = ref([])
const activeAircraft = ref(null)

const weighFormVisible = ref(false)
const previewLoading = ref(false)
const preview = ref(null)
const detailVisible = ref(false)
const detail = ref(null)
const confirmVisible = ref(false)
const confirmRow = ref({})
const reviewer = ref('')
const setBaselineOnConfirm = ref(false)
const deriveVisible = ref(false)
const diffFrom = ref(null)
const diffTo = ref(null)
const diff = ref(null)
const ruleDialogVisible = ref(false)

const wForm = ref(emptyWeighForm())
const dForm = ref(emptyDeriveForm())
const ruleForm = ref(emptyRuleForm())
const ruleDialogTitle = ref('')

function emptyWeighForm() {
  return {
    aircraftId: null, weighingDate: '', scaleNo: '', unitSystem: 'METRIC',
    ruleRevisionId: null, fuelCondition: '', oilCondition: '', weighedBy: '', items: []
  }
}
function emptyDeriveForm() {
  return { reason: '', workCardNo: '', operator: '', adjustments: [] }
}
function emptyRuleForm() {
  return {
    isCreate: true, model: '', publish: true, baseId: null,
    content: {
      effectiveDate: '', datumName: '', minWeight: '', maxWeight: '', weightUnit: 'KG',
      revisionNote: '', pivots: [], nodes: []
    }
  }
}

/** 统一值在三种展示模式下的文案（ORIGINAL 无统一换算时直接显示数值） */
function w(v, dim) {
  if (v === null || v === undefined || v === '') return '—'
  if (unitMode.value === 'IMPERIAL') {
    const n = Number(v)
    return dim === 'KG' ? `${(n / 0.45359237).toFixed(3)} lb` : `${(n / 25.4).toFixed(3)} in`
  }
  return `${Number(v).toLocaleString()} ${dim === 'KG' ? 'kg' : 'mm'}`
}
/**
 * 调整项原文显示：统一/英制模式读后端快照（totalWeightKg/armMm），
 * ORIGINAL 模式直接显示录入原文与原单位 —— 两种展示对应同一计算事实。
 */
function adjW(raw, unit, metricVal, dim) {
  if (unitMode.value === 'ORIGINAL') {
    const u = unit === 'KG' ? 'kg' : unit === 'LB' ? 'lb' : unit === 'MM' ? 'mm' : 'in'
    return `${raw} ${u}`
  }
  if (metricVal === null || metricVal === undefined || metricVal === '') return '—'
  if (unitMode.value === 'IMPERIAL') {
    const n = Number(metricVal)
    return dim === 'KG' ? `${(n / 0.45359237).toFixed(3)} lb` : `${(n / 25.4).toFixed(3)} in`
  }
  return `${metricVal} ${dim === 'KG' ? 'kg' : 'mm'}`
}

function envType(s) { return s === 'INSIDE' ? 'success' : s === 'OUTSIDE' ? 'danger' : 'warning' }
function ruleStatusName(s) { return s === 'EFFECTIVE' ? '生效中' : s === 'DRAFT' ? '草稿' : '已冻结' }
function itemTypeName(t) { return { PIVOT: '支点', FUEL: '燃油', OIL: '滑油', TEMP: '临时项', CONDITION: '条件项' }[t] || t }
function eventName(t) {
  return { RULE_PUBLISH: '规则发布', WEIGHING_CREATE: '登记称重', WEIGHING_CONFIRM: '称重复核',
    BASELINE_SET: '设定基线', CONFIG_DERIVE: '构型派生' }[t] || t
}
function fmtTime(t) { return t ? t.replace('T', ' ').substring(0, 16) : '' }
function violationText(v) {
  const unit = v.violationKindName && v.violationKindName.includes('重') ? 'kg' : 'mm'
  return `${v.violationDelta} ${unit}`
}
function latestRuleNo(model) {
  const eff = rules.value.filter((r) => r.model === model && r.status === 'EFFECTIVE')
  return eff.length ? eff[0].revisionNo : null
}

const rulesForModel = computed(() => {
  const a = fleet.value.find((x) => x.aircraftId === wForm.value.aircraftId)
  if (!a) return []
  return rules.value.filter((r) => r.model === a.model)
})

function envelopeRows(r) {
  const rows = []
  r.frontNodes.forEach((n) => rows.push({ kind: 'FRONT', kindName: '前边界', ...n }))
  r.aftNodes.forEach((n) => rows.push({ kind: 'AFT', kindName: '后边界', ...n }))
  return rows
}

const diffRows = computed(() => {
  if (!diff.value) return []
  const f = diff.value.from, t = diff.value.to
  return [
    { label: '重量', from: w(f.weightKg, 'KG'), to: w(t.weightKg, 'KG'), delta: `${diff.value.deltaWeightKg} kg` },
    { label: '总力矩', from: `${f.moment} kg·mm`, to: `${t.moment} kg·mm`, delta: `${diff.value.deltaMoment} kg·mm` },
    { label: '重心臂', from: w(f.cgArmMm, 'MM'), to: w(t.cgArmMm, 'MM'), delta: `${diff.value.deltaCgMm} mm` },
    { label: '包线判定', from: f.envelopeStatusName, to: t.envelopeStatusName, delta: '' }
  ]
})

async function reloadAll() {
  const [ov, fl, rs, ws, ev] = await Promise.all([
    wbApi.overview(), wbApi.fleet(), wbApi.rules(), wbApi.weighings(weighingFilter.value), wbApi.events()
  ])
  overview.value = ov
  fleet.value = fl
  rules.value = rs
  weighings.value = ws
  events.value = ev
  if (activeAircraft.value) await openAircraftById(activeAircraft.value.aircraftId)
}

async function loadWeighings() {
  weighings.value = await wbApi.weighings(weighingFilter.value)
}

async function openAircraft(row) {
  await openAircraftById(row.aircraftId)
  tab.value = 'chain'
}
async function openAircraftById(id) {
  activeAircraft.value = await wbApi.aircraft(id)
  diffFrom.value = null
  diffTo.value = null
  diff.value = null
}

// ---------- 称重表单 ----------
function defaultUnit() {
  const imperial = wForm.value.unitSystem === 'IMPERIAL'
  return { weightUnit: imperial ? 'LB' : 'KG', lengthUnit: imperial ? 'IN' : 'MM' }
}
function addItem(type) {
  const u = defaultUnit()
  const row = { itemType: type, pivotCode: type === 'PIVOT' ? 'NOSE' : null, scaleNo: '',
    itemName: '', grossReading: '', tareReading: type === 'PIVOT' ? '0' : '',
    armReading: '', quantity: 1, weightUnit: u.weightUnit, lengthUnit: u.lengthUnit,
    effect: type === 'FUEL' || type === 'OIL' ? 'DEDUCT' : (type === 'TEMP' ? 'ADD' : null) }
  wForm.value.items.push(row)
}
function onItemType(row) {
  if (row.itemType === 'PIVOT') { row.itemName = null; row.effect = null; row.pivotCode = row.pivotCode || 'NOSE' }
  else { row.pivotCode = null; row.effect = row.effect || (row.itemType === 'FUEL' || row.itemType === 'OIL' ? 'DEDUCT' : 'ADD') }
}
function onPickWeighAircraft() { /* unit/rule 默认即可 */ }

function openWeighForm(row) {
  wForm.value = emptyWeighForm()
  preview.value = null
  if (row) {
    wForm.value.aircraftId = row.aircraftId
    wForm.value.unitSystem = 'METRIC'
  }
  // 预置三个支点空行，强制从唯一入口逐秤录入
  addItem('PIVOT'); addItem('PIVOT'); addItem('PIVOT')
  wForm.value.items[0].pivotCode = 'NOSE'
  wForm.value.items[1].pivotCode = 'LEFT'
  wForm.value.items[2].pivotCode = 'RIGHT'
  weighFormVisible.value = true
}

async function doPreview() {
  previewLoading.value = true
  try {
    preview.value = await wbApi.preview({
      aircraftId: wForm.value.aircraftId,
      ruleRevisionId: wForm.value.ruleRevisionId,
      unitSystem: wForm.value.unitSystem,
      items: wForm.value.items
    })
  } finally {
    previewLoading.value = false
  }
}

async function submitWeighing() {
  const data = await wbApi.createWeighing(wForm.value)
  ElMessage.success(`称重 ${data.header.weighingNo} 已登记，待复核`)
  weighFormVisible.value = false
  await reloadAll()
  openWeighingDetailById(data.header.id)
}

async function openWeighingDetail(row) {
  await openWeighingDetailById(row.id)
}
async function openWeighingDetailById(id) {
  detail.value = await wbApi.weighing(id)
  detailVisible.value = true
}

function openConfirm(row) {
  confirmRow.value = row
  reviewer.value = ''
  setBaselineOnConfirm.value = false
  confirmVisible.value = true
}
async function doConfirm() {
  if (!reviewer.value.trim()) { ElMessage.warning('请填写复核人'); return }
  await wbApi.confirm(confirmRow.value.id, { reviewedBy: reviewer.value.trim(), setBaseline: setBaselineOnConfirm.value })
  ElMessage.success('复核完成')
  confirmVisible.value = false
  detailVisible.value = false
  await reloadAll()
  tab.value = 'weighings'
}

// ---------- 构型派生 ----------
function openDerive() {
  dForm.value = emptyDeriveForm()
  addAdjustment()
  deriveVisible.value = true
}
function addAdjustment() {
  dForm.value.adjustments.push({ action: 'INSTALL', itemName: '', weight: '', arm: '',
    weightUnit: 'KG', lengthUnit: 'MM', quantity: 1, reason: '' })
}
async function doDerive() {
  const v = await wbApi.derive(activeAircraft.value.aircraftId, dForm.value)
  ElMessage.success(`已派生 v${v.versionNo}：${v.envelopeStatusName}`
    + (v.violationKindName ? `（${v.violationKindName} 差值 ${v.violationDelta}）` : ''))
  deriveVisible.value = false
  await openAircraftById(activeAircraft.value.aircraftId)
}
async function loadDiff() {
  diff.value = await wbApi.diff(activeAircraft.value.aircraftId, diffFrom.value, diffTo.value)
}

// ---------- 规则 ----------
function openRuleCreate() {
  ruleForm.value = emptyRuleForm()
  ruleForm.value.isCreate = true
  ruleForm.value.content.pivots = [
    { code: 'NOSE', name: '前起落架', arm: '', lengthUnit: 'MM' },
    { code: 'LEFT', name: '左主起落架', arm: '', lengthUnit: 'MM' },
    { code: 'RIGHT', name: '右主起落架', arm: '', lengthUnit: 'MM' }]
  ruleForm.value.content.nodes = []
  ruleDialogTitle.value = '新增机型称重规则（首版 R0）'
  ruleDialogVisible.value = true
}
function openRuleEdit(r) {
  ruleForm.value = { isCreate: false, publish: false, baseId: r.id, model: r.model,
    content: ruleContentFromView(r), mode: 'DRAFT' }
  ruleDialogTitle.value = `编辑草稿 ${r.model} ${r.revisionNo}（仅草稿可改）`
  ruleDialogVisible.value = true
}
function openRevise(r) {
  ruleForm.value = { isCreate: false, publish: true, baseId: r.id, model: r.model,
    content: ruleContentFromView(r), mode: 'REVISE' }
  ruleForm.value.content.effectiveDate = ''
  ruleDialogTitle.value = `基于 ${r.model} ${r.revisionNo} 修订新版本（旧版冻结）`
  ruleDialogVisible.value = true
}
async function openPublish(r) {
  const { value } = await ElMessageBox.prompt('发布操作人', `发布 ${r.model} ${r.revisionNo}`, {
    confirmButtonText: '发布生效', inputValue: '质量员'
  }).catch(() => ({}))
  if (value) {
    await wbApi.publishRule(r.id, value)
    ElMessage.success('规则已生效')
    await reloadAll()
  }
}
function ruleContentFromView(r) {
  return {
    effectiveDate: r.effectiveDate, datumName: r.datumName,
    minWeight: r.minWeightKg, maxWeight: r.maxWeightKg, weightUnit: 'KG',
    revisionNote: r.revisionNote,
    pivots: r.pivots.map((p) => ({ code: p.code, name: p.name, arm: String(p.armMm), lengthUnit: 'MM' })),
    nodes: envelopeRows(r).map((n) => ({ kind: n.kind, weight: String(n.weightKg), arm: String(n.armMm),
      weightUnit: 'KG', lengthUnit: 'MM' }))
  }
}
function addRuleNode(kind) {
  ruleForm.value.content.nodes.push({ kind, weight: '', arm: '', weightUnit: 'KG', lengthUnit: 'MM' })
}
async function submitRule() {
  const f = ruleForm.value
  if (f.isCreate) {
    await wbApi.createRule({ model: f.model.trim().toUpperCase(), publish: f.publish, content: f.content })
    ElMessage.success('规则已创建')
  } else if (f.mode === 'DRAFT') {
    await wbApi.updateDraft(f.baseId, { content: f.content })
    ElMessage.success('草稿已更新')
  } else {
    await wbApi.reviseRule(f.baseId, { publish: true, content: f.content })
    ElMessage.success('新版本已建立并生效，旧版已冻结')
  }
  ruleDialogVisible.value = false
  await reloadAll()
}

onMounted(reloadAll)
</script>

<style scoped>
.wb-overview { display: flex; gap: 12px; flex-wrap: wrap; }
.wb-chip { background: #f4f8fc; border: 1px solid #dbe7f2; border-radius: 8px; padding: 8px 16px;
  display: flex; flex-direction: column; align-items: center; min-width: 104px; }
.wb-chip b { font-size: 22px; color: #1565c0; }
.wb-chip span { font-size: 12px; color: #607d8b; }
.wb-chip.warn b { color: #ed6c02; }
.wb-chip.danger b { color: #c62828; }
.muted { color: #90a4ae; }
.danger-text { color: #c62828; font-weight: 600; }
.wb-tabs { margin-top: 8px; }
.rule-head { display: flex; gap: 14px; align-items: center; flex-wrap: wrap; margin-bottom: 10px; }
.rule-cols { display: grid; grid-template-columns: 1fr 1fr; gap: 16px; }
.rule-cols h4 { margin: 4px 0; font-size: 13px; color: #455a64; }
.chain-card { background: #fff; border: 1px solid #e3ebf3; border-radius: 8px; padding: 10px 12px; }
.chain-card.current { border-color: #2e7d32; box-shadow: 0 0 0 2px rgba(46,125,50,.12); }
.chain-card.outside { border-color: #c62828; }
.chain-title { display: flex; gap: 8px; align-items: center; flex-wrap: wrap; margin-bottom: 6px; }
.chain-metrics { display: flex; gap: 18px; flex-wrap: wrap; font-size: 13px; margin-bottom: 4px; }
.adj-table { margin-top: 8px; }
.diff-bar { display: flex; gap: 10px; align-items: center; margin-bottom: 10px; }
.drawer-body { padding: 0 4px; }
.form-row { display: flex; gap: 10px; }
.row-actions { margin: 10px 0; display: flex; gap: 8px; flex-wrap: wrap; }
.preview-box { margin-top: 12px; border-top: 1px dashed #cdd9e5; padding-top: 10px; }
.detail-actions { margin-top: 14px; display: flex; gap: 12px; align-items: center; }
.calc-result { background: #f8fbff; border: 1px solid #d7e5f3; border-radius: 8px; padding: 12px; margin: 10px 0; }
.calc-verdict { display: flex; gap: 12px; align-items: center; margin-bottom: 10px; }
.env-tag { padding: 4px 14px; border-radius: 14px; font-weight: 700; color: #fff; }
.env-tag.INSIDE { background: #2e7d32; }
.env-tag.OUTSIDE { background: #c62828; }
.env-tag.UNDETERMINED { background: #ed6c02; }
.violation { color: #c62828; font-weight: 600; }
.calc-grid { display: grid; grid-template-columns: repeat(4, 1fr); gap: 8px; margin-bottom: 10px; }
.calc-grid div { background: #fff; border: 1px solid #e3ebf3; border-radius: 6px; padding: 6px 10px;
  font-size: 12px; color: #607d8b; display: flex; flex-direction: column; }
.calc-grid b { font-size: 14px; color: #263238; margin-top: 2px; }
.calc-issues { margin-top: 8px; background: #fff7e6; border: 1px solid #ffd591; border-radius: 6px;
  padding: 8px 10px; color: #ad4e00; font-size: 13px; }
.orig-note { margin-bottom: 8px; color: #607d8b; font-size: 12px; }
</style>
