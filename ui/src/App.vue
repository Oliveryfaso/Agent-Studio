<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import {
  emptySkillForm,
  exampleSkillForm,
  serializeSkillForm,
  validateSkillForm,
} from './guidedRequest'

type Phase = 'idle' | 'previewing' | 'applying' | 'rolling-back'

interface ApiErrorBody {
  error?: { code?: string; retryable?: boolean }
  requestId?: string
}

interface PreviewResponse extends ApiErrorBody {
  authorizedRoot: string | null
  targetPath: string | null
  exactReplacementDiff: string | null
  diffIncluded: boolean
  plan: {
    planId: string
    status: 'READY_REPLACE' | 'NO_CHANGE' | 'BLOCKED'
    logicalPath: string
    approvalToken: string | null
    blockedReason: string | null
    applyEligible: boolean
  }
}

interface ApplyResponse extends ApiErrorBody {
  status: 'VERIFIED_APPLIED' | 'APPROVAL_MISMATCH' | 'STALE_PREIMAGE' | 'BLOCKED' | 'WRITE_FAILED' | 'RECOVERY_REQUIRED'
  transactionId: string | null
  planId: string
  logicalPath: string
  targetWritesPerformed: boolean
  stateWritesPerformed: boolean
  rollbackAvailable: boolean
  recoveryRequired: boolean
  detail: string
}

interface RollbackResponse extends ApiErrorBody {
  status: 'ROLLED_BACK' | 'ALREADY_ROLLED_BACK' | 'CURRENT_TARGET_CHANGED' | 'INVALID_TRANSACTION' | 'WRITE_FAILED' | 'RECOVERY_REQUIRED'
  transactionId: string
  logicalPath: string
  targetWritesPerformed: boolean
  stateWritesPerformed: boolean
  recoveryRequired: boolean
  detail: string
}

interface RollbackAnchor {
  transactionId: string
  workspacePath: string
  targetPath: string
}

const sessionToken = ref(readAndForgetToken())
const workspacePath = ref('')
const inputMode = ref<'form' | 'advanced'>('form')
const skillForm = reactive(emptySkillForm())
const advancedRequest = ref('')
const formIssues = computed(() => validateSkillForm(skillForm))
const generatedRequest = computed(() => serializeSkillForm(skillForm))
const guidedRequest = computed(() => inputMode.value === 'form' ? generatedRequest.value : advancedRequest.value)
const targetSkillLogicalPath = computed(() => /^[a-z0-9]+(?:-[a-z0-9]+)*$/.test(skillForm.name.trim())
  ? `.agents/skills/${skillForm.name.trim()}/SKILL.md`
  : '')
const phase = ref<Phase>('idle')
const preview = ref<PreviewResponse | null>(null)
const applyReceipt = ref<ApplyResponse | null>(null)
const rollbackReceipt = ref<RollbackResponse | null>(null)
const rollbackAnchor = ref<RollbackAnchor | null>(null)
const previewSignature = ref('')
const confirmed = ref(false)
const wrapDiff = ref(false)
const message = ref('请选择项目，并填写要更新的 Skill。')
const messageTone = ref<'neutral' | 'success' | 'warning' | 'danger'>('neutral')

const busy = computed(() => phase.value !== 'idle')
const currentSignature = computed(() => `${workspacePath.value}\u0000${guidedRequest.value}`)
const canPreview = computed(() => Boolean(
  sessionToken.value
  && workspacePath.value.trim()
  && guidedRequest.value.trim()
  && (inputMode.value === 'advanced' || formIssues.value.length === 0)
  && !busy.value,
))
const hasActiveRollback = computed(() => Boolean(
  rollbackAnchor.value
  && !['ROLLED_BACK', 'ALREADY_ROLLED_BACK'].includes(rollbackReceipt.value?.status ?? ''),
))
const canApply = computed(() => Boolean(
  preview.value?.plan.status === 'READY_REPLACE'
  && preview.value.plan.approvalToken
  && previewSignature.value === currentSignature.value
  && confirmed.value
  && !hasActiveRollback.value
  && !busy.value,
))
const canRollback = computed(() => Boolean(
  hasActiveRollback.value
  && !busy.value,
))
const recordStatusClass = computed(() => (
  rollbackReceipt.value?.status ?? applyReceipt.value?.status ?? 'VERIFIED_APPLIED'
).toLowerCase())
const recordStatusLabel = computed(() => rollbackReceipt.value
  ? rollbackStatusLabel(rollbackReceipt.value.status)
  : applyReceipt.value ? applyStatusLabel(applyReceipt.value.status) : '')

