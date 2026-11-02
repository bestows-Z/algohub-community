<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { notifyError } from '@/api/client'
import { loadReportCycles, loadMissingReports, loadReminderOverview, currentReportWeek, reportLabels, mailLabels, cycleLabels, reportTime, type ReportScope, type ReportCycle, type MissingReport } from '@/api/weeklyReports'
const props = defineProps<{ scope: ReportScope; manager: boolean }>()
const emit = defineEmits<{ failed: [error: unknown] }>()
const cycles = ref<ReportCycle[]>([]), rows = ref<MissingReport[]>([]), overview = ref<{ status: string; reason: string | null; count: number }[]>([])
const week = ref(currentReportWeek()), year = ref<number>(), query = ref(''), className = ref(''), late = ref(''), reminder = ref(''), page = ref(1), total = ref(0), loading = ref(false), error = ref('')
const selectedCycle = computed(() => cycles.value.find(c => c.weekStart === week.value))
let generation = 0, request = 0
function effectiveScope() { return { schoolId: props.scope.schoolId, enrollmentYear: props.scope.enrollmentYear ?? year.value } }
async function load() {
  const token = generation, req = ++request, scope = effectiveScope(); loading.value = true; error.value = ''
  rows.value = []; overview.value = []; total.value = 0
  try {
    const [history, result, counts] = await Promise.all([
      loadReportCycles(scope), loadMissingReports(scope, { week: week.value, className: className.value.trim(), q: query.value.trim(), late: late.value, reminder: reminder.value, page: page.value }),
      props.manager ? loadReminderOverview(props.scope, week.value) : Promise.resolve([])
    ])
    if (token !== generation || req !== request) return
    cycles.value = history; rows.value = result.rows; total.value = result.total; overview.value = counts
  } catch (e) { if (token === generation && req === request) { cycles.value = []; error.value = '名单读取失败，请刷新管理权限或重试'; emit('failed', e); notifyError(e) } }
  finally { if (token === generation && req === request) loading.value = false }
}
watch(() => [props.scope.schoolId, props.scope.enrollmentYear], () => {
  generation++; request++; cycles.value = []; rows.value = []; overview.value = []; page.value = 1; week.value = currentReportWeek(); year.value = undefined
  void load()
}, { immediate: true })
onBeforeUnmount(() => { generation++; request++ })
function search() { page.value = 1; void load() }
</script>
<template>
  <div>
    <el-form label-position="top" class="flex flex-wrap gap-x-3" @submit.prevent="search">
      <el-form-item label="周次（周一日期）"><el-date-picker v-model="week" type="date" value-format="YYYY-MM-DD" :clearable="false" class="max-w-full !w-44" @change="search" /></el-form-item>
      <el-form-item v-if="manager && !scope.enrollmentYear" label="入学年级（留空为全部）"><el-input-number v-model="year" :min="2000" :max="2100" :precision="0" /></el-form-item>
      <el-form-item label="班级"><el-input v-model="className" maxlength="100" class="!w-36" /></el-form-item>
      <el-form-item label="姓名、用户名或学号"><el-input v-model="query" maxlength="100" class="!w-48" /></el-form-item>
      <el-form-item label="补交状态"><el-select v-model="late" class="!w-32"><el-option label="全部" value="" /><el-option label="已补交" value="YES" /><el-option label="未补交" value="NO" /></el-select></el-form-item>
      <el-form-item label="邮件状态"><el-select v-model="reminder" class="!w-36"><el-option label="全部" value="" /><el-option v-for="(label,value) in mailLabels" :key="value" :value="value" :label="label" /></el-select></el-form-item>
      <el-form-item label="查询"><el-button native-type="submit" :loading="loading">查询名单</el-button></el-form-item>
    </el-form>
    <p v-if="error" role="alert" class="text-danger-ink">{{ error }} <el-button text @click="load">重试</el-button></p>
    <el-select v-if="cycles.length" v-model="week" aria-label="最近统计周次" class="mb-4 max-w-full !w-80" @change="search"><el-option v-for="cycle in cycles" :key="cycle.id" :value="cycle.weekStart" :label="cycle.weekStart + ' · ' + cycleLabels[cycle.status]" /></el-select>
    <p v-if="selectedCycle" class="text-sm text-muted">{{ cycleLabels[selectedCycle.status] }} · 截止 {{ reportTime(selectedCycle.cutoffAt) }}<template v-if="selectedCycle.status === 'COMPLETE'"> · <template v-if="manager && !year">应交 {{ selectedCycle.eligibleCount }}，已交 {{ selectedCycle.submittedCount }}，</template>本范围未交 {{ selectedCycle.missingCount }} 人 · 生成 {{ reportTime(selectedCycle.generatedAt) }}</template><span v-if="selectedCycle.error"> · {{ selectedCycle.error }}</span></p>
    <p v-if="manager && overview.length" class="text-sm text-muted">提醒处理概况（按人数）：<span v-for="item in overview" :key="item.status + item.reason" class="mr-3">{{ item.reason || mailLabels[item.status] }} {{ item.count }}</span></p>
    <p class="text-sm text-muted">名单保留截止时点的情况，补交另行标注；不表示学生已经收到邮件。</p>
    <div v-loading="loading" class="min-h-20">
      <el-empty v-if="!loading && !rows.length && !error" :description="selectedCycle?.status === 'COMPLETE' ? '已完成统计，当前筛选范围没有未提交记录' : '该周尚未完成统计，没有可展示的名单'" />
      <article v-for="row in rows" :key="row.id" class="border-b border-line py-4">
        <div class="flex flex-wrap items-center gap-2"><strong class="text-ink break-words">{{ row.displayName }}</strong><el-tag>{{ reportLabels[row.cutoffStatus] }}</el-tag><el-tag v-if="row.lateAt" type="success">已补交</el-tag></div>
        <p class="text-sm text-muted break-words">{{ row.enrollmentYear ? row.enrollmentYear + ' 级' : '年级未填写' }} · {{ row.className || '班级未填写' }} · 学号 {{ row.studentNo || '未填写' }} · {{ row.username }}</p>
        <el-collapse><el-collapse-item title="查看提交与提醒详情">
          <p>截止时：{{ reportLabels[row.cutoffStatus] }}；{{ row.cutoffStatus === 'DRAFT' ? '有草稿' : '无待提交草稿或已退回' }}；最后编辑 {{ reportTime(row.lastEditedAt) }}</p>
          <p>当前：{{ row.currentStatus ? reportLabels[row.currentStatus] : '未创建' }} · 最近提交 {{ reportTime(row.submittedAt) }} · 首次补交 {{ reportTime(row.lateAt) }}</p>
          <p>截止时提醒：{{ mailLabels[row.reminderStatus || 'NONE'] }} · {{ row.reminderNote || '无附加说明' }} · 发出 {{ reportTime(row.sentAt) }}</p>
          <p>当前提醒结果：{{ mailLabels[row.currentReminderStatus || 'NONE'] }} · {{ row.currentReminderNote || '无附加说明' }} · 发出 {{ reportTime(row.currentSentAt) }}</p>
          <p>已验证邮箱：{{ row.hasVerifiedEmail ? '已绑定' : '未绑定' }} · 截止 {{ reportTime(row.cutoffAt) }} · 统计生成 {{ reportTime(row.generatedAt) }}</p>
        </el-collapse-item></el-collapse>
      </article>
    </div>
    <el-pagination v-model:current-page="page" :page-size="20" :total="total" layout="prev, pager, next" class="mt-4" @current-change="load" />
  </div>
</template>
