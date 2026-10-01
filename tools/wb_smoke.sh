#!/bin/bash
# 模块八 HTTP 端到端冒烟（wbsmoke profile，数据由真实 service 流程构造）
set -u
BASE=http://127.0.0.1:8092/api/weight-balance
PASS=0; FAIL=0
chk() { # desc expected actual
  if [ "$2" == "$3" ]; then echo "PASS: $1"; PASS=$((PASS+1));
  else echo "FAIL: $1 | expected=$2 actual=$3"; FAIL=$((FAIL+1)); fi
}
J() { python3 -c "import sys,json;d=json.load(sys.stdin);print($1)"; }

echo "=== 1. 总览：无基线飞机计数 ==="
ov=$(curl -s $BASE/overview)
chk "http ok" "True" "$(echo "$ov" | J 'd["ok"]')"
echo "$ov" | J '"aircraft=%s baseline=%s nobase=%s outside=%s effModels=%s"%(d["data"]["aircraftTotal"],d["data"]["baselineCount"],d["data"]["noBaselineCount"],d["data"]["outsideCount"],d["data"]["effectiveRuleModelCount"])'

echo "=== 2. 机队：B-SMK3 无规则无基线 ==="
fleet=$(curl -s $BASE/fleet)
chk "B-SMK3 baselineLabel" "未建立基线" "$(echo "$fleet" | J '[a for a in d["data"] if a["tailNo"]=="B-SMK3"][0]["baselineLabel"]')"
chk "B-SMK3 无猜测CG" "None" "$(echo "$fleet" | J 'str([a for a in d["data"] if a["tailNo"]=="B-SMK3"][0]["currentCgArmMm"])')"
chk "B-SMK3 空构型链" "0" "$(echo "$fleet" | J 'str(len([a for a in d["data"] if a["tailNo"]=="B-SMK3"][0]["configChain"]))')"
chk "B-SMK1 当前基线 v1(偏后)" "1" "$(echo "$fleet" | J 'str([a for a in d["data"] if a["tailNo"]=="B-SMK1"][0]["currentVersionNo"])')"
chk "B-SMK1 当前包线外" "包线外" "$(echo "$fleet" | J '[a for a in d["data"] if a["tailNo"]=="B-SMK1"][0]["currentEnvelopeStatusName"]')"

echo "=== 3. 规则版本：R0 冻结 / R1 生效 ==="
rules=$(curl -s $BASE/rules)
chk "R0 状态" "SUPERSEDED" "$(echo "$rules" | J '[r for r in d["data"] if r["revisionNo"]=="R0"][0]["status"]')"
chk "R1 状态" "EFFECTIVE" "$(echo "$rules" | J '[r for r in d["data"] if r["revisionNo"]=="R1"][0]["status"]')"
r1=$(echo "$rules" | J '[r for r in d["data"] if r["revisionNo"]=="R1"][0]["id"]')
r0=$(echo "$rules" | J '[r for r in d["data"] if r["revisionNo"]=="R0"][0]["id"]')
echo "R0 id=$r0 R1 id=$r1"
# 生效版不可改
echo "$(curl -s -X POST $BASE/rules/$r0/draft -H 'Content-Type: application/json' -d '{"content":{}}')" | J 'str(d["ok"])' | grep -q False && { echo "PASS: 冻结版不可改"; PASS=$((PASS+1)); } || { echo "FAIL: 冻结版可改"; FAIL=$((FAIL+1)); }

echo "=== 4. 飞机构型链与历史结果 ==="
ac1=$(echo "$fleet" | J '[a for a in d["data"] if a["tailNo"]=="B-SMK1"][0]["aircraftId"]')
st=$(curl -s $BASE/aircraft/$ac1)
chk "链版本数" "2" "$(echo "$st" | J 'str(len(d["data"]["configChain"]))')"
chk "v0 INSIDE" "INSIDE" "$(echo "$st" | J 'd["data"]["configChain"][0]["envelopeStatus"]')"
chk "v0 引用R0? 不，R1" "R1" "$(echo "$st" | J 'd["data"]["configChain"][0]["ruleRevisionNo"]')"
chk "v1 OUTSIDE" "OUTSIDE" "$(echo "$st" | J 'd["data"]["configChain"][1]["envelopeStatus"]')"
chk "v1 越界方向" "偏后（超出后重心边界）" "$(echo "$st" | J 'd["data"]["configChain"][1]["violationKindName"]')"
chk "v1 越界差值" "35.983" "$(echo "$st" | J 'd["data"]["configChain"][1]["violationDelta"]')"
chk "v1 是当前版本" "True" "$(echo "$st" | J 'str(d["data"]["configChain"][1]["current"])')"
chk "v0 不可再分叉" "False" "$(echo "$st" | J 'str(d["data"]["configChain"][0]["current"])')"