watch([workspacePath, guidedRequest], () => {
  if (preview.value && previewSignature.value !== currentSignature.value) {
    preview.value = null
    previewSignature.value = ''
    confirmed.value = false
    setMessage('内容已修改，请重新生成预览。', 'warning')
  }
})

function readAndForgetToken(): string {
  const parameters = new URLSearchParams(location.hash.replace(/^#/, ''))
  const token = parameters.get('token')?.trim() ?? ''
  if (location.hash) history.replaceState(null, '', `${location.pathname}${location.search}`)
  return token
}

function setMessage(text: string, tone: typeof messageTone.value): void {
  message.value = text
  messageTone.value = tone
}

function selectInputMode(mode: 'form' | 'advanced'): void {
  if (mode === 'advanced' && !advancedRequest.value.trim()) {
    advancedRequest.value = generatedRequest.value
  }
  inputMode.value = mode
}

function fillExample(): void {
  Object.assign(skillForm, exampleSkillForm())
  setMessage('示例已填入，可以直接预览，也可以按项目情况修改。', 'neutral')
}

function validateInputs(): boolean {
  if (!sessionToken.value) {
    setMessage('页面未连接到本地服务，请重新打开启动命令给出的链接。', 'danger')
    return false
  }
  if (!workspacePath.value.trim()) {
    setMessage('请输入项目文件夹的绝对路径。', 'danger')
    return false
  }
  if (!guidedRequest.value.trim()) {
    setMessage('请填写变更配置。', 'danger')
    return false
  }
  if (inputMode.value === 'form' && formIssues.value.length) {
    setMessage(formIssues.value[0], 'danger')
    return false
  }
  return true
}

async function post<T>(action: 'preview' | 'apply' | 'rollback', body: Record<string, unknown>): Promise<T> {
  const response = await fetch(`/api/v1/skill-changes/${action}`, {
    method: 'POST',
    headers: {
      Authorization: `Bearer ${sessionToken.value}`,
      'Content-Type': 'application/json',
    },
    body: JSON.stringify(body),
  })
  const result = await response.json() as T & ApiErrorBody
  if (result.error?.code) throw new Error(result.error.code)
  return result
}

async function requestPreview(): Promise<void> {
  if (!validateInputs()) return
  phase.value = 'previewing'
  confirmed.value = false
  preview.value = null
  previewSignature.value = ''
  setMessage('正在读取文件并生成预览…', 'neutral')
  try {
    const result = await post<PreviewResponse>('preview', {
      hostId: 'codex',
      workspacePath: workspacePath.value.trim(),
      guidedRequest: guidedRequest.value,
      includeDiff: true,
    })
    preview.value = result
    previewSignature.value = currentSignature.value
    if (result.plan.status === 'BLOCKED') {
      setMessage(`无法处理：${detailLabel(result.plan.blockedReason ?? 'BLOCKED')}`, 'danger')
    } else if (result.plan.status === 'NO_CHANGE') {
      setMessage('文件内容已经符合这份配置，无需更改。', 'success')
    } else if (hasActiveRollback.value) {
      setMessage('预览已生成。请先处理上一条恢复记录，再应用新的更改。', 'warning')
    } else {
      setMessage('预览已生成，请检查目标文件和差异。', 'success')
    }
  } catch (error) {
    setMessage(errorMessage(error), 'danger')
  } finally {
    phase.value = 'idle'
  }
}

async function applyChange(): Promise<void> {
  if (!canApply.value || !preview.value?.plan.approvalToken) {
    setMessage('请重新预览并确认文件差异。', 'danger')
    return
  }
  phase.value = 'applying'
  setMessage('正在写入并校验文件…', 'neutral')
  const approvedWorkspacePath = workspacePath.value.trim()
  const approvedTargetPath = preview.value.targetPath ?? preview.value.plan.logicalPath
  try {
    const result = await post<ApplyResponse>('apply', {
      hostId: 'codex',
      workspacePath: workspacePath.value.trim(),
      guidedRequest: guidedRequest.value,
      approvalToken: preview.value.plan.approvalToken,
    })
    applyReceipt.value = result
    if (result.status === 'VERIFIED_APPLIED' && result.transactionId && result.rollbackAvailable) {
      rollbackAnchor.value = {
        transactionId: result.transactionId,
        workspacePath: approvedWorkspacePath,
        targetPath: approvedTargetPath,
      }
      rollbackReceipt.value = null
    }
    confirmed.value = false
    preview.value = null
    previewSignature.value = ''
    const tone = result.status === 'VERIFIED_APPLIED' ? 'success'
      : result.status === 'RECOVERY_REQUIRED' ? 'danger' : 'warning'
    setMessage(applyStatus(result), tone)
  } catch (error) {
    setMessage(errorMessage(error), 'danger')
  } finally {
    phase.value = 'idle'
  }
}

async function rollbackChange(): Promise<void> {
  if (!canRollback.value || !rollbackAnchor.value) {
    setMessage('当前没有可以恢复的文件。', 'danger')
    return
  }
  phase.value = 'rolling-back'
  setMessage('正在检查并恢复原文件…', 'neutral')
  try {
    const result = await post<RollbackResponse>('rollback', {
      hostId: 'codex',
      workspacePath: rollbackAnchor.value.workspacePath,
      transactionId: rollbackAnchor.value.transactionId,
    })
    rollbackReceipt.value = result
    const tone = ['ROLLED_BACK', 'ALREADY_ROLLED_BACK'].includes(result.status) ? 'success'
      : result.status === 'RECOVERY_REQUIRED' ? 'danger' : 'warning'
    setMessage(rollbackStatus(result), tone)
  } catch (error) {
    setMessage(errorMessage(error), 'danger')
  } finally {
    phase.value = 'idle'
  }
}

function finishRollbackWindow(): void {
  if (!rollbackAnchor.value) return
  rollbackAnchor.value = null
  rollbackReceipt.value = null
  applyReceipt.value = null
  setMessage('已保留更改。恢复记录仍保存在本机状态目录中。', 'warning')
}

function applyStatus(receipt: ApplyResponse): string {
  switch (receipt.status) {
    case 'VERIFIED_APPLIED': return '更改已应用，并已保存恢复副本。'
    case 'STALE_PREIMAGE': return '预览后文件发生了变化。未写入，请重新预览。'
    case 'APPROVAL_MISMATCH': return '这份预览已经失效。未写入，请重新预览。'
    case 'BLOCKED': return `无法应用：${detailLabel(receipt.detail)}`
    case 'RECOVERY_REQUIRED': return `写入结果需要人工检查。请暂停操作并保留记录编号 ${receipt.transactionId ?? '未知'}。`
    default: return `写入失败：${detailLabel(receipt.detail)}`
  }
}

function rollbackStatus(receipt: RollbackResponse): string {
  switch (receipt.status) {
    case 'ROLLED_BACK': return '原文件已恢复。'
    case 'ALREADY_ROLLED_BACK': return '原文件此前已经恢复，无需重复操作。'
    case 'CURRENT_TARGET_CHANGED': return '文件在应用后又被其他程序修改，为避免覆盖，未执行恢复。'
    case 'RECOVERY_REQUIRED': return `恢复结果需要人工检查。请保留记录编号 ${receipt.transactionId}。`
    case 'INVALID_TRANSACTION': return '找不到对应的恢复记录，或记录与当前项目不匹配。'
    default: return `恢复失败：${detailLabel(receipt.detail)}`
  }
}

function previewStatusLabel(status: PreviewResponse['plan']['status']): string {
  return { READY_REPLACE: '可以应用', NO_CHANGE: '无需更改', BLOCKED: '无法处理' }[status]
}

function applyStatusLabel(status: ApplyResponse['status']): string {
  return {
    VERIFIED_APPLIED: '已应用',
    APPROVAL_MISMATCH: '预览已失效',
    STALE_PREIMAGE: '文件已变化',
    BLOCKED: '无法应用',
    WRITE_FAILED: '写入失败',
    RECOVERY_REQUIRED: '需要人工检查',
  }[status]
}

function rollbackStatusLabel(status: RollbackResponse['status']): string {
  return {
    ROLLED_BACK: '已恢复',
    ALREADY_ROLLED_BACK: '已恢复',
    CURRENT_TARGET_CHANGED: '文件已再次变化',
    INVALID_TRANSACTION: '找不到恢复记录',
    WRITE_FAILED: '恢复失败',
    RECOVERY_REQUIRED: '需要人工检查',
  }[status]
}

function detailLabel(value: string): string {
  const labels: Record<string, string> = {
    EXISTING_TARGET_REQUIRED: '目标 Skill 文件不存在',
    TARGET_MUST_BE_UTF8_LF_WITH_FINAL_NEWLINE: '文件格式暂不支持',
    TARGET_LINK_OR_REPARSE: '目标文件是链接或重解析点',
    TARGET_NOT_BOUNDED_REGULAR_FILE: '目标不是受支持的普通文件',
    TARGET_CHANGED_DURING_PROBE: '读取期间文件发生了变化',
    WORKSPACE_ROOT_IS_LINK: '项目文件夹不能是链接',
    WORKSPACE_ROOT_INVALID: '项目文件夹不可用',
    TARGET_OUTSIDE_ROOT: '目标文件不在项目文件夹内',
    TARGET_PARENT_MISSING_OR_UNSAFE: '目标目录不存在或不安全',
    CONTROLLED_APPLY_VERIFIED: '文件已写入并校验',
    CONTROLLED_ROLLBACK_VERIFIED: '原文件已恢复并校验',
  }
  return labels[value] ?? '操作未完成，请查看技术详情'
}

function errorMessage(error: unknown): string {
  if (error instanceof TypeError) return '无法连接本地服务。请确认服务仍在运行，然后重试。'
  if (error instanceof Error) {
    if (error.message === 'SESSION_TOKEN_INVALID') return '连接已失效，请重新打开启动命令给出的链接。'
    if (error.message === 'INPUT_INVALID') return '输入格式不正确，请检查项目路径和变更配置。'
    return `请求失败：${detailLabel(error.message)}`
  }
  return '发生未知错误，请重试。'
}
</script>

<template>
  <main class="shell">
    <header class="app-header">
      <div class="brand-mark" aria-hidden="true">AS</div>
      <div class="app-title">
        <strong>Agent Studio</strong>
        <span>Skill 文件更新</span>
      </div>
      <span class="session" :class="{ missing: !sessionToken }">
        <span class="session-dot" />{{ sessionToken ? '服务已连接' : '服务未连接' }}
      </span>
    </header>

    <div class="workspace-layout">
      <aside class="panel setup-panel" aria-labelledby="configure-title">
        <div class="section-heading">
          <span>1</span>
          <div><h1 id="configure-title">选择项目和变更内容</h1><p>所有操作都由本机服务完成。</p></div>
        </div>

        <div class="field-grid">
          <label class="field">
            <span>项目文件夹</span>
            <input v-model="workspacePath" type="text" maxlength="4096" autocomplete="off" spellcheck="false" placeholder="/Users/you/code/my-project" :disabled="busy" />
            <small>当前仅支持修改已有的 .agents/skills/&lt;名称&gt;/SKILL.md</small>
          </label>
          <div class="request-editor">
            <div class="editor-heading">
              <span>Skill 内容</span>
              <div class="mode-switch" aria-label="内容填写方式">
                <button type="button" :class="{ active: inputMode === 'form' }" :aria-pressed="inputMode === 'form'" :disabled="busy" @click="selectInputMode('form')">表单填写</button>
                <button type="button" :class="{ active: inputMode === 'advanced' }" :aria-pressed="inputMode === 'advanced'" :disabled="busy" @click="selectInputMode('advanced')">高级配置</button>
              </div>
            </div>

            <template v-if="inputMode === 'form'">
              <div class="form-tools">
                <p>每行填写一项；带“至少 3 项”的内容需要分三行填写。</p>
                <button type="button" class="text-button" :disabled="busy" @click="fillExample">填入完整示例</button>
              </div>

              <details class="form-section" open>
                <summary>基本信息</summary>
                <div class="form-fields">
                  <label class="field"><span>文件名称</span><input v-model="skillForm.name" type="text" maxlength="63" autocomplete="off" spellcheck="false" placeholder="review-api-change" :disabled="busy" /><small>{{ targetSkillLogicalPath || '小写英文、数字和连字符' }}</small></label>
                  <label class="field"><span>用途说明</span><input v-model="skillForm.description" class="plain-input" type="text" maxlength="2048" placeholder="检查 API 变更的兼容性与风险" :disabled="busy" /></label>
                  <label class="field"><span>完成目标</span><textarea v-model="skillForm.goal" class="short-textarea" rows="2" maxlength="2048" placeholder="最终要交付什么结果" :disabled="busy" /></label>
                  <div class="paired-fields">
                    <label class="field"><span>输入</span><textarea v-model="skillForm.inputs" class="list-textarea" rows="3" placeholder="本次变更的文件&#10;相关接口契约" :disabled="busy" /><small>每行一项</small></label>
                    <label class="field"><span>输出</span><textarea v-model="skillForm.outputs" class="list-textarea" rows="3" placeholder="按优先级排列的审查结论" :disabled="busy" /><small>每行一项</small></label>
                  </div>
                </div>
              </details>

              <details class="form-section" open>
                <summary>使用范围</summary>
                <div class="form-fields">
                  <label class="field"><span>什么时候使用</span><textarea v-model="skillForm.triggers" class="list-textarea" rows="3" placeholder="用户要求审查接口或数据结构变更" :disabled="busy" /><small>每行一个场景</small></label>
                  <label class="field"><span>什么时候不使用</span><textarea v-model="skillForm.exclusions" class="list-textarea" rows="3" placeholder="只涉及页面样式或宣传文案" :disabled="busy" /><small>每行一个场景</small></label>
                  <label class="field"><span>边界示例</span><textarea v-model="skillForm.boundaries" class="list-textarea" rows="3" placeholder="仅调整按钮颜色时不应使用" :disabled="busy" /><small>每行一个容易混淆的例子</small></label>
                  <label class="field"><span>应当使用的例子</span><textarea v-model="skillForm.positiveExamples" class="list-textarea" rows="4" placeholder="审查新增的订单接口&#10;检查数据库迁移是否兼容&#10;核对接口响应字段的变更" :disabled="busy" /><small>至少 3 项，每行一项</small></label>
                  <label class="field"><span>不应使用的例子</span><textarea v-model="skillForm.negativeExamples" class="list-textarea" rows="4" placeholder="调整登录页间距&#10;撰写发布公告&#10;重命名图片文件" :disabled="busy" /><small>至少 3 项，每行一项</small></label>
                </div>
              </details>

              <details class="form-section" open>
                <summary>执行与检查</summary>
                <div class="form-fields">
                  <label class="field"><span>执行步骤</span><textarea v-model="skillForm.steps" class="list-textarea" rows="4" placeholder="检查接口契约和调用方&#10;检查测试与迁移路径&#10;按优先级整理发现" :disabled="busy" /><small>按执行顺序，每行一步</small></label>
                  <label class="field"><span>完成标准</span><textarea v-model="skillForm.completion" class="short-textarea" rows="2" maxlength="2048" placeholder="怎样判断工作已经完成" :disabled="busy" /></label>
                  <label class="field"><span>检查方法</span><textarea v-model="skillForm.validations" class="list-textarea" rows="3" placeholder="确认每条结论都引用具体输入" :disabled="busy" /><small>每行一项</small></label>
                </div>
              </details>

              <div class="form-check" :class="{ ready: formIssues.length === 0 }" role="status">
                <strong>{{ formIssues.length === 0 ? '表单已完整' : `还需补充 ${formIssues.length} 项` }}</strong>
                <ul v-if="formIssues.length">
                  <li v-for="issue in formIssues.slice(0, 3)" :key="issue">{{ issue }}</li>
                  <li v-if="formIssues.length > 3">以及另外 {{ formIssues.length - 3 }} 项</li>
                </ul>
              </div>
            </template>

            <label v-else class="field advanced-editor">
              <textarea v-model="advancedRequest" rows="14" maxlength="32768" spellcheck="false" placeholder="粘贴 key: value 配置" :disabled="busy" />
              <small>用于已有配置或未在表单中提供的字段 · {{ advancedRequest.length.toLocaleString() }} / 32,768</small>
            </label>
          </div>
        </div>

        <div class="action-row">
          <button class="primary" type="button" :disabled="!canPreview" @click="requestPreview">
            <span v-if="phase === 'previewing'" class="spinner" aria-hidden="true" />
            {{ phase === 'previewing' ? '正在预览…' : '预览变更' }}
          </button>
          <p>此操作不会修改文件</p>
        </div>
      </aside>

      <div class="result-column">
        <div class="announcement" :class="messageTone" role="status" aria-live="polite" aria-atomic="true">
          <span aria-hidden="true">{{ messageTone === 'success' ? '✓' : messageTone === 'danger' ? '!' : '•' }}</span>
          {{ message }}
        </div>

        <section class="panel" aria-labelledby="review-title">
          <div class="section-heading">
            <span>2</span>
            <div><h2 id="review-title">查看更改</h2><p>修改左侧内容后需要重新预览。</p></div>
          </div>

          <div v-if="!preview" class="empty-state">
            <div class="empty-icon" aria-hidden="true">⌘</div>
            <h3>暂无预览</h3>
            <p>生成预览后，这里会显示目标文件和完整差异。</p>
          </div>

          <template v-else>
            <dl class="metadata">
              <div><dt>状态</dt><dd><span class="pill" :class="preview.plan.status.toLowerCase()">{{ previewStatusLabel(preview.plan.status) }}</span></dd></div>
              <div><dt>目标文件</dt><dd class="mono">{{ preview.targetPath ?? preview.plan.logicalPath }}</dd></div>
            </dl>
            <details class="technical-details">
              <summary>技术详情</summary>
              <div><span>预览编号</span><code>{{ preview.plan.planId }}</code></div>
              <div><span>状态代码</span><code>{{ preview.plan.status }}</code></div>
              <div v-if="preview.plan.blockedReason"><span>原因代码</span><code>{{ preview.plan.blockedReason }}</code></div>
            </details>

            <div v-if="preview.exactReplacementDiff" class="diff-card">
              <div class="diff-toolbar">
                <div><strong>文件差异</strong><small>检查将删除和新增的内容</small></div>
                <label class="switch"><input v-model="wrapDiff" type="checkbox" /><span>自动换行</span></label>
              </div>
              <pre tabindex="0" :class="{ wrapped: wrapDiff }" aria-label="完整文件差异"><code>{{ preview.exactReplacementDiff }}</code></pre>
            </div>

            <div v-if="preview.plan.status === 'READY_REPLACE'" class="approval">
              <label>
                <input v-model="confirmed" type="checkbox" />
                <span><strong>我已检查目标文件和以上差异</strong><small>应用后会替换这个 SKILL.md，并保存恢复副本。</small></span>
              </label>
              <button class="danger-button" type="button" :disabled="!canApply" @click="applyChange">
                <span v-if="phase === 'applying'" class="spinner" aria-hidden="true" />
                {{ phase === 'applying' ? '正在应用…' : '应用更改' }}
              </button>
            </div>
          </template>
        </section>

        <section class="panel" aria-labelledby="receipt-title">
          <div class="section-heading">
            <span>3</span>
            <div><h2 id="receipt-title">应用记录</h2><p>恢复前会确认文件没有被其他程序修改。</p></div>
          </div>

          <div v-if="!applyReceipt && !rollbackAnchor" class="empty-state compact">
            <h3>暂无应用记录</h3><p>成功应用更改后，可以在这里恢复原文件。</p>
          </div>
          <div v-else class="receipt-card">
            <template v-if="applyReceipt">
              <div class="receipt-top">
                <span class="pill" :class="recordStatusClass">{{ recordStatusLabel }}</span>
                <span v-if="applyReceipt.recoveryRequired" class="recovery">需要人工检查</span>
              </div>
              <dl class="receipt-list">
                <div><dt>记录编号</dt><dd class="mono">{{ applyReceipt.transactionId ?? '—' }}</dd></div>
                <div><dt>应用时写入文件</dt><dd>{{ applyReceipt.targetWritesPerformed ? '是' : '否' }}</dd></div>
                <div><dt>可以恢复</dt><dd>{{ hasActiveRollback ? '是' : '否' }}</dd></div>
              </dl>
              <details class="technical-details">
                <summary>技术详情</summary>
                <div><span>状态代码</span><code>{{ applyReceipt.status }}</code></div>
                <div><span>结果代码</span><code>{{ applyReceipt.detail }}</code></div>
                <div v-if="rollbackReceipt"><span>恢复状态</span><code>{{ rollbackReceipt.status }} / {{ rollbackReceipt.detail }}</code></div>
              </details>
            </template>
            <div v-if="rollbackAnchor" class="rollback-anchor">
              <strong>可恢复文件</strong>
              <span class="mono">{{ rollbackAnchor.targetPath }}</span>
              <small v-if="workspacePath.trim() !== rollbackAnchor.workspacePath">当前项目输入已切换；恢复操作仍只作用于上方文件。</small>
              <div v-if="rollbackReceipt" class="rollback-result">
                <strong>{{ rollbackStatusLabel(rollbackReceipt.status) }}</strong><span>{{ rollbackStatus(rollbackReceipt) }}</span>
              </div>
              <div class="rollback-actions">
                <button class="secondary" type="button" :disabled="!canRollback" @click="rollbackChange">
                  <span v-if="phase === 'rolling-back'" class="spinner dark" aria-hidden="true" />
                  {{ phase === 'rolling-back' ? '正在恢复…' : '恢复原文件' }}
                </button>
                <button v-if="hasActiveRollback" class="quiet-button" type="button" :disabled="busy" @click="finishRollbackWindow">
                  保留更改，不再显示恢复操作
                </button>
              </div>
            </div>
          </div>
        </section>
      </div>
    </div>

    <footer><span>Agent Studio</span><span>仅连接本机 · 每次修改一个 Skill</span></footer>
  </main>
</template>
