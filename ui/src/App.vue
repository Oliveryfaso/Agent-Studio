<script setup lang="ts">
import { computed, ref, watch } from 'vue'

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
const guidedRequest = ref('')
const phase = ref<Phase>('idle')
const preview = ref<PreviewResponse | null>(null)
const applyReceipt = ref<ApplyResponse | null>(null)
const rollbackReceipt = ref<RollbackResponse | null>(null)
const rollbackAnchor = ref<RollbackAnchor | null>(null)
const previewSignature = ref('')
const confirmed = ref(false)
const wrapDiff = ref(false)
const message = ref('填写工作区和需求后开始预览。')
const messageTone = ref<'neutral' | 'success' | 'warning' | 'danger'>('neutral')

const busy = computed(() => phase.value !== 'idle')
const currentSignature = computed(() => `${workspacePath.value}\u0000${guidedRequest.value}`)
const canPreview = computed(() => Boolean(sessionToken.value && workspacePath.value.trim() && guidedRequest.value.trim() && !busy.value))
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

watch([workspacePath, guidedRequest], () => {
  if (preview.value && previewSignature.value !== currentSignature.value) {
    preview.value = null
    previewSignature.value = ''
    confirmed.value = false
    setMessage('输入已修改，旧预览和批准凭据已失效，请重新预览。', 'warning')
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

function validateInputs(): boolean {
  if (!sessionToken.value) {
    setMessage('会话凭据缺失。请从本地服务输出的启动链接重新进入。', 'danger')
    return false
  }
  if (!workspacePath.value.trim()) {
    setMessage('请输入本地项目的绝对路径。', 'danger')
    return false
  }
  if (!guidedRequest.value.trim()) {
    setMessage('请输入 guided request，说明要生成的 Skill。', 'danger')
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
  setMessage('正在读取目标并生成真实 Diff…', 'neutral')
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
      setMessage(`无法准备变更：${humanStatus(result.plan.blockedReason ?? 'BLOCKED')}`, 'danger')
    } else if (result.plan.status === 'NO_CHANGE') {
      setMessage('NO_CHANGE：目标内容已经与候选一致，无需写入。', 'success')
    } else if (hasActiveRollback.value) {
      setMessage('预览已就绪，但仍有一笔可回退事务。请先回退或明确保留它，再批准新变更。', 'warning')
    } else {
      setMessage('预览已就绪。请检查目标和完整 Diff，再确认应用。', 'success')
    }
  } catch (error) {
    setMessage(errorMessage(error), 'danger')
  } finally {
    phase.value = 'idle'
  }
}

async function applyChange(): Promise<void> {
  if (!canApply.value || !preview.value?.plan.approvalToken) {
    setMessage('需要一份仍然有效的预览并勾选确认。', 'danger')
    return
  }
  phase.value = 'applying'
  setMessage('正在应用并验证目标文件…', 'neutral')
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
    setMessage('当前没有可回退的已验证事务。', 'danger')
    return
  }
  phase.value = 'rolling-back'
  setMessage('正在核对当前目标并回退…', 'neutral')
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
  setMessage('已保留上一笔变更并关闭其页面回退入口；可信 state 目录中的事务记录未删除。', 'warning')
}

function applyStatus(receipt: ApplyResponse): string {
  switch (receipt.status) {
    case 'VERIFIED_APPLIED': return '应用成功并已验证。请保留事务记录，以便需要时回退。'
    case 'STALE_PREIMAGE': return 'STALE：预览后目标已变化，未应用。请重新预览。'
    case 'APPROVAL_MISMATCH': return '批准凭据已失效，未应用。请重新预览。'
    case 'BLOCKED': return `应用被阻止：${humanStatus(receipt.detail)}`
    case 'RECOVERY_REQUIRED': return `RECOVERY_REQUIRED：写入状态不确定，请停止操作并检查事务 ${receipt.transactionId ?? ''}。`
    default: return `写入失败：${humanStatus(receipt.detail)}`
  }
}

function rollbackStatus(receipt: RollbackResponse): string {
  switch (receipt.status) {
    case 'ROLLED_BACK': return '回退成功，原始文件已恢复并验证。'
    case 'ALREADY_ROLLED_BACK': return '该事务此前已经回退，无需重复操作。'
    case 'CURRENT_TARGET_CHANGED': return 'CURRENT_TARGET_CHANGED：目标在应用后又被修改，已拒绝覆盖。'
    case 'RECOVERY_REQUIRED': return `RECOVERY_REQUIRED：回退状态不确定，请检查事务 ${receipt.transactionId}。`
    case 'INVALID_TRANSACTION': return '事务不存在或与当前工作区不匹配。'
    default: return `回退失败：${humanStatus(receipt.detail)}`
  }
}

function humanStatus(value: string): string {
  return value.replaceAll('_', ' ').toLowerCase()
}

function errorMessage(error: unknown): string {
  if (error instanceof TypeError) return '无法连接本地服务。请确认服务仍在运行，然后重试。'
  if (error instanceof Error) {
    if (error.message === 'SESSION_TOKEN_INVALID') return '会话已失效，请从本地服务的新启动链接重新进入。'
    if (error.message === 'INPUT_INVALID') return '输入格式无效，请检查工作区路径和 guided request。'
    return `请求失败：${humanStatus(error.message)}`
  }
  return '发生未知错误，请重试。'
}
</script>

<template>
  <main class="shell">
    <header class="hero">
      <div class="brand-mark" aria-hidden="true">AS</div>
      <div>
        <p class="eyebrow">LOCAL SKILL WORKBENCH · CODEX</p>
        <h1>把意图变成一项<br><em>可核对的变更</em></h1>
        <p class="lede">先预览真实文件差异，再明确批准。一次只处理一个已存在的 Codex Skill。</p>
      </div>
      <span class="session" :class="{ missing: !sessionToken }">
        <span class="session-dot" />{{ sessionToken ? '本地会话已连接' : '会话凭据缺失' }}
      </span>
    </header>

    <section class="workflow" aria-labelledby="configure-title">
      <div class="section-heading">
        <span>01</span>
        <div><h2 id="configure-title">定义变更</h2><p>路径和需求都只发送到本机服务。</p></div>
      </div>

      <div class="field-grid">
        <label class="field full">
          <span>项目工作区绝对路径</span>
          <input v-model="workspacePath" type="text" maxlength="4096" autocomplete="off" spellcheck="false" placeholder="/Users/you/code/my-project" :disabled="busy" />
          <small>目标必须已存在于 .agents/skills/&lt;name&gt;/SKILL.md</small>
        </label>
        <label class="field full">
          <span>Guided request</span>
          <textarea v-model="guidedRequest" rows="13" maxlength="32768" spellcheck="false" placeholder="粘贴经过确认的 key: value 请求，例如：&#10;repeated-workflow: true&#10;clear-trigger: true&#10;success-criteria: true&#10;confirmed-artifact: skill&#10;confirmed-scope: project&#10;name: review-api-change&#10;description: Review API changes.&#10;..." :disabled="busy" />
          <small>{{ guidedRequest.length.toLocaleString() }} / 32,768 字符</small>
        </label>
      </div>

      <div class="action-row">
        <button class="primary" type="button" :disabled="!canPreview" @click="requestPreview">
          <span v-if="phase === 'previewing'" class="spinner" aria-hidden="true" />
          {{ phase === 'previewing' ? '正在准备…' : '生成真实预览' }}
        </button>
        <p>不会写入任何项目文件</p>
      </div>
    </section>

    <div class="announcement" :class="messageTone" role="status" aria-live="polite" aria-atomic="true">
      <span aria-hidden="true">{{ messageTone === 'success' ? '✓' : messageTone === 'danger' ? '!' : '•' }}</span>
      {{ message }}
    </div>

    <section class="workflow" aria-labelledby="review-title">
      <div class="section-heading">
        <span>02</span>
        <div><h2 id="review-title">核对预览</h2><p>输入变化后，旧预览会立即失效。</p></div>
      </div>

      <div v-if="!preview" class="empty-state">
        <span aria-hidden="true">↳</span>
        <h3>等待一份可验证的预览</h3>
        <p>填写上方信息后，真实目标路径和完整替换 Diff 会显示在这里。</p>
      </div>

      <template v-else>
        <dl class="metadata">
          <div><dt>状态</dt><dd><span class="pill" :class="preview.plan.status.toLowerCase()">{{ preview.plan.status }}</span></dd></div>
          <div><dt>目标</dt><dd class="mono">{{ preview.targetPath ?? preview.plan.logicalPath }}</dd></div>
          <div><dt>计划 ID</dt><dd class="mono">{{ preview.plan.planId }}</dd></div>
        </dl>

        <div v-if="preview.exactReplacementDiff" class="diff-card">
          <div class="diff-toolbar">
            <div><strong>完整替换 Diff</strong><small>逐行检查即将写入的内容</small></div>
            <label class="switch"><input v-model="wrapDiff" type="checkbox" /><span>自动换行</span></label>
          </div>
          <pre tabindex="0" :class="{ wrapped: wrapDiff }" aria-label="完整文件差异"><code>{{ preview.exactReplacementDiff }}</code></pre>
        </div>

        <div v-if="preview.plan.status === 'READY_REPLACE'" class="approval">
          <label>
            <input v-model="confirmed" type="checkbox" />
            <span><strong>我已核对目标路径和完整 Diff</strong><small>应用会替换这一个已存在的 SKILL.md，并保存可回退快照。</small></span>
          </label>
          <button class="danger-button" type="button" :disabled="!canApply" @click="applyChange">
            <span v-if="phase === 'applying'" class="spinner" aria-hidden="true" />
            {{ phase === 'applying' ? '正在应用…' : '批准并应用' }}
          </button>
        </div>
      </template>
    </section>

    <section class="workflow" aria-labelledby="receipt-title">
      <div class="section-heading">
        <span>03</span>
        <div><h2 id="receipt-title">事务与回退</h2><p>回退前会再次确认目标没有被外部修改。</p></div>
      </div>

      <div v-if="!applyReceipt && !rollbackAnchor" class="empty-state compact">
        <h3>尚无写入事务</h3><p>只有经过验证的应用才会在这里提供回退入口。</p>
      </div>
      <div v-else class="receipt-card">
        <template v-if="applyReceipt">
          <div class="receipt-top">
            <span class="pill" :class="applyReceipt.status.toLowerCase()">{{ applyReceipt.status }}</span>
            <span v-if="applyReceipt.recoveryRequired" class="recovery">需要人工恢复检查</span>
          </div>
          <dl class="receipt-list">
            <div><dt>最近尝试事务 ID</dt><dd class="mono">{{ applyReceipt.transactionId ?? '—' }}</dd></div>
            <div><dt>目标文件写入</dt><dd>{{ applyReceipt.targetWritesPerformed ? '是' : '否' }}</dd></div>
            <div><dt>本次回退可用</dt><dd>{{ applyReceipt.rollbackAvailable ? '是' : '否' }}</dd></div>
            <div><dt>详情</dt><dd>{{ applyReceipt.detail }}</dd></div>
          </dl>
        </template>
        <div v-if="rollbackAnchor" class="rollback-anchor">
          <strong>可回退目标</strong>
          <span class="mono">{{ rollbackAnchor.targetPath }}</span>
          <small v-if="workspacePath.trim() !== rollbackAnchor.workspacePath">当前输入已切换；下面的操作仍只回退上方冻结目标。</small>
          <div v-if="rollbackReceipt" class="rollback-result">
            <strong>{{ rollbackReceipt.status }}</strong><span>{{ rollbackReceipt.detail }}</span>
          </div>
          <div class="rollback-actions">
            <button class="secondary" type="button" :disabled="!canRollback" @click="rollbackChange">
              <span v-if="phase === 'rolling-back'" class="spinner dark" aria-hidden="true" />
              {{ phase === 'rolling-back' ? '正在回退…' : '回退上方目标' }}
            </button>
            <button v-if="hasActiveRollback" class="quiet-button" type="button" :disabled="busy" @click="finishRollbackWindow">
              保留变更并关闭回退入口
            </button>
          </div>
        </div>
      </div>
    </section>

    <footer><span>Agent Studio</span><span>Loopback-only · one Skill at a time</span></footer>
  </main>
</template>
