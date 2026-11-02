<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { notifyError } from '@/api/client'
import { saveOwnReport, deleteOwnDraft, reportLabels, type ReportDetail } from '@/api/weeklyReports'
import ReportHistory from './ReportHistory.vue'
const props = defineProps<{ report: ReportDetail | null; weekStart: string; canWrite: boolean }>()
const emit = defineEmits<{ saved: [report: ReportDetail]; deleted: []; dirty: [value: boolean] }>()
const summary = ref(''), problems = ref(''), plan = ref(''), saving = ref(false), error = ref('')
let generation = 0
const editable = computed(() => props.canWrite && (!props.report || ['DRAFT', 'RETURNED'].includes(props.report.status)))
const dirty = computed(() => summary.value !== (props.report?.summary || '') || problems.value !== (props.report?.problems || '') || plan.value !== (props.report?.plan || ''))
watch(dirty, value => emit('dirty', value))
watch(() => [props.report, props.weekStart, props.canWrite], () => {
  generation++; summary.value = props.report?.summary || ''; problems.value = props.report?.problems || ''; plan.value = props.report?.plan || ''; saving.value = false; error.value = ''
}, { immediate: true })
onBeforeUnmount(() => { generation++ })
async function save(submit: boolean) {
  if (saving.value || !editable.value) return
  if (submit && !summary.value.trim()) { error.value = '请填写本周学习总结'; return }
  const token = generation, input = { id: props.report?.id || null, weekStart: props.weekStart, version: props.report?.version || 0, summary: summary.value, problems: problems.value, plan: plan.value }
  saving.value = true; error.value = ''
  try {
    if (submit) { try { await ElMessageBox.confirm('提交后由负责人查看和评价，需退回后才能修改。确认提交？', '提交周报') } catch { return } }
    if (token !== generation) return
    const result = await saveOwnReport(input, submit)
    if (token !== generation) return
    emit('saved', result); ElMessage.success(submit ? '周报已提交' : '草稿已保存')
  } catch (e) { if (token === generation) { error.value = '保存未完成，请保留内容并重试；如版本变化，请重新打开该周报'; notifyError(e) } }
  finally { if (token === generation) saving.value = false }
}
async function remove() {
  const report = props.report, token = generation
  if (!report || report.status !== 'DRAFT' || saving.value) return
  saving.value = true
  try {
    try { await ElMessageBox.confirm('删除这份尚未提交的草稿？', '删除草稿', { type: 'warning' }) } catch { return }
    if (token !== generation) return
    await deleteOwnDraft(report.id, report.version)
    if (token === generation) { emit('deleted'); ElMessage.success('草稿已删除') }
  } catch (e) { if (token === generation) notifyError(e) }
  finally { if (token === generation) saving.value = false }
}
</script>
<template>
  <div>
    <div class="mb-4 flex flex-wrap items-center gap-2"><span class="font-semibold text-ink">{{ weekStart }} 起的一周</span><el-tag>{{ report ? reportLabels[report.status] : '尚未创建' }}</el-tag></div>
    <p v-if="error" role="alert" class="text-danger-ink">{{ error }}</p>
    <p v-if="report?.status === 'RETURNED'" class="text-sm text-muted">请按下方退回原因修改，保存后还需重新提交。</p>
    <el-form v-if="editable" label-position="top" @submit.prevent="save(true)">
      <el-form-item label="本周学习总结（必填）"><el-input v-model="summary" :disabled="saving" type="textarea" :rows="7" maxlength="10000" show-word-limit placeholder="记录本周学习、训练和收获；没有刷题记录也可以如实填写。" /></el-form-item>
      <el-form-item label="问题与困难（选填）"><el-input v-model="problems" :disabled="saving" type="textarea" :rows="3" maxlength="5000" /></el-form-item>
      <el-form-item label="下周计划（选填）"><el-input v-model="plan" :disabled="saving" type="textarea" :rows="3" maxlength="5000" /></el-form-item>
      <div class="flex flex-wrap gap-2"><el-button type="primary" native-type="submit" :loading="saving">{{ report?.status === 'RETURNED' ? '重新提交' : '提交周报' }}</el-button><el-button :disabled="saving" @click="save(false)">保存草稿</el-button><el-button v-if="report?.status === 'DRAFT'" :disabled="saving" type="danger" plain @click="remove">删除草稿</el-button></div>
      <p class="mb-0 text-sm text-muted">草稿仅自己可见。页面切换前请保存；统计截止后仍可补交。</p>
    </el-form>
    <div v-else class="text-ink"><h3 class="text-base">本周学习总结</h3><p class="whitespace-pre-wrap break-words">{{ summary || '未填写' }}</p><h3 class="text-base">问题与困难</h3><p class="whitespace-pre-wrap break-words">{{ problems || '未填写' }}</p><h3 class="text-base">下周计划</h3><p class="whitespace-pre-wrap break-words">{{ plan || '未填写' }}</p></div>
    <ReportHistory v-if="report" :history="report.history" />
  </div>
</template>
