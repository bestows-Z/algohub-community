<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { get, post, del, notifyError } from '@/api/client'
import PageHeader from '@/components/ui/PageHeader.vue'
import SectionCard from '@/components/ui/SectionCard.vue'
import ResponsiveDialog from '@/components/ui/ResponsiveDialog.vue'
import CohortLeaderGrants from '@/components/admin/CohortLeaderGrants.vue'
import { useAuthStore } from '@/stores/auth'
import { loadAdminMe } from '@/api/adminUsers'
const auth=useAuthStore()
const permissions=ref<string[]>([])
const canMembers=computed(()=>!auth.isAdmin || permissions.value.includes('SCHOOLS'))
const canNotices=computed(()=>auth.isAdmin && permissions.value.includes('CONTENT'))
type Member={id:number;userId:number;displayName:string;username:string;status:string;enrollmentYear:number;className:string;studentNo:string;platformAdmin:boolean}
type Notice={id:number;title:string;content:string;published:boolean}
const schools=ref<{id:number;name:string}[]>([]), schoolId=ref<number>(), tab=ref('members')
const members=ref<Member[]>([]), notices=ref<Notice[]>([]), query=ref(''), status=ref(''), page=ref(1), total=ref(0), loading=ref(false)
const editing=ref<Member|null>(null), notice=ref<Notice|null>(null), saving=ref(false)
const label:Record<string,string>={PENDING:'待审核',APPROVED:'已通过',REJECTED:'已拒绝'}
let generation=0
async function load() { const id=schoolId.value, token=++generation; if(!id)return; loading.value=true; try{
  if(tab.value==='members'){const result=await get<{rows:Member[];total:number}>(`/school-admin/${id}/members`,{q:query.value,status:status.value,page:page.value});if(token===generation){members.value=result.rows;total.value=result.total}}
  else {const result=await get<Notice[]>(`/school-admin/${id}/announcements`);if(token===generation)notices.value=result}
}catch(e){if(token===generation)notifyError(e)}finally{if(token===generation)loading.value=false} }
watch([schoolId,tab],()=>{page.value=1;members.value=[];notices.value=[];editing.value=null;notice.value=null;void load()})
onMounted(async()=>{try{if(auth.isAdmin){permissions.value=(await loadAdminMe()).permissions;if(!canMembers.value)tab.value='notices'}schools.value=await get('/school-admin/schools');schoolId.value=schools.value[0]?.id}catch(e){notifyError(e)}})
async function review(m:Member,approve:boolean){ const id=schoolId.value;try{await ElMessageBox.confirm(`${approve?'通过':'拒绝'} ${m.displayName} 的入校申请？`,'入校审核')}catch{return}try{await post(`/school-admin/${id}/members/${m.id}/review`,{approve});await load();ElMessage.success('已处理')}catch(e){notifyError(e)} }
async function saveMember(){if(!editing.value)return;saving.value=true;try{await post(`/school-admin/${schoolId.value}/members/${editing.value.id}/profile`,editing.value);editing.value=null;await load();ElMessage.success('已保存')}catch(e){notifyError(e)}finally{saving.value=false}}
async function saveNotice(){if(!notice.value)return;saving.value=true;try{await post(`/school-admin/${schoolId.value}/announcements${notice.value.id?`/${notice.value.id}`:''}`,notice.value);notice.value=null;await load();ElMessage.success('已保存')}catch(e){notifyError(e)}finally{saving.value=false}}
async function remove(n:Notice){const id=schoolId.value;try{await ElMessageBox.confirm(`删除公告「${n.title}」？`,'删除校内公告',{type:'warning'})}catch{return}try{await del(`/school-admin/${id}/announcements/${n.id}`);await load()}catch(e){notifyError(e)}}
function search(){page.value=1;void load()}
</script>
<template>
 <div class="page mx-auto max-w-[1100px] flex flex-col gap-4">
  <PageHeader title="学校管理" description="管理负责学校的成员与班级，可查看本校已审核学生的非公开档案；公告由平台管理员发布。" />
  <el-select v-model="schoolId" :disabled="saving" aria-label="当前管理学校" class="max-w-full !w-80"><el-option v-for="s in schools" :key="s.id" :value="s.id" :label="s.name" /></el-select>
  <el-empty v-if="!schools.length" description="暂无负责的学校，请联系平台管理员授权。" />
  <template v-else>
   <CohortLeaderGrants v-if="canMembers && schoolId" :school-id="schoolId" />
   <el-radio-group v-model="tab" :disabled="saving" aria-label="管理内容"><el-radio-button v-if="canMembers" value="members">成员与班级</el-radio-button><el-radio-button v-if="canNotices" value="notices">校内公告</el-radio-button></el-radio-group>
   <SectionCard v-if="tab==='members'" title="成员与班级">
    <div class="flex flex-wrap gap-2 mb-4"><el-input v-model="query" aria-label="搜索成员" placeholder="姓名、用户名、学号或班级" class="!w-64" @keyup.enter="search" /><el-select v-model="status" aria-label="审核状态" placeholder="全部状态" class="!w-32" @change="search"><el-option label="全部状态" value=""/><el-option v-for="(v,k) in label" :key="k" :value="k" :label="v"/></el-select><el-button @click="search">查询</el-button></div>
    <div v-loading="loading" class="min-h-24">
     <el-empty v-if="!members.length && !loading" description="暂无符合条件的成员"/>
     <div v-for="m in members" :key="m.id" class="flex flex-wrap items-center justify-between gap-3 border-b border-line py-4">
      <div class="min-w-0"><div class="font-semibold text-ink"><RouterLink v-if="m.status==='APPROVED' && (!m.platformAdmin || auth.isAdmin)" :to="`/users/${m.userId}`" class="text-brand-600">{{m.displayName}}</RouterLink><span v-else>{{m.displayName}}</span> <el-tag size="small">{{label[m.status]}}</el-tag></div><div class="text-sm text-muted break-words">{{m.enrollmentYear || '未填写'}} 级 · {{m.className || '未填写班级'}} · 学号 {{m.studentNo || '未填写'}}</div></div>
      <div v-if="!m.platformAdmin || auth.isAdmin" class="flex gap-2"><template v-if="m.status==='PENDING'"><el-button size="small" type="primary" @click="review(m,true)">通过</el-button><el-button size="small" @click="review(m,false)">拒绝</el-button></template><el-button size="small" @click="editing={...m}">修改资料</el-button></div>
     </div>
    </div>
    <el-pagination v-model:current-page="page" :page-size="50" :total="total" layout="prev, pager, next" class="mt-4" @current-change="load"/>
   </SectionCard>
   <SectionCard v-else title="校内公告" description="发布后只向本校已审核成员和负责本校的管理员展示。">
    <el-button type="primary" @click="notice={id:0,title:'',content:'',published:false}">新增公告</el-button>
    <div v-loading="loading" class="min-h-24"><el-empty v-if="!notices.length && !loading" description="暂无校内公告"/><div v-for="n in notices" :key="n.id" class="border-b border-line py-4"><div class="font-semibold text-ink">{{n.title}} <el-tag size="small">{{n.published?'已发布':'草稿'}}</el-tag></div><p class="whitespace-pre-wrap break-words text-sm text-muted">{{n.content}}</p><el-button size="small" @click="notice={...n}">编辑</el-button><el-button size="small" type="danger" plain @click="remove(n)">删除</el-button></div></div>
   </SectionCard>
  </template>
  <ResponsiveDialog :model-value="!!editing" title="修改成员资料" @update:model-value="v=>{if(!v)editing=null}"><el-form v-if="editing" label-position="top"><el-form-item label="入学年级"><el-input-number v-model="editing.enrollmentYear" :min="2000" :max="2100"/></el-form-item><el-form-item label="班级"><el-input v-model="editing.className" maxlength="64"/></el-form-item><el-form-item label="学号"><el-input v-model="editing.studentNo" maxlength="32"/></el-form-item><el-button type="primary" :loading="saving" @click="saveMember">保存</el-button></el-form></ResponsiveDialog>
  <ResponsiveDialog :model-value="!!notice" title="校内公告" @update:model-value="v=>{if(!v)notice=null}"><el-form v-if="notice" label-position="top"><el-form-item label="标题"><el-input v-model="notice.title" maxlength="120"/></el-form-item><el-form-item label="内容"><el-input v-model="notice.content" type="textarea" :rows="6" maxlength="10000"/></el-form-item><el-form-item label="发布"><el-switch v-model="notice.published"/></el-form-item><el-button type="primary" :loading="saving" @click="saveNotice">保存</el-button></el-form></ResponsiveDialog>
 </div>
</template>
