<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { ApiError, notifyError } from '@/api/client'
import { loadCohortMembers, loadCohortScopes, memberStatusLabels, reviewCohortMember, updateCohortMember, type CohortMember, type CohortScope } from '@/api/cohortLeaders'
import { useAuthStore } from '@/stores/auth'
import PageHeader from '@/components/ui/PageHeader.vue'
import SectionCard from '@/components/ui/SectionCard.vue'
import ResponsiveDialog from '@/components/ui/ResponsiveDialog.vue'

const auth = useAuthStore()
const scopes = ref<CohortScope[]>([])
const selected = ref('')
const scopeKey = (scope: CohortScope) => scope.schoolId + ':' + scope.enrollmentYear
const current = computed(() => scopes.value.find(scope => scopeKey(scope) === selected.value))
const members = ref<CohortMember[]>([])
const query = ref(''), status = ref(''), page = ref(1), total = ref(0)
const loading = ref(false), saving = ref(false), scopesLoading = ref(false), error = ref('')
const editing = ref<CohortMember | null>(null)
let generation = 0, requestId = 0, scopesRequest = 0, disposed = false
onBeforeUnmount(() => { disposed = true; generation++; requestId++; scopesRequest++ })

async function loadScopes() {
  const request = ++scopesRequest
  scopesLoading.value = true; error.value = ''
  try {
    const data = await loadCohortScopes()
    if (disposed || request !== scopesRequest) return
    scopes.value = data
    if (!data.some(scope => scopeKey(scope) === selected.value)) selected.value = data[0] ? scopeKey(data[0]) : ''
  } catch (e) {
    if (!disposed && request === scopesRequest) { error.value = '管理范围暂时读取失败，请重试'; notifyError(e) }
  } finally { if (!disposed && request === scopesRequest) scopesLoading.value = false }
}
onMounted(loadScopes)
watch(selected, () => {
  generation++; requestId++
  members.value = []; total.value = 0; editing.value = null; page.value = 1; saving.value = false
  query.value = ''; status.value = ''; error.value = ''; loading.value = false
  if (current.value) void load()
})
async function failed(e: unknown, token: number) {
  if (disposed || token !== generation) return
  if (e instanceof ApiError && e.kind === 'FORBIDDEN') {
    members.value = []; total.value = 0; editing.value = null; selected.value = ''; scopes.value = []
    try { await auth.refreshMe() } catch { /* Server authorization remains authoritative. */ }
    if (!disposed) await loadScopes()
  }
  if (!disposed && token === generation) notifyError(e)
}
async function load() {
  const scope = current.value
  if (!scope) return
  const token = generation, request = ++requestId
  loading.value = true; error.value = ''
  try {
    const data = await loadCohortMembers(scope, { q: query.value.trim(), status: status.value, page: page.value })
    if (token !== generation || request !== requestId) return
    members.value = data.rows; total.value = data.total
  } catch (e) {
    if (token !== generation || request !== requestId) return
    error.value = '成员名单暂时读取失败，请重试'
    await failed(e, token)
  } finally { if (token === generation && request === requestId) loading.value = false }
}
function search() { page.value = 1; void load() }
async function review(member: CohortMember, approve: boolean) {
  const scope = current.value
  if (!scope || saving.value || !member.canReview) return
  const token = generation
  saving.value = true
  try {
    try { await ElMessageBox.confirm((approve ? '通过 ' : '拒绝 ') + member.displayName + ' 的入校申请？', '本届成员审核') }
    catch { return }
    if (token !== generation) return
    await reviewCohortMember(scope, member.id, approve)
    if (token !== generation) return
    ElMessage.success('已处理'); await load()
  } catch (e) { await failed(e, token) }
  finally { if (token === generation) saving.value = false }
}
async function save() {
  const scope = current.value, member = editing.value
  if (!scope || !member || saving.value) return
  const token = generation
  saving.value = true
  try {
    await updateCohortMember(scope, member)
    if (token !== generation) return
    editing.value = null; ElMessage.success('班级和学号已保存'); await load()
  } catch (e) { await failed(e, token) }
  finally { if (token === generation) saving.value = false }
}
</script>

