<script setup lang="ts">
import { onBeforeUnmount, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { notifyError } from '@/api/client'
import { useAuthStore } from '@/stores/auth'
import { reviewReport, reportLabels, reportTime, type ReportDetail, type ReportScope } from '@/api/weeklyReports'
import ReportHistory from './ReportHistory.vue'
const props = defineProps<{ scope: ReportScope; report: ReportDetail }>()
const emit = defineEmits<{ updated: [report: ReportDetail]; failed: [error: unknown] }>()
const auth = useAuthStore(), comment = ref(''), saving = ref(false), error = ref('')
let generation = 0
watch(() => [props.scope.schoolId, props.scope.enrollmentYear, props.report.id, props.report.version], () => { generation++; comment.value = ''; saving.value = false; error.value = '' })
onBeforeUnmount(() => { generation++ })
async function decide(returned: boolean) {
  if (saving.value || props.report.userId === auth.me?.id) return
  if (!comment.value.trim()) { error.value = returned ? '请填写退回原因' : '请填写评价'; return }
  const token = generation, scope = { ...props.scope }, id = props.report.id, input = { version: props.report.version, returned, comment: comment.value.trim() }
  saving.value = true; error.value = ''
  try {
    if (returned) { try { await ElMessageBox.confirm('退回后学生需要修改并重新提交，确认退回？', '退回周报', { type: 'warning' }) } catch { return } }
    if (token !== generation) return
    const result = await reviewReport(scope, id, input)
    if (token === generation) { emit('updated', result); ElMessage.success(returned ? '周报已退回' : '评价已保存') }
  } catch (e) { if (token === generation) { error.value = '操作未完成，请刷新周报后重试'; emit('failed', e); notifyError(e) } }
  finally { if (token === generation) saving.value = false }
}
</script>
<template>
  <div>
    <p class="mt-0 text-sm text-muted">{{ report.weekStart }} 起的一周 · {{ reportLabels[report.status] }} · 提交 {{ reportTime(report.submittedAt) }}</p>
    <h3 class="text-base text-ink">本周学习总结</h3><p class="whitespace-pre-wrap break-words text-ink">{{ report.summary }}</p>
    <h3 class="text-base text-ink">问题与困难</h3><p class="whitespace-pre-wrap break-words text-ink">{{ report.problems || '未填写' }}</p>
    <h3 class="text-base text-ink">下周计划</h3><p class="whitespace-pre-wrap break-words text-ink">{{ report.plan || '未填写' }}</p>
    <el-form v-if="report.userId !== auth.me?.id && ['SUBMITTED','REVIEWED'].includes(report.status)" label-position="top" class="mt-5" @submit.prevent="decide(false)">
      <el-form-item label="评价或退回原因"><el-input v-model="comment" type="textarea" :rows="3" :disabled="saving" maxlength="3000" /></el-form-item>
      <p v-if="error" role="alert" class="text-danger-ink">{{ error }}</p>
      <div class="flex flex-wrap gap-2"><el-button v-if="report.status === 'SUBMITTED'" native-type="submit" type="primary" :loading="saving">保存评价</el-button><el-button :disabled="saving" type="warning" plain @click="decide(true)">退回修改</el-button></div>
    </el-form>
    <p v-if="report.userId === auth.me?.id" class="text-sm text-muted">自己的周报需由其他有权限的负责人评价。</p>
    <ReportHistory :history="report.history" />
  </div>
</template>
