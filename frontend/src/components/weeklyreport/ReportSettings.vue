<script setup lang="ts">
import { onBeforeUnmount, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { notifyError } from '@/api/client'
import { loadReportSettings, saveReportSettings, reportTime, type ReportScope, type ReportSettings, type ReportRule } from '@/api/weeklyReports'
import SectionCard from '@/components/ui/SectionCard.vue'
const props = defineProps<{ scope: ReportScope }>()
const emit = defineEmits<{ updated: []; failed: [error: unknown] }>()
const data = ref<ReportSettings | null>(null), loading = ref(false), saving = ref(false), error = ref(''), enabled = ref(false)
const form = ref<ReportRule>({ dayOfWeek: 7, time: '22:00', leadHours: 6, emailEnabled: true })
const days = ['一', '二', '三', '四', '五', '六', '日']
let generation = 0, request = 0
function description(rule: ReportRule) { return '每周' + days[rule.dayOfWeek - 1] + ' ' + rule.time + '，提前 ' + rule.leadHours + ' 小时，邮件' + (rule.emailEnabled ? '开启' : '关闭') }
async function load() {
  const token = generation, req = ++request; loading.value = true; error.value = ''
  try { const result = await loadReportSettings({ ...props.scope }); if (token === generation && req === request) { data.value = result; enabled.value = result.enabled; form.value = { ...(result.next || result.current) } } }
  catch (e) { if (token === generation && req === request) { data.value = null; error.value = '统计配置读取失败，请重试'; emit('failed', e); notifyError(e) } }
  finally { if (token === generation && req === request) loading.value = false }
}
watch(() => props.scope.schoolId, () => { generation++; data.value = null; saving.value = false; void load() }, { immediate: true })
onBeforeUnmount(() => { generation++; request++ })
async function save() {
  if (!data.value || saving.value) return
  const token = generation, scope = { ...props.scope }, wasEnabled = data.value.enabled
  const input = { ...form.value, enabled: enabled.value, version: data.value.version, confirmDisable: false }
  saving.value = true; error.value = ''
  try {
    if (wasEnabled && !input.enabled) {
      try { await ElMessageBox.confirm('关闭后停止后续未提交统计和提醒，未发出的邮件将取消。历史名单保留，周报提交与评价继续可用。确认关闭？', '关闭周报统计与提醒', { type: 'warning' }) } catch { if (token === generation) enabled.value = wasEnabled; return }
      input.confirmDisable = true
    }
    if (token !== generation) return
    await saveReportSettings(scope, input)
    if (token === generation) { ElMessage.success(input.enabled ? '配置已保存，请查看生效周次' : '统计与提醒已关闭'); await load(); emit('updated') }
  } catch (e) { if (token === generation) { error.value = '配置未保存，请核对或刷新后重试'; emit('failed', e); notifyError(e) } }
  finally { if (token === generation) saving.value = false }
}
</script>
<template>
  <SectionCard title="周报统计设置" description="学校默认关闭。时间统一为北京时间；关闭统计不影响学生提交和负责人评价。">
    <p v-if="error" role="alert" class="text-danger-ink">{{ error }} <el-button text @click="load">重试</el-button></p>
    <div v-loading="loading" class="min-h-20">
      <template v-if="data">
        <p class="mt-0 text-sm text-muted">当前状态：{{ data.enabled ? '已开启' : '已关闭' }}<template v-if="data.configured"> · 本周安排：{{ description(data.current) }}</template></p>
        <p v-if="data.next.effectiveWeek && data.next.effectiveWeek !== data.current.effectiveWeek" class="text-sm text-muted">{{ data.next.effectiveWeek }} 起：{{ description(data.next) }}</p>
        <p v-if="data.enabled" class="text-sm text-muted">下次统计：{{ reportTime(data.nextCutoffAt) }}<br>下次提前提醒：{{ data.nextReminderAt ? reportTime(data.nextReminderAt) : '当前没有待执行的提前提醒' }}</p>
        <p v-if="!data.automaticRemindersEnabled || !data.mailConfigured" role="status" class="text-sm text-muted">{{ !data.automaticRemindersEnabled ? '平台自动提醒当前暂停。' : '' }}{{ !data.mailConfigured ? '邮件服务或周报入口尚未配置，邮件暂不能发出。' : '' }}统计不受邮件发送结果影响。</p>
        <el-form label-position="top" @submit.prevent="save">
          <el-form-item label="周报提交统计与未提交提醒"><el-switch v-model="enabled" :disabled="saving" aria-label="开启本校周报统计与提醒" /></el-form-item>
          <div class="flex flex-wrap gap-x-5">
            <el-form-item label="每周统计日期"><el-select v-model="form.dayOfWeek" class="!w-40" :disabled="saving"><el-option v-for="(day,index) in days" :key="index" :value="index+1" :label="'星期'+day" /></el-select></el-form-item>
            <el-form-item label="统计时刻（北京时间）"><el-time-picker v-model="form.time" value-format="HH:mm" format="HH:mm" :clearable="false" :disabled="saving" class="max-w-full !w-40" /></el-form-item>
            <el-form-item label="提前提醒小时数"><el-input-number v-model="form.leadHours" :min="0" :max="167" :precision="0" :disabled="saving" /></el-form-item>
            <el-form-item label="同时发送邮件"><el-switch v-model="form.emailEnabled" :disabled="saving" aria-label="周报邮件提醒" /></el-form-item>
          </div>
          <p class="text-sm text-muted">提前 0 小时表示不提醒；提醒不得早于本周一。首次开启仅执行本周未来时点，以后参数修改对下一周生效。个人关闭邮件后不会强制发送。</p>
          <el-button native-type="submit" type="primary" :loading="saving">保存配置</el-button>
        </el-form>
      </template>
    </div>
  </SectionCard>
</template>
