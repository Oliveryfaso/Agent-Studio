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

interface SkillInventoryResponse extends ApiErrorBody {
  status: 'COMPLETE' | 'PARTIAL'
  contentIncluded: false
  writesPerformed: false
  skills: Array<{
    name: string
    logicalPath: string
    state: 'MINIMAL_METADATA_VALID' | 'INVALID' | 'PARTIAL'
    availableForPreview: boolean
    supportingFileCount: number
    risks: string[]
  }>
  findingCounts: { warning: number; error: number; blocking: number }
}

interface SkillContentResponse extends ApiErrorBody {
  status: 'PARTIAL_FORM' | 'ADVANCED_ONLY'
  logicalPath: string
  sourceSha256: string
  byteSize: number
  rendererProfileId: string
  missingFormFields: string[]
  losses: string[]
  form: null | {
    name: string
    description: string
    goal: string
    inputs: string[]
    outputs: string[]
    triggers: string[]
    exclusions: string[]
    boundaries: string[]
    steps: string[]
    completion: string
    validations: string[]
  }
  rawContent: string
  contentIncluded: true
  writesPerformed: false
}

interface PreviewResponse extends ApiErrorBody {
  operation: 'CREATE' | 'UPDATE'
  authorizedRoot: string | null
  targetPath: string | null
  exactReplacementDiff: string | null
  diffIncluded: boolean
  plan: {
    planId: string
    status: 'READY_CREATE' | 'READY_REPLACE' | 'NO_CHANGE' | 'BLOCKED'
    logicalPath: string
    approvalToken: string | null
    blockedReason: string | null
    missingParentDirectories: string[]
    applyEligible: boolean
  }
}

interface ApplyResponse extends ApiErrorBody {
  operation: 'CREATE' | 'UPDATE'
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
  operation: 'CREATE' | 'UPDATE'
}

const sessionToken = ref(readAndForgetToken())
const workspacePath = ref('')
const inventoryPhase = ref<'idle' | 'loading' | 'ready' | 'error'>('idle')
const skillInventory = ref<SkillInventoryResponse | null>(null)
const inventoryWorkspace = ref('')
const inventoryError = ref('')
const selectedInventoryName = ref('')
const contentPhase = ref<'idle' | 'loading' | 'ready' | 'error'>('idle')
const loadedSourceSha256 = ref('')
const sourcePresentation = ref<'none' | 'partial-form' | 'advanced-only'>('none')
const rawSourceContent = ref('')
const contentError = ref('')
const operationMode = ref<'update' | 'create'>('update')
let inventorySequence = 0
let contentSequence = 0
const inputMode = ref<'form' | 'advanced'>('form')
const skillForm = reactive(emptySkillForm())
const advancedRequest = ref('')
const formIssues = computed(() => validateSkillForm(skillForm))
const generatedRequest = computed(() => serializeSkillForm(skillForm))
const guidedRequest = computed(() => inputMode.value === 'form' ? generatedRequest.value : advancedRequest.value)
const advancedSkillName = computed(() => {
  const names = advancedRequest.value.split(/\r?\n/)
    .map(line => line.match(/^\s*name\s*:\s*(.+?)\s*$/i)?.[1] ?? '')
    .filter(Boolean)
  return names.length === 1 ? names[0] : ''
})
const selectedInventorySkill = computed(() => skillInventory.value?.skills
  .find(skill => skill.name === selectedInventoryName.value) ?? null)
