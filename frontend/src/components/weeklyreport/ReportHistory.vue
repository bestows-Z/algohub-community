<script setup lang="ts">
import { reportLabels, reportTime, type ReportEvent } from '@/api/weeklyReports'
defineProps<{ history: ReportEvent[] }>()
const actions: Record<string, string> = { SAVE: '保存修改', SUBMIT: '提交周报', REVIEW: '评价', RETURN: '退回修改' }
</script>
<template>
  <div v-if="history.length" class="mt-5 border-t border-line pt-4">
    <h3 class="m-0 text-base text-ink">提交与评价记录</h3>
    <div v-for="item in history" :key="item.version" class="border-b border-line py-3">
      <p class="m-0 text-sm text-muted break-words">{{ reportTime(item.happenedAt) }} · {{ actions[item.action] || item.action }} · {{ item.actorName || '原操作人' }} · {{ reportLabels[item.status] }}</p>
      <p v-if="item.comment" class="mb-0 whitespace-pre-wrap break-words text-ink">{{ item.comment }}</p>
      <el-collapse v-if="item.action === 'SUBMIT'" class="mt-2"><el-collapse-item :title="'查看第 ' + item.version + ' 版提交内容'"><p class="whitespace-pre-wrap break-words">{{ item.summary }}</p><p v-if="item.problems" class="whitespace-pre-wrap break-words">问题与困难：{{ item.problems }}</p><p v-if="item.plan" class="whitespace-pre-wrap break-words">下周计划：{{ item.plan }}</p></el-collapse-item></el-collapse>
    </div>
  </div>
</template>