echo "=== 5. 英制称重原始单位与统一快照 ==="
ac2=$(echo "$fleet" | J '[a for a in d["data"] if a["tailNo"]=="B-SMK2"][0]["aircraftId"]')
wid=$(curl -s "$BASE/weighings?aircraftId=$ac2" | J 'd["data"][0]["id"]')
det=$(curl -s $BASE/weighings/$wid)
chk "原始毛读保留" "15873" "$(echo "$det" | J 'd["data"]["items"][0]["grossReading"]')"
chk "原始单位 LB" "LB" "$(echo "$det" | J 'd["data"]["items"][0]["weightUnit"]')"
chk "换算kg快照" "7199.871689" "$(echo "$det" | J 'str(d["data"]["items"][0]["netKg"])')"
chk "最终重量kg" "43799.332839" "$(echo "$det" | J 'd["data"]["calc"]["finalWeightKg"]')"
chk "重心臂mm" "7799.546" "$(echo "$det" | J 'd["data"]["calc"]["cgArmMm"]')"
chk "判定INSIDE" "包线内" "$(echo "$det" | J 'd["data"]["calc"]["envelopeStatusName"]')"

echo "=== 6. 试算非法输入：缺读数/非法支点/非法数值 均非包线内 ==="
mkprev() { curl -s -X POST $BASE/weighings/preview -H 'Content-Type: application/json' -d "$1"; }
BASE2=$BASE
MISSING='{"aircraftId":'$ac2',"ruleRevisionId":'$r1',"unitSystem":"METRIC","items":[{"itemType":"PIVOT","pivotCode":"NOSE","grossReading":"7200","weightUnit":"KG","lengthUnit":"MM"},{"itemType":"PIVOT","pivotCode":"LEFT","grossReading":"18725","weightUnit":"KG","lengthUnit":"MM"},{"itemType":"FUEL","itemName":"油","grossReading":"800","armReading":"8200","weightUnit":"KG","lengthUnit":"MM","effect":"DEDUCT"}]}'
chk "缺RIGHT->UNDETERMINED" "UNDETERMINED" "$(mkprev "$MISSING" | J 'd["data"]["envelopeStatus"]')"
BADCODE="${MISSING/\"NOSE\"/\"TAIL\"}"
chk "非法支点->UNDETERMINED" "UNDETERMINED" "$(mkprev "$BADCODE" | J 'd["data"]["envelopeStatus"]')"
NAN='{"aircraftId":'$ac2',"ruleRevisionId":'$r1',"items":[{"itemType":"PIVOT","pivotCode":"NOSE","grossReading":"abc","weightUnit":"KG","lengthUnit":"MM"},{"itemType":"PIVOT","pivotCode":"LEFT","grossReading":"18725","weightUnit":"KG","lengthUnit":"MM"},{"itemType":"PIVOT","pivotCode":"RIGHT","grossReading":"18725","weightUnit":"KG","lengthUnit":"MM"}]}'
chk "非法数值->UNDETERMINED" "UNDETERMINED" "$(mkprev "$NAN" | J 'd["data"]["envelopeStatus"]')"
chk "非法数值原因" "True" "$(mkprev "$NAN" | J 'str(any("非法数值" in i for i in d["data"]["issues"]))')"
# 总重越界
HEAVY='{"aircraftId":'$ac2',"ruleRevisionId":'$r1',"items":[{"itemType":"PIVOT","pivotCode":"NOSE","grossReading":"9000","weightUnit":"KG","lengthUnit":"MM"},{"itemType":"PIVOT","pivotCode":"LEFT","grossReading":"24000","weightUnit":"KG","lengthUnit":"MM"},{"itemType":"PIVOT","pivotCode":"RIGHT","grossReading":"24000","weightUnit":"KG","lengthUnit":"MM"}]}'
hv=$(mkprev "$HEAVY")
chk "超总重->OUTSIDE" "OUTSIDE" "$(echo "$hv" | J 'd["data"]["envelopeStatus"]')"
chk "越界方向OVER_WEIGHT" "OVER_WEIGHT" "$(echo "$hv" | J 'd["data"]["violationKind"]')"

