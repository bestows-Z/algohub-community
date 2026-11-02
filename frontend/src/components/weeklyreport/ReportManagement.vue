<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { ApiError, notifyError } from '@/api/client'
import { useAuthStore } from '@/stores/auth'
import { reportAvailability, loadManagedReports, loadManagedReport, currentReportWeek, reportLabels, reportTime, type ReportScope, type ReportRow, type ReportDetail } from '@/api/weeklyReports'
import SectionCard from '@/components/ui/SectionCard.vue'
import ResponsiveDialog from '@/components/ui/ResponsiveDialog.vue'
import ReportSettings from './ReportSettings.vue'
import ReportReview from './ReportReview.vue'
import ReportMissing from './ReportMissing.vue'
const props = defineProps<{ scope: ReportScope }>()
const auth = useAuthStore(), manager = ref(false), enabled = ref(false), allowed = ref(false), tab = ref('reports')
const rows = ref<ReportRow[]>([]), detail = ref<ReportDetail | null>(null), query = ref(''), status = ref(''), week = ref(currentReportWeek()), year = ref<number>(), page = ref(1), total = ref(0), loading = ref(false), error = ref('')
const reportScope = computed(() => ({ schoolId: props.scope.schoolId, enrollmentYear: props.scope.enrollmentYear ?? year.value }))
let generation = 0, request = 0, detailRequest = 0
onBeforeUnmount(() => { generation++; request++; detailRequest++ })
async function failed(e: unknown) {
  if (e instanceof ApiError && e.kind === 'FORBIDDEN') {
    generation++; request++; detailRequest++; allowed.value = false; manager.value = false; enabled.value = false; rows.value = []; total.value = 0; detail.value = null
    try { await auth.refreshMe() } catch { /* Read-only permission loss must still clear the workspace. */ }
    error.value = '管理权限或学校开关已变化，请刷新管理范围'
  }
}
async function availability() {
  const token = generation
  try { const data = await reportAvailability({ ...props.scope }); if (token !== generation) return; manager.value = data.manager; enabled.value = data.statisticsEnabled; allowed.value = true; if (!manager.value && !enabled.value) tab.value = 'reports' }
  catch (e) { if (token === generation) { await failed(e); notifyError(e) } }
}
async function load() {
  const token = generation, req = ++request; loading.value = true; error.value = ''; rows.value = []; total.value = 0
  try { const data = await loadManagedReports(reportScope.value, { week: week.value, status: status.value, q: query.value.trim(), page: page.value }); if (token === generation && req === request) { rows.value = data.rows; total.value = data.total } }
  catch (e) { if (token === generation && req === request) { error.value = '周报列表读取失败，请重试'; await failed(e); notifyError(e) } }
  finally { if (token === generation && req === request) loading.value = false }
}
watch(() => [props.scope.schoolId, props.scope.enrollmentYear], async () => {
  const token = ++generation; request++; detailRequest++; allowed.value = false; manager.value = false; enabled.value = false; detail.value = null; rows.value = []; total.value = 0; page.value = 1; year.value = undefined; tab.value = 'reports'; week.value = currentReportWeek(); query.value = ''; status.value = ''
  await availability(); if (token === generation && allowed.value) await load()
}, { immediate: true })
function search() { detailRequest++; detail.value = null; page.value = 1; void load() }
async function open(row: ReportRow) {
  const token = generation, req = ++detailRequest, scope = { ...reportScope.value }; detail.value = null
  try { const data = await loadManagedReport(scope, row.id); if (token === generation && req === detailRequest) detail.value = data }
  catch (e) { if (token === generation && req === detailRequest) { await failed(e); notifyError(e) } }
}
async function settingsUpdated() { await availability() }
async function reviewed(result: ReportDetail) { detail.value = result; await load() }
</script>
<template>
  <div class="report-management flex flex-col gap-4">
    <p v-if="error" role="alert" class="m-0 text-danger-ink">{{ error }} <el-button text @click="availability(); load()">重试</el-button></p>
    <template v-if="allowed">
      <ReportSettings v-if="manager" :scope="scope" @updated="settingsUpdated" @failed="failed" />
      <SectionCard title="学生周报" description="仅展示已提交版本。草稿和退回后尚未提交的修改仅学生本人可见。">
        <el-radio-group v-model="tab" aria-label="周报管理内容" class="mb-4"><el-radio-button value="reports">查看与评价</el-radio-button><el-radio-button v-if="enabled || manager" value="missing">{{ enabled ? '未提交统计' : '历史统计记录' }}</el-radio-button></el-radio-group>
        <template v-if="tab === 'reports'">
          <el-form label-position="top" class="flex flex-wrap gap-x-3" @submit.prevent="search">
            <el-form-item label="周次（周一日期）"><el-date-picker v-model="week" type="date" value-format="YYYY-MM-DD" :clearable="false" class="max-w-full !w-44" @change="search" /></el-form-item>
            <el-form-item v-if="manager && !scope.enrollmentYear" label="入学年级（留空为全部）"><el-input-number v-model="year" :min="2000" :max="2100" :precision="0" @change="search" /></el-form-item>
            <el-form-item label="姓名、用户名或学号"><el-input v-model="query" maxlength="100" class="!w-48" /></el-form-item>
            <el-form-item label="状态"><el-select v-model="status" class="!w-40" @change="search"><el-option label="全部已提交周报" value="" /><el-option v-for="value in ['SUBMITTED','REVIEWED','RETURNED']" :key="value" :value="value" :label="reportLabels[value]" /></el-select></el-form-item>
            <el-form-item label="查询"><el-button native-type="submit" :loading="loading">查询周报</el-button></el-form-item>
          </el-form>
          <div v-loading="loading" class="min-h-20"><el-empty v-if="!rows.length && !loading && !error" description="当前范围还没有已提交的周报" /><div v-for="row in rows" :key="row.id" class="flex flex-wrap items-center justify-between gap-3 border-b border-line py-4"><div class="min-w-0"><p class="m-0 font-semibold text-ink break-words">{{ row.displayName }} · {{ reportLabels[row.status] }}</p><p class="mb-0 text-sm text-muted break-words">{{ row.enrollmentYear || '未填写年级' }} · {{ row.studentNo || '学号未填写' }} · {{ reportTime(row.submittedAt) }}</p></div><el-button @click="open(row)">查看与评价</el-button></div></div>
          <el-pagination v-model:current-page="page" :page-size="20" :total="total" layout="prev, pager, next" class="mt-4" @current-change="load" />
        </template>
        <ReportMissing v-else :scope="scope" :manager="manager" @failed="failed" />
      </SectionCard>
      <ResponsiveDialog :model-value="!!detail" title="周报与评价" @update:model-value="value => { if (!value) { detailRequest++; detail = null } }"><ReportReview v-if="detail" :scope="reportScope" :report="detail" @updated="reviewed" @failed="failed" /></ResponsiveDialog>
    </template>
  </div>
</template>
<style scoped>
@media(max-width:767px){.report-management :deep(.el-button),.report-management :deep(.el-input__wrapper),.report-management :deep(.el-select__wrapper){min-height:44px}.report-management :deep(.el-form-item){max-width:100%}.report-management :deep(textarea){font-size:16px}}
</style>