<template>
  <div class="cohort-management page mx-auto max-w-[1100px] flex flex-col gap-4">
    <PageHeader title="本届管理" description="协助管理负责年级的学生，审核入校申请、维护班级和学号、查看学习档案。">
      <template #actions><el-button :loading="scopesLoading" :disabled="saving" @click="loadScopes">刷新管理范围</el-button></template>
    </PageHeader>
    <p v-if="error" role="alert" class="m-0 text-sm text-danger-ink">{{ error }} <el-button text :disabled="loading || saving" @click="current ? load() : loadScopes()">重试</el-button></p>
    <el-select v-if="scopes.length" v-model="selected" :disabled="saving" aria-label="当前管理年级" class="max-w-full !w-96">
      <el-option v-for="scope in scopes" :key="scopeKey(scope)" :value="scopeKey(scope)" :label="scope.schoolName + ' · ' + scope.enrollmentYear + ' 级'" />
    </el-select>
    <el-empty v-if="!scopes.length && !scopesLoading && !error" description="暂无有效年级授权，请联系学校管理员任命。" />
    <SectionCard v-if="current" title="本届成员" :description="current.schoolName + ' · ' + current.enrollmentYear + ' 级；其他年级由对应负责人或学校管理员管理。'">
      <el-form class="mb-4 flex flex-wrap items-end gap-3" label-position="top" @submit.prevent="search">
        <el-form-item label="查找成员" class="mb-0"><el-input v-model="query" :disabled="saving" maxlength="100" placeholder="姓名、用户名、学号或班级" class="max-w-full !w-64" /></el-form-item>
        <el-form-item label="审核状态" class="mb-0"><el-select v-model="status" :disabled="saving" placeholder="全部状态" class="!w-32" @change="search"><el-option label="全部状态" value="" /><el-option v-for="(label, value) in memberStatusLabels" :key="value" :value="value" :label="label" /></el-select></el-form-item>
        <el-button native-type="submit" :disabled="saving" :loading="loading">查询</el-button>
      </el-form>
      <div v-loading="loading" class="min-h-24">
        <el-empty v-if="!members.length && !loading && !error" description="暂无符合条件的本届成员" />
        <div v-for="member in members" :key="member.id" class="flex flex-wrap items-center justify-between gap-3 border-b border-line py-4">
          <div class="min-w-0">
            <div class="flex flex-wrap items-center gap-2 font-semibold text-ink">
              <RouterLink v-if="member.status === 'APPROVED' && member.canEdit" :to="'/users/' + member.userId" class="text-brand-600">{{ member.displayName }}</RouterLink><span v-else>{{ member.displayName }}</span>
              <el-tag size="small">{{ memberStatusLabels[member.status] }}</el-tag>
            </div>
            <p class="mb-0 mt-1 break-words text-sm text-muted">{{ member.className || '未填写班级' }} · 学号 {{ member.studentNo || '未填写' }}</p>
          </div>
          <div class="flex flex-wrap gap-2">
            <template v-if="member.canReview">
              <el-button v-if="member.status !== 'APPROVED'" :disabled="saving" type="primary" @click="review(member, true)">通过</el-button>
              <el-button v-if="member.status !== 'REJECTED'" :disabled="saving" @click="review(member, false)">拒绝</el-button>
            </template>
            <el-button v-if="member.canEdit" :disabled="saving" @click="editing = { ...member }">修改资料</el-button>
          </div>
        </div>
      </div>
      <el-pagination v-model:current-page="page" :disabled="saving" :page-size="50" :total="total" layout="prev, pager, next" class="mt-4" @current-change="load" />
    </SectionCard>
    <ResponsiveDialog :model-value="!!editing" title="修改本届成员资料" @update:model-value="value => { if (!value && !saving) editing = null }">
      <el-form v-if="editing" label-position="top" @submit.prevent="save">
        <p class="mt-0 text-sm text-muted">只修改班级和学号。年级调整请联系学校管理员。</p>
        <el-form-item label="班级"><el-input v-model="editing.className" :disabled="saving" maxlength="64" /></el-form-item>
        <el-form-item label="学号"><el-input v-model="editing.studentNo" :disabled="saving" maxlength="32" /></el-form-item>
        <el-button native-type="submit" type="primary" :loading="saving">保存</el-button>
      </el-form>
    </ResponsiveDialog>
  </div>
</template>

<style scoped>
@media (max-width: 767px) {
  .cohort-management :deep(.el-button),
  .cohort-management :deep(.el-input__wrapper),
  .cohort-management :deep(.el-select__wrapper) { min-height: 44px; }
}
</style>