echo "=== 7. 重复试算完全一致（精度） ==="
GOOD='{"aircraftId":'$ac2',"ruleRevisionId":'$r1',"items":[{"itemType":"PIVOT","pivotCode":"NOSE","grossReading":"7200","weightUnit":"KG","lengthUnit":"MM"},{"itemType":"PIVOT","pivotCode":"LEFT","grossReading":"18725","weightUnit":"KG","lengthUnit":"MM"},{"itemType":"PIVOT","pivotCode":"RIGHT","grossReading":"18725","weightUnit":"KG","lengthUnit":"MM"},{"itemType":"FUEL","itemName":"油","grossReading":"800","armReading":"8200","weightUnit":"KG","lengthUnit":"MM","effect":"DEDUCT"},{"itemType":"OIL","itemName":"滑","grossReading":"50","armReading":"7800","weightUnit":"KG","lengthUnit":"MM","effect":"DEDUCT"}]}'
a=$(mkprev "$GOOD"); b=$(mkprev "$GOOD"); c=$(mkprev "$GOOD")
chk "重复试算一致" "True" "$([ "$a" == "$b" ] && [ "$b" == "$c" ] && echo True || echo False)"
echo "$a" | J '"finalW=%s cg=%s front=%s aft=%s"%(d["data"]["finalWeightKg"],d["data"]["cgArmMm"],d["data"]["limitFrontMm"],d["data"]["limitAftMm"])'

echo "=== 8. 只能从当前版本派生 + 跨机拒绝 + 英制调整换算 ==="
# 再派生一版（当前=v1），英制输入拆除后回到包线内
DERIVE='{"reason":"英制拆除恢复","workCardNo":"WC-SMK-9","operator":"测试员","adjustments":[{"action":"REMOVE","itemName":"后货舱天线","weight":"6613.867866","arm":"464.566929","weightUnit":"LB","lengthUnit":"IN","quantity":1,"reason":"恢复"}]}'
dv=$(curl -s -X POST $BASE/aircraft/$ac1/config/derive -H 'Content-Type: application/json' -d "$DERIVE")
chk "派生回包线内" "INSIDE" "$(echo "$dv" | J 'd["data"]["envelopeStatus"]')"
chk "派生版本v2" "2" "$(echo "$dv" | J 'str(d["data"]["versionNo"])')"
# 跨机对比拒绝
v0=$(echo "$st" | J 'd["data"]["configChain"][0]["id"]')
chk "跨机diff拒绝400语义" "False" "$(curl -s "$BASE/aircraft/$ac2/config/diff?fromId=$v0&toId=$v0" | J 'str(d["ok"])')"

echo "=== 9. 包线外称重不可设基线 + 双人复核约束 ==="
HW='{"aircraftId":'$ac1',"weighingDate":"2026-09-20","scaleNo":"X","unitSystem":"METRIC","weighedBy":"称证人","items":[{"itemType":"PIVOT","pivotCode":"NOSE","grossReading":"9000","weightUnit":"KG","lengthUnit":"MM"},{"itemType":"PIVOT","pivotCode":"LEFT","grossReading":"24000","weightUnit":"KG","lengthUnit":"MM"},{"itemType":"PIVOT","pivotCode":"RIGHT","grossReading":"24000","weightUnit":"KG","lengthUnit":"MM"}]}'
hwid=$(curl -s -X POST $BASE/weighings -H 'Content-Type: application/json' -d "$HW" | J 'd["data"]["header"]["id"]')
echo "包线外称重id=$hwid"
chk "同人复核被拒" "False" "$(curl -s -X POST $BASE/weighings/$hwid/confirm -H 'Content-Type: application/json' -d '{"reviewedBy":"称证人"}' | J 'str(d["ok"])')"
curl -s -X POST $BASE/weighings/$hwid/confirm -H 'Content-Type: application/json' -d '{"reviewedBy":"复核员乙"}' >/dev/null
chk "包线外设基线被拒" "False" "$(curl -s -X POST $BASE/weighings/$hwid/baseline -H 'Content-Type: application/json' -d '{"operator":"复核员乙"}' | J 'str(d["ok"])')"

echo "=== 10. 版本对比差值 ==="
st2=$(curl -s $BASE/aircraft/$ac1)
from=$(echo "$st2" | J 'd["data"]["configChain"][0]["id"]')
to=$(echo "$st2" | J 'd["data"]["configChain"][1]["id"]')
diff=$(curl -s "$BASE/aircraft/$ac1/config/diff?fromId=$from&toId=$to")
chk "diff 重量差" "3000" "$(echo "$diff" | J 'd["data"]["deltaWeightKg"]')"

echo
echo "RESULT pass=$PASS fail=$FAIL"
[ $FAIL -eq 0 ]
