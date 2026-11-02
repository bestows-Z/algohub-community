<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue'
import { onBeforeRouteLeave } from 'vue-router'
import { ElMessageBox } from 'element-plus'
import { notifyError } from '@/api/client'
import { loadReportContext, loadOwnReports, loadOwnReport, findOwnReport, reportLabels, reportTime, type ReportContext, type ReportRow, type ReportDetail } from '@/api/weeklyReports'
import PageHeader from '@/components/ui/PageHeader.vue'
import SectionCard from '@/components/ui/SectionCard.vue'
import ReportEditor from '@/components/weeklyreport/ReportEditor.vue'
const context = ref<ReportContext | null>(null), rows = ref<ReportRow[]>([]), editing = ref<ReportDetail | null>(null)
const week = ref(''), page = ref(1), total = ref(0), loading = ref(false), detailLoading = ref(false), dirty = ref(false), error = ref('')
const editorKey = ref(0)
let generation = 0, detailRequest = 0
onBeforeUnmount(() => { generation++; detailRequest++ })
async function canLeave() { if (!dirty.value) return true; try { await ElMessageBox.confirm('有尚未保存的周报内容，确认离开？', '未保存内容', { type: 'warning' }); return true } catch { return false } }
onBeforeRouteLeave(canLeave)
async function load() {
  const token = ++generation; loading.value = true; error.value = ''
  try {
    const [info, result] = await Promise.all([loadReportContext(), loadOwnReports(page.value)])
    if (token !== generation) return
    context.value = info; rows.value = result.rows; total.value = result.total
    if (!week.value) week.value = info.weekStart
  } catch (e) { if (token === generation) { error.value = '周报读取失败，请重试'; notifyError(e) } }
  finally { if (token === generation) loading.value = false }
}
onMounted(async () => { await load(); if (context.value?.membership) await chooseWeek(context.value.weekStart) })
async function chooseWeek(value: string) {
  if (detailLoading.value) return
  const token = ++detailRequest
  if (!(await canLeave()) || token !== detailRequest) return
  dirty.value = false; detailLoading.value = true
  try { const result = await findOwnReport(value); if (token === detailRequest) { dirty.value = false; editing.value = result; week.value = value; editorKey.value++ } }
  catch (e) { if (token === detailRequest) notifyError(e) }
  finally { if (token === detailRequest) detailLoading.value = false }
}
async function open(row: ReportRow) {
  if (detailLoading.value) return
  const token = ++detailRequest
  if (!(await canLeave()) || token !== detailRequest) return
  detailLoading.value = true; dirty.value = false
  try { const result = await loadOwnReport(row.id); if (token === detailRequest) { editing.value = result; week.value = result.weekStart } }
  catch (e) { if (token === detailRequest) notifyError(e) }
  finally { if (token === detailRequest) detailLoading.value = false }
}
async function create() {
  if (context.value) await chooseWeek(context.value.weekStart)
}
async function saved(report: ReportDetail) { editing.value = report; dirty.value = false; await load() }
async function deleted() { editing.value = null; dirty.value = false; await load() }
</script>
<template>
  <div class="weekly-reports page mx-auto max-w-[1000px] flex flex-col gap-4">
    <PageHeader title="我的周报" description="记录每周学习情况，提交后查看负责人的评价。自动刷题汇总不会代替周报提交。"><template #actions><el-button :disabled="loading || detailLoading || !context?.membership" @click="create">本周周报</el-button></template></PageHeader>
    <p v-if="error" role="alert" class="m-0 text-danger-ink">{{ error }} <el-button text @click="load">重试</el-button></p>
    <SectionCard v-if="context && week" :title="editing ? '周报详情' : '填写周报'" :description="context.membership?.schoolName || '当前没有可提交周报的学生身份，仍可查看本人历史周报。'">
      <p v-if="context.deadline && week === context.weekStart" class="mt-0 text-sm text-muted">本周截止统计时间：{{ reportTime(context.deadline) }}（北京时间）</p>
      <p v-if="detailLoading" role="status" class="text-sm text-muted">正在读取周报，请稍候…</p>
      <el-form v-if="!editing && context.membership && !detailLoading" label-position="top"><el-form-item label="周次（请选择该周周一）"><el-date-picker :model-value="week" type="date" value-format="YYYY-MM-DD" :clearable="false" aria-label="周报周次" class="max-w-full" @update:model-value="chooseWeek" /></el-form-item></el-form>
      <ReportEditor v-if="!detailLoading && (editing || context.membership)" :key="editorKey" :report="editing" :week-start="editing?.weekStart || week" :can-write="!!context.membership && (!editing || editing.schoolId === context.membership.schoolId)" @saved="saved" @deleted="deleted" @dirty="dirty = $event" />
    </SectionCard>
    <SectionCard title="我的历史周报" description="这里只展示本人的周报及处理状态。">
      <div v-loading="loading" class="min-h-20"><el-empty v-if="!rows.length && !loading" description="还没有周报，先记录本周的学习情况。" /><div v-for="row in rows" :key="row.id" class="flex flex-wrap items-center justify-between gap-3 border-b border-line py-4"><div class="min-w-0"><p class="m-0 font-semibold text-ink">{{ row.weekStart }} 起的一周 · {{ reportLabels[row.status] }}</p><p class="mb-0 text-sm text-muted break-words">{{ row.schoolName }} · 提交时间 {{ reportTime(row.submittedAt) }}</p></div><el-button :disabled="detailLoading" @click="open(row)">查看周报</el-button></div></div>
      <el-pagination v-model:current-page="page" :total="total" :page-size="20" layout="prev, pager, next" class="mt-4" @current-change="load" />
    </SectionCard>
  </div>
</template>
<style scoped>
@media(max-width:767px){.weekly-reports :deep(.el-button),.weekly-reports :deep(.el-input__wrapper){min-height:44px}.weekly-reports :deep(textarea){font-size:16px}}
</style>