const inventorySelectionIssue = computed(() => {
  if (inventoryPhase.value !== 'ready') return ''
  const requestName = inputMode.value === 'form' ? skillForm.name.trim() : advancedSkillName.value
  if (operationMode.value === 'create') {
    if (!requestName) return '请填写新 Skill 的文件名称。'
    return skillInventory.value?.skills.some(skill => skill.name === requestName)
      ? '项目中已有同名 Skill，请更换名称或切换到更新模式。' : ''
  }
  if (!selectedInventorySkill.value) return '请从项目列表中选择要更新的 Skill。'
  return requestName === selectedInventorySkill.value.name
    ? '' : '配置中的文件名称与所选 Skill 不一致。'
})
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
const canLoadInventory = computed(() => Boolean(
  sessionToken.value && workspacePath.value.trim() && inventoryPhase.value !== 'loading',
))
const currentSignature = computed(() => `${operationMode.value}\u0000${workspacePath.value}\u0000${loadedSourceSha256.value}\u0000${guidedRequest.value}`)
const canPreview = computed(() => Boolean(
  sessionToken.value
  && workspacePath.value.trim()
  && guidedRequest.value.trim()
  && inventoryPhase.value !== 'loading'
  && contentPhase.value !== 'loading'
  && (inputMode.value === 'advanced' || formIssues.value.length === 0)
  && (operationMode.value === 'create' || Boolean(loadedSourceSha256.value))
  && !inventorySelectionIssue.value
  && !busy.value,
))
const hasActiveRollback = computed(() => Boolean(
  rollbackAnchor.value
  && !['ROLLED_BACK', 'ALREADY_ROLLED_BACK'].includes(rollbackReceipt.value?.status ?? ''),
))
const canApply = computed(() => Boolean(
  ['READY_CREATE', 'READY_REPLACE'].includes(preview.value?.plan.status ?? '')
  && preview.value?.plan.approvalToken
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

watch([workspacePath, guidedRequest, operationMode], () => {
  if (preview.value && previewSignature.value !== currentSignature.value) {
    preview.value = null
    previewSignature.value = ''
    confirmed.value = false
    setMessage('内容已修改，请重新生成预览。', 'warning')
  }
  if (inventoryPhase.value !== 'idle'
      && inventoryWorkspace.value !== workspacePath.value.trim()) {
    if (skillForm.name === selectedInventoryName.value) skillForm.name = ''
    inventorySequence++
    contentSequence++
    inventoryPhase.value = 'idle'
    skillInventory.value = null
    inventoryWorkspace.value = ''
    inventoryError.value = ''
    selectedInventoryName.value = ''
    resetLoadedContent()
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
  const selectedName = selectedInventoryName.value
  Object.assign(skillForm, exampleSkillForm())
  if (selectedName) skillForm.name = selectedName
  setMessage('示例已填入，可以直接预览，也可以按项目情况修改。', 'neutral')
}

function selectOperation(mode: 'update' | 'create'): void {
  if (operationMode.value === mode) return
  const selectedName = selectedInventoryName.value
  operationMode.value = mode
  if (mode === 'create') {
    if (skillForm.name === selectedName) skillForm.name = ''
    selectedInventoryName.value = ''
    resetLoadedContent()
    setMessage('填写名称和内容，预览将要创建的新文件。', 'neutral')
  } else {
    skillForm.name = selectedName
    setMessage('从项目列表中选择要更新的 Skill。', 'neutral')
  }
}

async function loadSkillInventory(): Promise<void> {
  const workspace = workspacePath.value.trim()
  if (!sessionToken.value) {
    inventoryPhase.value = 'error'
    inventoryError.value = '页面未连接到本地服务，请重新打开启动链接。'
    return
  }
  if (!workspace) {
    inventoryPhase.value = 'error'
    inventoryError.value = '请先填写项目文件夹。'
    return
  }
  const requestSequence = ++inventorySequence
  inventoryPhase.value = 'loading'
  inventoryError.value = ''
  skillInventory.value = null
  try {
    const result = await post<SkillInventoryResponse>('skills/inventory', {
      hostId: 'codex',
      workspacePath: workspace,
    })
    if (requestSequence !== inventorySequence || workspace !== workspacePath.value.trim()) return
    skillInventory.value = result
    const retained = result.skills.find(skill =>
      skill.name === selectedInventoryName.value && skill.availableForPreview)
    if (!retained) {
      if (result.skills.length > 0 && skillForm.name === selectedInventoryName.value) {
        skillForm.name = ''
      }
      selectedInventoryName.value = ''
    }
    if (result.skills.length === 0 && result.status === 'COMPLETE') {
      operationMode.value = 'create'
      setMessage('这个项目还没有 Skill，可以直接创建第一个。', 'neutral')
    } else if (operationMode.value === 'update' && !retained) {
      setMessage('项目已读取，请选择要更新的 Skill。', 'neutral')
    }
    inventoryWorkspace.value = workspace
    inventoryPhase.value = 'ready'
  } catch (error) {
    if (requestSequence !== inventorySequence || workspace !== workspacePath.value.trim()) return
    inventoryWorkspace.value = workspace
    inventoryError.value = inventoryErrorMessage(error)
    inventoryPhase.value = 'error'
  }
}

async function selectInventorySkill(skill: SkillInventoryResponse['skills'][number]): Promise<void> {
  if (!skill.availableForPreview) return
  const workspace = workspacePath.value.trim()
  const requestSequence = ++contentSequence
  contentPhase.value = 'loading'
  contentError.value = ''
  setMessage(`正在读取 ${skill.name} 的内容…`, 'neutral')
  try {
    const result = await post<SkillContentResponse>('skills/content', {
      hostId: 'codex', workspacePath: workspace, logicalPath: skill.logicalPath,
    })
    if (requestSequence !== contentSequence || workspace !== workspacePath.value.trim()) return
    operationMode.value = 'update'
    selectedInventoryName.value = skill.name
    loadedSourceSha256.value = result.sourceSha256
    rawSourceContent.value = result.rawContent
    if (result.status === 'PARTIAL_FORM' && result.form) {
      Object.assign(skillForm, {
        name: result.form.name,
        description: result.form.description,
        goal: result.form.goal,
        inputs: result.form.inputs.join('\n'),
        outputs: result.form.outputs.join('\n'),
        triggers: result.form.triggers.join('\n'),
        exclusions: result.form.exclusions.join('\n'),
        boundaries: result.form.boundaries.join('\n'),
        positiveExamples: '',
        negativeExamples: '',
        steps: result.form.steps.join('\n'),
        completion: result.form.completion,
        validations: result.form.validations.join('\n'),
      })
      sourcePresentation.value = 'partial-form'
      selectInputMode('form')
      setMessage('已填入可识别内容；原文件没有保存触发测试示例，请补充后再预览。', 'warning')
    } else {
      Object.assign(skillForm, emptySkillForm(), { name: skill.name })
      sourcePresentation.value = 'advanced-only'
      selectInputMode('form')
      setMessage('这个 Skill 使用了自定义结构。已保留原文供核对，填写表单后可预览整体替换。', 'warning')
    }
    contentPhase.value = 'ready'
  } catch (error) {
    if (requestSequence !== contentSequence || workspace !== workspacePath.value.trim()) return
    contentPhase.value = 'error'
    contentError.value = skillContentErrorMessage(error)
    setMessage('没有读取到文件内容，当前填写内容未改变。', 'danger')
  }
}

async function handleInventorySelection(event: Event): Promise<void> {
  const name = (event.target as HTMLSelectElement).value
  const skill = skillInventory.value?.skills.find(candidate => candidate.name === name)
  if (skill) await selectInventorySkill(skill)
  else {
    if (skillForm.name === selectedInventoryName.value) skillForm.name = ''
    selectedInventoryName.value = ''
  }
}

function resetLoadedContent(): void {
  contentSequence++
  contentPhase.value = 'idle'
  loadedSourceSha256.value = ''
  sourcePresentation.value = 'none'
  rawSourceContent.value = ''
  contentError.value = ''
}

function skillContentErrorMessage(error: unknown): string {
  if (error instanceof Error) {
    if (error.message === 'SKILL_NOT_AVAILABLE') return '这个 Skill 当前不可安全读取，请重新读取项目。'
    if (error.message === 'TARGET_CHANGED_SINCE_INVENTORY') return '文件刚刚发生变化，请重新选择。'
    if (error.message === 'CONTENT_NOT_UTF8') return '文件不是有效的 UTF-8 文本。'
  }
  return inventoryErrorMessage(error)
}

function inventoryStateLabel(state: SkillInventoryResponse['skills'][number]['state']): string {
  if (state === 'MINIMAL_METADATA_VALID') return '已识别'
  if (state === 'INVALID') return '预览时检查'
  return '信息不完整'
}

function inventoryErrorMessage(error: unknown): string {
  if (error instanceof Error) {
    if (error.message === 'INPUT_INVALID') return '项目路径无效，请检查后重试。'
    if (error.message === 'CORE_IO_FAILED') return '无法完整读取这个项目，请稍后重试。'
  }
  return '无法连接本地服务，请确认服务仍在运行。'
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
  if (inventorySelectionIssue.value) {
    setMessage(inventorySelectionIssue.value, 'danger')
    return false
  }
  return true
}

async function post<T>(endpoint: string, body: Record<string, unknown>): Promise<T> {
  const response = await fetch(`/api/v1/${endpoint}`, {
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
    const result = await post<PreviewResponse>('skill-changes/preview', {
      hostId: 'codex',
      workspacePath: workspacePath.value.trim(),
      guidedRequest: guidedRequest.value,
      includeDiff: true,
      operation: operationMode.value === 'create' ? 'CREATE' : 'UPDATE',
      ...(operationMode.value === 'update'
        ? { expectedPreimageSha256: loadedSourceSha256.value } : {}),
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
      setMessage(result.plan.status === 'READY_CREATE'
        ? '新文件预览已生成，请检查路径和内容。'
        : '预览已生成，请检查目标文件和差异。', 'success')
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
    const result = await post<ApplyResponse>('skill-changes/apply', {
      hostId: 'codex',
      workspacePath: workspacePath.value.trim(),
      guidedRequest: guidedRequest.value,
      approvalToken: preview.value.plan.approvalToken,
      operation: operationMode.value === 'create' ? 'CREATE' : 'UPDATE',
    })
    applyReceipt.value = result
    if (result.status === 'VERIFIED_APPLIED' && result.transactionId && result.rollbackAvailable) {
      rollbackAnchor.value = {
        transactionId: result.transactionId,
        workspacePath: approvedWorkspacePath,
        targetPath: approvedTargetPath,
        operation: result.operation,
      }
      rollbackReceipt.value = null
    }
    confirmed.value = false
    preview.value = null
    previewSignature.value = ''
    const tone = result.status === 'VERIFIED_APPLIED' ? 'success'
      : result.status === 'RECOVERY_REQUIRED' ? 'danger' : 'warning'
    if (result.status === 'VERIFIED_APPLIED') {
      await loadSkillInventory()
      const applied = skillInventory.value?.skills.find(skill => skill.logicalPath === result.logicalPath)
      if (applied) await selectInventorySkill(applied)
    }
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
    const result = await post<RollbackResponse>('skill-changes/rollback', {
      hostId: 'codex',
      workspacePath: rollbackAnchor.value.workspacePath,
      transactionId: rollbackAnchor.value.transactionId,
    })
    rollbackReceipt.value = result
    const tone = ['ROLLED_BACK', 'ALREADY_ROLLED_BACK'].includes(result.status) ? 'success'
      : result.status === 'RECOVERY_REQUIRED' ? 'danger' : 'warning'
    if (['ROLLED_BACK', 'ALREADY_ROLLED_BACK'].includes(result.status)) {
      await loadSkillInventory()
    }
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
    case 'VERIFIED_APPLIED': return receipt.operation === 'CREATE'
      ? 'Skill 已创建，并已保存撤销记录。'
      : '更改已应用，并已保存恢复副本。'
    case 'STALE_PREIMAGE': return '预览后文件发生了变化。未写入，请重新预览。'
    case 'APPROVAL_MISMATCH': return '这份预览已经失效。未写入，请重新预览。'
    case 'BLOCKED': return `无法应用：${detailLabel(receipt.detail)}`
    case 'RECOVERY_REQUIRED': return `写入结果需要人工检查。请暂停操作并保留记录编号 ${receipt.transactionId ?? '未知'}。`
    default: return `写入失败：${detailLabel(receipt.detail)}`
  }
}

function rollbackStatus(receipt: RollbackResponse): string {
  switch (receipt.status) {
    case 'ROLLED_BACK': return rollbackAnchor.value?.operation === 'CREATE'
      ? '本次创建的 Skill 已移除。' : '原文件已恢复。'
    case 'ALREADY_ROLLED_BACK': return rollbackAnchor.value?.operation === 'CREATE'
      ? '本次创建此前已经撤销。' : '原文件此前已经恢复，无需重复操作。'
    case 'CURRENT_TARGET_CHANGED': return '文件在应用后又被其他程序修改，为避免覆盖，未执行恢复。'
    case 'RECOVERY_REQUIRED': return `恢复结果需要人工检查。请保留记录编号 ${receipt.transactionId}。`
    case 'INVALID_TRANSACTION': return '找不到对应的恢复记录，或记录与当前项目不匹配。'
    default: return `恢复失败：${detailLabel(receipt.detail)}`
  }
}

function previewStatusLabel(status: PreviewResponse['plan']['status']): string {
  return { READY_CREATE: '可以创建', READY_REPLACE: '可以更新', NO_CHANGE: '无需更改', BLOCKED: '无法处理' }[status]
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
    ROLLED_BACK: rollbackAnchor.value?.operation === 'CREATE' ? '已撤销' : '已恢复',
    ALREADY_ROLLED_BACK: rollbackAnchor.value?.operation === 'CREATE' ? '已撤销' : '已恢复',
    CURRENT_TARGET_CHANGED: '文件已再次变化',
    INVALID_TRANSACTION: '找不到恢复记录',
    WRITE_FAILED: '恢复失败',
    RECOVERY_REQUIRED: '需要人工检查',
  }[status]
}

function detailLabel(value: string): string {
  const labels: Record<string, string> = {
    EXISTING_TARGET_REQUIRED: '目标 Skill 文件不存在',
    CREATE_TARGET_ALREADY_EXISTS: '目标 Skill 已存在',
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
    CONTROLLED_CREATE_ROLLBACK_VERIFIED: '本次创建已撤销并校验',
  }
  return labels[value] ?? '操作未完成，请查看技术详情'
}

function errorMessage(error: unknown): string {
  if (error instanceof TypeError) return '无法连接本地服务。请确认服务仍在运行，然后重试。'
  if (error instanceof Error) {
    if (error.message === 'SESSION_TOKEN_INVALID') return '连接已失效，请重新打开启动命令给出的链接。'
    if (error.message === 'INPUT_INVALID') return '输入格式不正确，请检查项目路径和变更配置。'
    if (error.message === 'LOADED_CONTENT_STALE') return '文件在读取后发生了变化，请重新选择 Skill 再预览。'
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
          <div class="field">
            <label for="workspace-path">项目文件夹</label>
            <div class="workspace-input-row">
              <input id="workspace-path" v-model="workspacePath" type="text" maxlength="4096" autocomplete="off" spellcheck="false" placeholder="/Users/you/code/my-project" :disabled="busy" @keydown.enter.prevent="loadSkillInventory" />
              <button class="secondary compact-button" type="button" :disabled="!canLoadInventory || busy" @click="loadSkillInventory">
                <span v-if="inventoryPhase === 'loading'" class="spinner" aria-hidden="true" />
                {{ inventoryPhase === 'loading' ? '正在读取…' : inventoryPhase === 'error' ? '重试' : '读取 Skill' }}
              </button>
            </div>
            <small>支持创建或更新一个 .agents/skills/&lt;名称&gt;/SKILL.md</small>

            <div v-if="inventoryPhase !== 'idle'" class="skill-picker" :class="inventoryPhase" :aria-busy="inventoryPhase === 'loading'">
              <p v-if="inventoryPhase === 'loading'">正在读取项目中的 Skill…</p>
              <div v-else-if="inventoryPhase === 'error'" class="picker-message error-message">
                <strong>没有读取到项目</strong><span>{{ inventoryError }}</span>
              </div>
              <template v-else-if="skillInventory">
                <div v-if="skillInventory.status === 'PARTIAL'" class="picker-message partial-message">
                  <strong>只读取到部分结果</strong><span>仍可选择下列状态完整的 Skill。</span>
                </div>
                <div v-if="skillInventory.skills.length === 0" class="picker-message">
                  <strong>这个项目还没有 Skill</strong><span>填写下面的内容即可创建第一个。</span>
                </div>
                <div v-if="skillInventory.skills.length > 0" class="mode-switch operation-switch" aria-label="文件操作">
                  <button type="button" :class="{ active: operationMode === 'update' }" :aria-pressed="operationMode === 'update'" :disabled="busy" @click="selectOperation('update')">更新已有</button>
                  <button type="button" :class="{ active: operationMode === 'create' }" :aria-pressed="operationMode === 'create'" :disabled="busy" @click="selectOperation('create')">新建 Skill</button>
                </div>
                <template v-if="skillInventory.skills.length > 0 && operationMode === 'update'">
                  <label class="picker-select">
                    <span>要更新的 Skill</span>
                    <select :value="selectedInventoryName" @change="handleInventorySelection">
                      <option value="">请选择</option>
                        <option v-for="skill in skillInventory.skills" :key="skill.logicalPath" :value="skill.name" :disabled="!skill.availableForPreview">
                        {{ skill.name }} · {{ inventoryStateLabel(skill.state) }}
                      </option>
                    </select>
                  </label>
                  <div v-if="selectedInventorySkill" class="selected-skill">
                    <strong>{{ selectedInventorySkill.name }}</strong>
                    <span class="mono">{{ selectedInventorySkill.logicalPath }}</span>
                    <small>配套文件 {{ selectedInventorySkill.supportingFileCount }} 个<span v-if="selectedInventorySkill.risks.length"> · {{ selectedInventorySkill.risks.length }} 项需留意</span></small>
                  </div>
                  <div v-if="contentPhase === 'loading'" class="picker-message content-message">
                    <strong>正在读取内容</strong><span>读取完成前不会替换当前填写内容。</span>
                  </div>
                  <div v-else-if="contentPhase === 'error'" class="picker-message error-message">
                    <strong>内容未加载</strong><span>{{ contentError }}</span>
                  </div>
                  <p v-else-if="!skillInventory.skills.some(skill => skill.availableForPreview)" class="no-selectable">没有信息完整、可以进入预览的 Skill。</p>
                </template>
                <small class="inventory-note">选择 Skill 后会读取它的 SKILL.md；配套文件不会被打开。</small>
              </template>
            </div>
          </div>
          <div class="request-editor" :inert="contentPhase === 'loading'" :aria-busy="contentPhase === 'loading'">
            <div class="editor-heading">
              <span>Skill 内容</span>
              <div class="mode-switch" aria-label="内容填写方式">
                <button type="button" :class="{ active: inputMode === 'form' }" :aria-pressed="inputMode === 'form'" :disabled="busy" @click="selectInputMode('form')">表单填写</button>
                <button type="button" :class="{ active: inputMode === 'advanced' }" :aria-pressed="inputMode === 'advanced'" :disabled="busy" @click="selectInputMode('advanced')">高级配置</button>
              </div>
            </div>

            <div v-if="sourcePresentation === 'partial-form'" class="source-notice partial-message">
              <strong>已回填可确认的字段</strong>
              <span>运行时文件不保存 3 个应当使用和 3 个不应使用的测试例；补齐后才能预览。</span>
            </div>
            <details v-else-if="sourcePresentation === 'advanced-only'" class="source-notice raw-source">
              <summary>查看现有 SKILL.md 原文</summary>
              <p>此文件不是 Studio 的标准模板，当前版本不会猜测拆分。下面只读展示原文；若填写表单继续，预览会显示整份替换差异。</p>
              <pre>{{ rawSourceContent }}</pre>
            </details>

            <template v-if="inputMode === 'form'">
              <div class="form-tools">
                <p>每行填写一项；带“至少 3 项”的内容需要分三行填写。</p>
                <button type="button" class="text-button" :disabled="busy" @click="fillExample">填入完整示例</button>
              </div>

              <details class="form-section" open>
                <summary>基本信息</summary>
                <div class="form-fields">
                  <label class="field"><span>文件名称</span><input v-model="skillForm.name" type="text" maxlength="63" autocomplete="off" spellcheck="false" placeholder="review-api-change" :disabled="busy" :readonly="inventoryPhase === 'ready' && operationMode === 'update'" /><small>{{ targetSkillLogicalPath || '小写英文、数字和连字符' }}</small></label>
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
              <small v-if="inventorySelectionIssue" class="inline-error">{{ inventorySelectionIssue }}</small>
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
            <div v-if="preview.plan.status === 'READY_CREATE'" class="creation-paths">
              <strong>将创建的目录</strong>
              <ul v-if="preview.plan.missingParentDirectories.length">
                <li v-for="path in preview.plan.missingParentDirectories" :key="path" class="mono">{{ path }}</li>
              </ul>
              <small v-else>目标目录已存在，不需要创建目录。</small>
            </div>
            <details class="technical-details">
              <summary>技术详情</summary>
              <div><span>预览编号</span><code>{{ preview.plan.planId }}</code></div>
              <div><span>状态代码</span><code>{{ preview.plan.status }}</code></div>
              <div v-if="preview.plan.blockedReason"><span>原因代码</span><code>{{ preview.plan.blockedReason }}</code></div>
            </details>

            <div v-if="preview.exactReplacementDiff" class="diff-card">
              <div class="diff-toolbar">
                <div><strong>{{ preview.plan.status === 'READY_CREATE' ? '新文件内容' : '文件差异' }}</strong><small>{{ preview.plan.status === 'READY_CREATE' ? '检查将写入的新文件' : '检查将删除和新增的内容' }}</small></div>
                <label class="switch"><input v-model="wrapDiff" type="checkbox" /><span>自动换行</span></label>
              </div>
              <pre tabindex="0" :class="{ wrapped: wrapDiff }" aria-label="完整文件差异"><code>{{ preview.exactReplacementDiff }}</code></pre>
            </div>

            <div v-if="['READY_CREATE', 'READY_REPLACE'].includes(preview.plan.status)" class="approval">
              <label>
                <input v-model="confirmed" type="checkbox" />
                <span><strong>{{ preview.plan.status === 'READY_CREATE' ? '我已检查新文件路径和以上内容' : '我已检查目标文件和以上差异' }}</strong><small>{{ preview.plan.status === 'READY_CREATE' ? '创建后可以在下方撤销本次创建。' : '应用后会替换这个 SKILL.md，并保存恢复副本。' }}</small></span>
              </label>
              <button class="danger-button" type="button" :disabled="!canApply" @click="applyChange">
                <span v-if="phase === 'applying'" class="spinner" aria-hidden="true" />
                {{ phase === 'applying' ? (preview.plan.status === 'READY_CREATE' ? '正在创建…' : '正在应用…') : (preview.plan.status === 'READY_CREATE' ? '创建 Skill' : '应用更改') }}
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
                  {{ phase === 'rolling-back' ? '正在处理…' : (rollbackAnchor.operation === 'CREATE' ? '撤销创建' : '恢复原文件') }}
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
