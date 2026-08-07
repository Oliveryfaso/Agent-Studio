<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
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

type CategoryId = 'LIBRARY_API_REFERENCE' | 'PRODUCT_VALIDATION' | 'DATA_QUERY_ANALYSIS'
  | 'BUSINESS_WORKFLOW_AUTOMATION' | 'CODE_SCAFFOLDING' | 'CODE_QUALITY_REVIEW'
  | 'CI_CD_DEPLOYMENT' | 'RUNBOOK_TROUBLESHOOTING' | 'INFRASTRUCTURE_OPERATIONS'

interface SkillClassificationResponse extends ApiErrorBody {
  classifierProfileId: string
  status: 'COMPLETE' | 'PARTIAL'
  contentIncluded: false
  writesPerformed: false
  llmUsed: false
  categories: CategoryId[]
  skills: Array<{
    name: string
    logicalPath: string
    sourceSha256: string
    category: CategoryId | null
    confidence: 'HIGH' | 'MEDIUM' | 'UNCLASSIFIED'
    score: number
    margin: number
    evidenceSources: Array<'NAME' | 'DESCRIPTION'>
  }>
  unclassifiedCount: number
}

const taxonomyCategories: Array<{
  id: CategoryId; name: string; shortName: string; mark: string; description: string
}> = [
  { id: 'LIBRARY_API_REFERENCE', name: '库和 API 参考', shortName: 'API 参考', mark: '{}', description: '库、SDK、API 与 CLI 的使用说明' },
  { id: 'PRODUCT_VALIDATION', name: '产品验证', shortName: '产品验证', mark: '✓', description: '端到端流程、验收与浏览器验证' },
  { id: 'DATA_QUERY_ANALYSIS', name: '数据查询分析', shortName: '数据分析', mark: 'Σ', description: 'SQL、指标、漏斗和数据诊断' },
  { id: 'BUSINESS_WORKFLOW_AUTOMATION', name: '业务流程自动化', shortName: '流程自动化', mark: '↻', description: '工单、报告与重复业务流程' },
  { id: 'CODE_SCAFFOLDING', name: '代码脚手架', shortName: '代码脚手架', mark: '<>', description: '项目模板、样板与初始化流程' },
  { id: 'CODE_QUALITY_REVIEW', name: '代码质量与审查', shortName: '质量审查', mark: '◎', description: '代码审查、安全审计与静态检查' },
  { id: 'CI_CD_DEPLOYMENT', name: 'CI/CD 与部署', shortName: 'CI/CD', mark: '⇧', description: '流水线、发布和部署工作' },
  { id: 'RUNBOOK_TROUBLESHOOTING', name: 'Runbook 排障手册', shortName: '排障手册', mark: '!', description: '告警、事故响应与根因分析' },
  { id: 'INFRASTRUCTURE_OPERATIONS', name: '基础设施运维', shortName: '基础运维', mark: '⌂', description: '集群、资源与例行维护操作' },
]

interface PreviewResponse extends ApiErrorBody {
  candidateMode: 'GUIDED_TEMPLATE' | 'RAW_SKILL_MD'
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
  candidateMode: 'GUIDED_TEMPLATE' | 'RAW_SKILL_MD'
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

interface WorkspacePickerResponse extends ApiErrorBody {
  status: 'SELECTED' | 'CANCELLED' | 'UNAVAILABLE' | 'BUSY'
  workspacePath: string | null
}

interface RollbackAnchor {
  transactionId: string
  workspacePath: string
  targetPath: string
  operation: 'CREATE' | 'UPDATE'
}

const sessionToken = ref(readAndForgetToken())
const activeView = ref<'library' | 'editor'>('library')
const workspacePath = ref('')
const workspacePickerPhase = ref<'idle' | 'picking'>('idle')
const workspacePickerNotice = ref('')
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
const loadedFormSignature = ref('')
const loadedAdvancedSignature = ref('')
const loadedRawSignature = ref('')
const pendingInventorySkill = ref<SkillInventoryResponse['skills'][number] | null>(null)
const pendingOperationMode = ref<'update' | 'create' | null>(null)
const taxonomyPhase = ref<'idle' | 'classifying' | 'sorting' | 'ready' | 'error'>('idle')
const taxonomyReport = ref<SkillClassificationResponse | null>(null)
const taxonomyError = ref('')
const selectedBucket = ref<CategoryId | null>(null)
const selectedCatalogSkillName = ref('')
const draggedCatalogSkillName = ref('')
const manualCategories = reactive<Record<string, CategoryId | 'UNCLASSIFIED'>>({})
const pendingCatalogEditorSkill = ref<SkillInventoryResponse['skills'][number] | null>(null)
const catalogDetailElement = ref<HTMLElement | null>(null)
let catalogDetailTrigger: HTMLElement | null = null
const operationMode = ref<'update' | 'create'>('update')
let inventorySequence = 0
let contentSequence = 0
const inputMode = ref<'form' | 'advanced' | 'raw'>('form')
const skillForm = reactive(emptySkillForm())
const advancedRequest = ref('')
const formIssues = computed(() => validateSkillForm(skillForm))
const generatedRequest = computed(() => serializeSkillForm(skillForm))
const emptyFormRequest = serializeSkillForm(emptySkillForm())
const guidedRequest = computed(() => inputMode.value === 'form' ? generatedRequest.value : advancedRequest.value)
const rawByteSize = computed(() => new TextEncoder().encode(rawSourceContent.value).length)
const rawIssues = computed(() => {
  if (inputMode.value !== 'raw') return []
  const issues: string[] = []
  if (!rawSourceContent.value) issues.push('SKILL.md 原文不能为空')
  if (rawSourceContent.value && !rawSourceContent.value.endsWith('\n')) issues.push('原文末尾需要保留换行')
  if (rawSourceContent.value.includes('\r') || rawSourceContent.value.startsWith('\ufeff')) {
    issues.push('原文需要使用 UTF-8 与 LF 换行')
  }
  if (rawByteSize.value > 128 * 1024) {
    issues.push('原文不能超过 128 KiB')
  }
  return issues
})
const advancedSkillName = computed(() => {
  const names = advancedRequest.value.split(/\r?\n/)
    .map(line => line.match(/^\s*name\s*:\s*(.+?)\s*$/i)?.[1] ?? '')
    .filter(Boolean)
  return names.length === 1 ? names[0] : ''
})
const selectedInventorySkill = computed(() => skillInventory.value?.skills
  .find(skill => skill.name === selectedInventoryName.value) ?? null)
const catalogSkills = computed(() => taxonomyReport.value?.skills.map(skill => {
  const key = `${skill.logicalPath}:${skill.sourceSha256}`
  const manuallyAssigned = Object.hasOwn(manualCategories, key)
  const override = manualCategories[key]
  return {
    ...skill,
    effectiveCategory: manuallyAssigned
      ? override === 'UNCLASSIFIED' ? null : override
      : skill.category,
    manuallyAssigned,
  }
}) ?? [])
const unclassifiedCatalogSkills = computed(() => catalogSkills.value
  .filter(skill => !skill.effectiveCategory))
const selectedCatalogSkill = computed(() => catalogSkills.value
  .find(skill => skill.name === selectedCatalogSkillName.value) ?? null)
const classifiedCatalogCount = computed(() => catalogSkills.value.length
  - unclassifiedCatalogSkills.value.length)

function skillsInCategory(category: CategoryId) {
  return catalogSkills.value.filter(skill => skill.effectiveCategory === category)
}

function taxonomyCategory(category: CategoryId) {
  return taxonomyCategories.find(candidate => candidate.id === category) ?? taxonomyCategories[0]
}
const inventorySelectionIssue = computed(() => {
  if (inventoryPhase.value !== 'ready') return ''
  const requestName = inputMode.value === 'raw' ? selectedInventoryName.value
    : inputMode.value === 'form' ? skillForm.name.trim() : advancedSkillName.value
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
const editorSignature = computed(() => inputMode.value === 'raw'
  ? `raw\u0000${rawSourceContent.value}` : `${inputMode.value}\u0000${guidedRequest.value}`)
const editorDirty = computed(() => {
  if (loadedFormSignature.value || loadedRawSignature.value) {
    return Boolean((loadedFormSignature.value && generatedRequest.value !== loadedFormSignature.value)
      || loadedAdvancedSignature.value !== advancedRequest.value
      || (loadedRawSignature.value && rawSourceContent.value !== loadedRawSignature.value))
  }
  return generatedRequest.value !== emptyFormRequest
    || Boolean(advancedRequest.value || rawSourceContent.value)
})
const currentSignature = computed(() => `${operationMode.value}\u0000${workspacePath.value}\u0000${loadedSourceSha256.value}\u0000${editorSignature.value}`)
const canPreview = computed(() => Boolean(
  sessionToken.value
  && workspacePath.value.trim()
  && (inputMode.value === 'raw' ? rawSourceContent.value : guidedRequest.value).trim()
  && inventoryPhase.value !== 'loading'
  && contentPhase.value !== 'loading'
  && (inputMode.value === 'raw' ? rawIssues.value.length === 0
    : inputMode.value === 'advanced' || formIssues.value.length === 0)
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

watch([workspacePath, guidedRequest, rawSourceContent, operationMode], () => {
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
    taxonomyPhase.value = 'idle'
    taxonomyReport.value = null
    selectedBucket.value = null
    selectedCatalogSkillName.value = ''
    for (const key of Object.keys(manualCategories)) delete manualCategories[key]
    resetLoadedContent(true)
    setMessage('项目路径已改变；当前编辑内容仍保留，请重新读取项目。', 'warning')
  }
})

watch(selectedCatalogSkillName, async name => {
  pendingCatalogEditorSkill.value = null
  if (!name) return
  await nextTick()
  catalogDetailElement.value?.focus()
})

function warnBeforeUnload(event: BeforeUnloadEvent): void {
  if (!editorDirty.value) return
  event.preventDefault()
  event.returnValue = ''
}

async function importCatalogSkills(): Promise<void> {
  taxonomyPhase.value = 'idle'
  taxonomyReport.value = null
  taxonomyError.value = ''
  selectedBucket.value = null
  selectedCatalogSkillName.value = ''
  await loadSkillInventory()
}

async function loadActiveWorkspace(): Promise<void> {
  if (activeView.value === 'library') await importCatalogSkills()
  else await loadSkillInventory()
}

async function pickWorkspaceDirectory(): Promise<void> {
  if (!sessionToken.value || workspacePickerPhase.value === 'picking') return
  workspacePickerPhase.value = 'picking'
  workspacePickerNotice.value = '请在弹出的窗口中选择项目文件夹。'
  try {
    const result = await post<WorkspacePickerResponse>('workspaces/pick', {})
    if (result.status === 'SELECTED' && result.workspacePath) {
      workspacePath.value = result.workspacePath
      await nextTick()
      workspacePickerNotice.value = '已选择项目，正在读取 Skill…'
      await loadActiveWorkspace()
      workspacePickerNotice.value = inventoryPhase.value === 'ready'
        ? '项目已读取；两个工作台共用。'
        : inventoryError.value || '项目已选择，请重新读取。'
    } else if (result.status === 'CANCELLED') {
      workspacePickerNotice.value = '已取消选择，当前路径没有改变。'
    } else if (result.status === 'BUSY') {
      workspacePickerNotice.value = '文件夹选择窗口已经打开。'
    } else {
      workspacePickerNotice.value = '当前环境不能打开选择窗口，也可以直接输入路径。'
    }
  } catch (error) {
    workspacePickerNotice.value = error instanceof Error && error.message === 'SESSION_TOKEN_INVALID'
      ? '连接已失效，请重新打开启动链接。'
      : '没有打开选择窗口，也可以直接输入路径。'
  } finally {
    workspacePickerPhase.value = 'idle'
  }
}

async function classifyCatalogSkills(): Promise<void> {
  if (!skillInventory.value || inventoryPhase.value !== 'ready') return
  taxonomyPhase.value = 'classifying'
  taxonomyError.value = ''
  selectedBucket.value = null
  selectedCatalogSkillName.value = ''
  try {
    const result = await post<SkillClassificationResponse>('skills/classifications', {
      hostId: 'codex', workspacePath: workspacePath.value.trim(),
    })
    taxonomyReport.value = result
    taxonomyPhase.value = 'sorting'
    window.setTimeout(() => {
      if (taxonomyPhase.value === 'sorting') taxonomyPhase.value = 'ready'
    }, 720)
  } catch (error) {
    taxonomyPhase.value = 'error'
    taxonomyError.value = inventoryErrorMessage(error)
  }
}

function chooseCatalogSkill(name: string, event?: Event): void {
  if (event?.currentTarget instanceof HTMLElement) catalogDetailTrigger = event.currentTarget
  selectedCatalogSkillName.value = name
}

async function closeCatalogDetail(): Promise<void> {
  const trigger = catalogDetailTrigger
  catalogDetailTrigger = null
  selectedCatalogSkillName.value = ''
  await nextTick()
  trigger?.focus()
}

function startCatalogDrag(name: string): void {
  draggedCatalogSkillName.value = name
}

function finishCatalogDrag(): void {
  draggedCatalogSkillName.value = ''
}

function assignCatalogSkill(name: string, category: CategoryId): void {
  const skill = taxonomyReport.value?.skills.find(candidate => candidate.name === name)
  if (!skill) return
  manualCategories[`${skill.logicalPath}:${skill.sourceSha256}`] = category
  draggedCatalogSkillName.value = ''
  selectedCatalogSkillName.value = name
  selectedBucket.value = category
}

function dropCatalogSkill(category: CategoryId): void {
  if (draggedCatalogSkillName.value) assignCatalogSkill(draggedCatalogSkillName.value, category)
}

function assignCatalogSkillFromSelect(name: string, event: Event): void {
  const value = (event.target as HTMLSelectElement).value
  const skill = taxonomyReport.value?.skills.find(candidate => candidate.name === name)
  if (!skill) return
  if (!value) {
    manualCategories[`${skill.logicalPath}:${skill.sourceSha256}`] = 'UNCLASSIFIED'
    selectedBucket.value = null
    return
  }
  const category = value as CategoryId
  if (taxonomyCategories.some(candidate => candidate.id === category)) {
    assignCatalogSkill(name, category)
  }
}

async function openCatalogSkillInEditor(name: string): Promise<void> {
  const skill = skillInventory.value?.skills.find(candidate => candidate.name === name)
  if (!skill?.availableForPreview) return
  if (skill.name === selectedInventoryName.value && editorDirty.value) {
    activeView.value = 'editor'
    return
  }
  if (skill.name !== selectedInventoryName.value && editorDirty.value) {
    pendingCatalogEditorSkill.value = skill
    return
  }
  activeView.value = 'editor'
  await selectInventorySkill(skill)
}

async function continueCatalogEditorLoad(): Promise<void> {
  const skill = pendingCatalogEditorSkill.value
  pendingCatalogEditorSkill.value = null
  if (!skill) return
  activeView.value = 'editor'
  await selectInventorySkill(skill)
}

onMounted(() => window.addEventListener('beforeunload', warnBeforeUnload))
onBeforeUnmount(() => window.removeEventListener('beforeunload', warnBeforeUnload))

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

function selectInputMode(mode: 'form' | 'advanced' | 'raw'): void {
  if (mode === 'raw' && sourcePresentation.value !== 'advanced-only') return
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
  if (editorDirty.value) {
    pendingOperationMode.value = mode
    setMessage('当前内容尚未应用。确认后再切换操作。', 'warning')
    return
  }
  commitOperation(mode)
}

function commitOperation(mode: 'update' | 'create'): void {
  const selectedName = selectedInventoryName.value
  operationMode.value = mode
  if (mode === 'create') {
    if (skillForm.name === selectedName) skillForm.name = ''
    selectedInventoryName.value = ''
    resetLoadedContent(true)
    selectInputMode('form')
    setMessage('填写名称和内容，预览将要创建的新文件。', 'neutral')
  } else {
    skillForm.name = selectedName
    setMessage('从项目列表中选择要更新的 Skill。', 'neutral')
  }
}

function continuePendingOperation(): void {
  const mode = pendingOperationMode.value
  pendingOperationMode.value = null
  if (mode) commitOperation(mode)
}

function cancelPendingOperation(): void {
  pendingOperationMode.value = null
  setMessage('已保留当前填写内容。', 'neutral')
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
    advancedRequest.value = ''
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
      loadedFormSignature.value = generatedRequest.value
      loadedAdvancedSignature.value = advancedRequest.value
      loadedRawSignature.value = rawSourceContent.value
      setMessage('已填入可识别内容；原文件没有保存触发测试示例，请补充后再预览。', 'warning')
    } else {
      Object.assign(skillForm, emptySkillForm(), { name: skill.name })
      sourcePresentation.value = 'advanced-only'
      selectInputMode('raw')
      loadedFormSignature.value = generatedRequest.value
      loadedAdvancedSignature.value = advancedRequest.value
      loadedRawSignature.value = rawSourceContent.value
      setMessage('这个 Skill 使用了自定义结构。可以直接编辑原文，预览后再整体替换。', 'warning')
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
  if (skill && skill.name !== selectedInventoryName.value && editorDirty.value) {
    pendingInventorySkill.value = skill
    setMessage('当前内容尚未应用。确认后再加载另一个 Skill。', 'warning')
  } else if (skill) await selectInventorySkill(skill)
  else {
    if (skillForm.name === selectedInventoryName.value) skillForm.name = ''
    selectedInventoryName.value = ''
  }
}

async function continuePendingSkillLoad(): Promise<void> {
  const skill = pendingInventorySkill.value
  pendingInventorySkill.value = null
  if (skill) await selectInventorySkill(skill)
}

function cancelPendingSkillLoad(): void {
  pendingInventorySkill.value = null
  setMessage('已保留当前填写内容。', 'neutral')
}

function resetLoadedContent(preserveEditor = false): void {
  contentSequence++
  contentPhase.value = 'idle'
  loadedSourceSha256.value = ''
  if (!preserveEditor) {
    loadedFormSignature.value = ''
    loadedAdvancedSignature.value = ''
    loadedRawSignature.value = ''
  }
  pendingInventorySkill.value = null
  pendingOperationMode.value = null
  sourcePresentation.value = preserveEditor && rawSourceContent.value
    ? 'advanced-only' : 'none'
  if (!preserveEditor) rawSourceContent.value = ''
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
  if (!(inputMode.value === 'raw' ? rawSourceContent.value : guidedRequest.value).trim()) {
    setMessage(inputMode.value === 'raw' ? '请填写 SKILL.md 原文。' : '请填写变更配置。', 'danger')
    return false
  }
  if (inputMode.value === 'raw' && rawIssues.value.length) {
    setMessage(rawIssues.value[0], 'danger')
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
    const candidate = inputMode.value === 'raw'
      ? { candidateMode: 'RAW_SKILL_MD', logicalPath: selectedInventorySkill.value?.logicalPath, rawContent: rawSourceContent.value }
      : { candidateMode: 'GUIDED_TEMPLATE', guidedRequest: guidedRequest.value }
    const result = await post<PreviewResponse>('skill-changes/preview', {
      hostId: 'codex',
      workspacePath: workspacePath.value.trim(),
      ...candidate,
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
    const candidate = inputMode.value === 'raw'
      ? { candidateMode: 'RAW_SKILL_MD', logicalPath: selectedInventorySkill.value?.logicalPath, rawContent: rawSourceContent.value }
      : { candidateMode: 'GUIDED_TEMPLATE', guidedRequest: guidedRequest.value }
    const result = await post<ApplyResponse>('skill-changes/apply', {
      hostId: 'codex',
      workspacePath: workspacePath.value.trim(),
      ...candidate,
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
    if (error.message.startsWith('RAW_')) return `原文未通过检查：${rawValidationLabel(error.message)}`
    return `请求失败：${detailLabel(error.message)}`
  }
  return '发生未知错误，请重试。'
}

function rawValidationLabel(code: string): string {
  const labels: Record<string, string> = {
    RAW_CONTENT_REQUIRED: '内容不能为空',
    RAW_CONTENT_TOO_LARGE: '内容超过 128 KiB',
    RAW_TEXT_FORMAT_INVALID: '需要使用 UTF-8、LF 换行并保留末尾换行',
    RAW_LOGICAL_PATH_INVALID: 'Skill 路径或名称不符合规则',
    RAW_FRONTMATTER_REQUIRED: '缺少有效的 YAML frontmatter',
    RAW_FRONTMATTER_TOO_LARGE: 'frontmatter 超过 16 KiB 或 128 行',
    RAW_FRONTMATTER_UNSUPPORTED: 'frontmatter 使用了暂不支持的复杂 YAML key',
    RAW_FRONTMATTER_DUPLICATE_NAME: 'frontmatter 中 name 重复',
    RAW_FRONTMATTER_DUPLICATE_DESCRIPTION: 'frontmatter 中 description 重复',
    RAW_FRONTMATTER_NAME_MISMATCH: 'frontmatter 名称与 Skill 文件夹不一致',
    RAW_FRONTMATTER_DESCRIPTION_REQUIRED: 'frontmatter 缺少用途说明',
    RAW_FRONTMATTER_DESCRIPTION_INVALID: 'frontmatter 用途说明不符合限制',
  }
  return labels[code] ?? '请检查 frontmatter 与文本格式'
}
</script>

<template>
  <main class="shell">
    <div class="app-frame">
      <aside class="app-sidebar">
        <div class="sidebar-brand">
          <div class="brand-mark" aria-hidden="true">AS</div>
          <div class="app-title"><strong>Agent Studio</strong><span>Skills 管理工作台</span></div>
        </div>

        <nav class="primary-nav" aria-label="主要工作区">
          <span>工作台</span>
          <button type="button" :class="{ active: activeView === 'library' }" @click="activeView = 'library'">技能库</button>
          <button type="button" :class="{ active: activeView === 'editor' }" @click="activeView = 'editor'">编辑与应用</button>
        </nav>

        <section class="sidebar-workspace" aria-labelledby="sidebar-workspace-title">
          <span id="sidebar-workspace-title">当前项目</span>
          <input v-model="workspacePath" type="text" maxlength="4096" autocomplete="off" spellcheck="false" placeholder="/项目/绝对路径" :title="workspacePath" :disabled="busy || workspacePickerPhase === 'picking' || inventoryPhase === 'loading' || taxonomyPhase === 'classifying'" @keydown.enter.prevent="loadActiveWorkspace" />
          <button class="sidebar-pick" type="button" :disabled="busy || workspacePickerPhase === 'picking' || inventoryPhase === 'loading' || taxonomyPhase === 'classifying'" @click="pickWorkspaceDirectory">
            <span v-if="workspacePickerPhase === 'picking'" class="spinner" aria-hidden="true" />
            {{ workspacePickerPhase === 'picking' ? '等待选择…' : '选择文件夹' }}
          </button>
          <button class="sidebar-load" type="button" :disabled="!canLoadInventory || busy || workspacePickerPhase === 'picking' || taxonomyPhase === 'classifying'" @click="loadActiveWorkspace">
            <span v-if="inventoryPhase === 'loading'" class="spinner" aria-hidden="true" />
            {{ inventoryPhase === 'loading' ? '正在读取…' : inventoryPhase === 'error' ? '重新读取' : activeView === 'library' ? '导入 Skill' : '读取 Skill' }}
          </button>
          <small>{{ workspacePickerNotice || '读取 .agents/skills；两个工作台共用。' }}</small>
        </section>

        <div class="sidebar-spacer" />
        <span class="session" :class="{ missing: !sessionToken }"><span class="session-dot" />{{ sessionToken ? '本机服务已连接' : '服务未连接' }}</span>
        <small class="sidebar-note">本机运行 · 每次修改一个 Skill</small>
      </aside>

      <div class="app-main">

    <section v-if="activeView === 'library'" class="catalog-view" aria-labelledby="catalog-title">
      <div class="panel catalog-hero">
        <div>
          <span class="eyebrow">本地 Skills</span>
          <h1 id="catalog-title">把零散技能放回合适的位置</h1>
          <p>先读取项目，再用可解释规则整理为九类。证据不足的 Skill 留给你确认，不会强行归类。</p>
        </div>
      </div>

      <div v-if="inventoryPhase === 'error'" class="panel catalog-state error-message">
        <strong>没有读取到项目</strong><span>{{ inventoryError }}</span>
        <button class="secondary compact-button" type="button" @click="importCatalogSkills">重试</button>
      </div>

      <template v-else-if="inventoryPhase === 'ready' && skillInventory">
        <div class="catalog-summary" aria-live="polite">
          <div><strong>{{ skillInventory.skills.length }}</strong><span>已导入</span></div>
          <div><strong>{{ taxonomyReport ? classifiedCatalogCount : '—' }}</strong><span>已归类</span></div>
          <div><strong>{{ taxonomyReport ? unclassifiedCatalogSkills.length : '—' }}</strong><span>待整理</span></div>
          <button class="primary" type="button" :disabled="skillInventory.skills.length === 0 || taxonomyPhase === 'classifying' || taxonomyPhase === 'sorting'" @click="classifyCatalogSkills">
            <span v-if="taxonomyPhase === 'classifying'" class="spinner" aria-hidden="true" />
            {{ taxonomyReport ? '重新分类' : taxonomyPhase === 'classifying' ? '正在分类…' : '开始分类' }}
          </button>
        </div>

        <div v-if="skillInventory.skills.length === 0" class="panel empty-state catalog-empty">
          <div class="empty-icon" aria-hidden="true">0</div><h3>这个项目还没有 Skill</h3>
          <p>可以前往“编辑与应用”创建第一个 Skill，再回来整理。</p>
          <button class="secondary" type="button" @click="activeView = 'editor'">创建 Skill</button>
        </div>

        <template v-else>
          <div v-if="skillInventory.status === 'PARTIAL' || taxonomyReport?.status === 'PARTIAL'" class="catalog-warning">
            只读取到部分结果；已确认的 Skill 仍可整理，未读取项不会被猜测分类。
          </div>
          <div v-if="taxonomyPhase === 'error'" class="catalog-warning error-message">
            分类没有完成：{{ taxonomyError }}。已导入列表仍然保留，可以重试。
          </div>

          <div v-if="taxonomyPhase === 'classifying' || taxonomyPhase === 'sorting'" class="sorting-tray" :class="{ distributing: taxonomyPhase === 'sorting' }" aria-live="polite">
            <strong>{{ taxonomyPhase === 'classifying' ? '正在判断分类' : '正在放入分类桶' }}</strong>
            <span v-for="skill in skillInventory.skills.slice(0, 12)" :key="skill.logicalPath">{{ skill.name }}</span>
            <small v-if="skillInventory.skills.length > 12">其余 {{ skillInventory.skills.length - 12 }} 个一并整理</small>
          </div>

          <div class="catalog-board">
            <div class="taxonomy-area">
              <div class="bucket-grid" :class="{ sorting: taxonomyPhase === 'sorting' }" aria-label="九类 Skill">
            <article v-for="category in taxonomyCategories" :key="category.id" class="skill-bucket" :class="{ selected: selectedBucket === category.id }" @dragover.prevent @drop="dropCatalogSkill(category.id)">
              <button class="bucket-heading" type="button" :aria-expanded="selectedBucket === category.id" @click="selectedBucket = selectedBucket === category.id ? null : category.id">
                <span class="bucket-mark" aria-hidden="true">{{ category.mark }}</span>
                <span><strong>{{ category.name }}</strong><small>{{ category.description }}</small></span>
                <b>{{ skillsInCategory(category.id).length }}</b>
              </button>
              <div class="bucket-content">
                <button v-for="(skill, index) in skillsInCategory(category.id).slice(0, 4)" :key="skill.logicalPath" class="skill-chip" :class="{ manual: skill.manuallyAssigned }" type="button" :style="{ animationDelay: `${Math.min(index, 8) * 55}ms` }" @click="chooseCatalogSkill(skill.name, $event)">
                  {{ skill.name }}
                </button>
                <span v-if="skillsInCategory(category.id).length === 0" class="bucket-empty">等待 Skill</span>
                <span v-if="skillsInCategory(category.id).length > 4" class="bucket-more desktop-more">另有 {{ skillsInCategory(category.id).length - 4 }} 个</span>
              </div>
            </article>
              </div>

              <section v-if="selectedBucket" class="bucket-browser" aria-live="polite">
            <div><span class="bucket-mark" aria-hidden="true">{{ taxonomyCategory(selectedBucket).mark }}</span><strong>{{ taxonomyCategory(selectedBucket).name }}</strong><small>共 {{ skillsInCategory(selectedBucket).length }} 个 Skill</small></div>
            <button v-for="skill in skillsInCategory(selectedBucket)" :key="skill.logicalPath" type="button" @click="chooseCatalogSkill(skill.name, $event)">{{ skill.name }}</button>
            <span v-if="skillsInCategory(selectedBucket).length === 0">这个分类暂时为空。</span>
              </section>
            </div>

          <section v-if="taxonomyReport" class="review-queue" aria-labelledby="review-queue-title">
            <div class="review-heading">
              <div><span class="eyebrow">需要你判断</span><h2 id="review-queue-title">待整理</h2><p>拖进左侧分类桶，或直接从列表中选择分类。</p></div>
              <strong>{{ unclassifiedCatalogSkills.length }}</strong>
            </div>
            <div v-if="unclassifiedCatalogSkills.length" class="review-list">
              <div v-for="skill in unclassifiedCatalogSkills" :key="skill.logicalPath" class="review-skill" draggable="true" @dragstart="startCatalogDrag(skill.name)" @dragend="finishCatalogDrag">
                <span class="drag-handle" aria-hidden="true">⋮⋮</span>
                <button class="review-skill-name" type="button" @click="chooseCatalogSkill(skill.name, $event)"><strong>{{ skill.name }}</strong><small>规则证据不足，本次未自动归类</small></button>
                <select aria-label="人工选择分类" @click.stop @change="assignCatalogSkillFromSelect(skill.name, $event)">
                  <option value="">选择分类</option>
                  <option v-for="category in taxonomyCategories" :key="category.id" :value="category.id">{{ category.name }}</option>
                </select>
              </div>
            </div>
            <p v-else class="queue-complete">所有 Skill 都已有分类。你仍可在详情中调整本次结果。</p>
          </section>
          <aside v-else class="review-queue review-awaiting">
            <span class="eyebrow">待整理</span><h2>尚未分类</h2><p>运行分类后，证据不足的 Skill 会集中出现在这里。</p>
          </aside>
          </div>

          <aside v-if="selectedCatalogSkill" ref="catalogDetailElement" class="catalog-detail" role="dialog" aria-modal="false" aria-labelledby="catalog-detail-title" tabindex="-1" @keydown.esc.prevent="closeCatalogDetail">
            <button class="detail-close" type="button" aria-label="关闭详情" @click="closeCatalogDetail">×</button>
            <span class="eyebrow">Skill 详情</span>
            <h2 id="catalog-detail-title">{{ selectedCatalogSkill.name }}</h2>
            <code>{{ selectedCatalogSkill.logicalPath }}</code>
            <dl>
              <div><dt>当前分类</dt><dd>{{ selectedCatalogSkill.effectiveCategory ? taxonomyCategory(selectedCatalogSkill.effectiveCategory).name : '待整理' }}</dd></div>
              <div><dt>分类来源</dt><dd>{{ selectedCatalogSkill.manuallyAssigned ? '人工设置（本次会话）' : selectedCatalogSkill.confidence === 'HIGH' ? '名称与说明规则' : selectedCatalogSkill.confidence === 'MEDIUM' ? '说明规则' : '未自动分类' }}</dd></div>
            </dl>
            <label class="detail-category">调整分类
              <select :value="selectedCatalogSkill.effectiveCategory ?? ''" @change="assignCatalogSkillFromSelect(selectedCatalogSkill.name, $event)">
                <option value="">移到待整理</option>
                <option v-for="category in taxonomyCategories" :key="category.id" :value="category.id">{{ category.name }}</option>
              </select>
            </label>
            <button class="primary" type="button" @click="openCatalogSkillInEditor(selectedCatalogSkill.name)">查看并编辑</button>
            <div v-if="pendingCatalogEditorSkill" class="discard-prompt" role="alert">
              <strong>当前编辑内容还没有应用</strong><span>继续会加载 {{ pendingCatalogEditorSkill.name }} 并替换编辑区草稿。</span>
              <div><button class="secondary compact-button" type="button" @click="continueCatalogEditorLoad">继续加载</button><button class="quiet-button" type="button" @click="pendingCatalogEditorSkill = null">保留草稿</button></div>
            </div>
            <button class="quiet-button" type="button" disabled title="Claude Code Skill 转换适配器尚未开放">转换到 Claude Code · 后续开放</button>
          </aside>
          <p class="classification-note">分类配置 {{ taxonomyReport?.classifierProfileId ?? '尚未运行' }} · 未使用大模型 · 本次人工调整不会修改 Skill 文件</p>
        </template>
      </template>

      <div v-else class="panel catalog-state">
        <div class="catalog-placeholder" aria-hidden="true"><span v-for="category in taxonomyCategories" :key="category.id">{{ category.mark }}</span></div>
        <strong>先导入一个本地项目</strong><p>读取完成后，九个分类桶会在这里接收 Skill。</p>
      </div>
    </section>

    <div v-else class="workspace-layout">
      <aside class="panel setup-panel" aria-labelledby="configure-title">
        <div class="section-heading">
          <span>1</span>
          <div><h1 id="configure-title">选择项目和变更内容</h1><p>所有操作都由本机服务完成。</p></div>
        </div>

        <div class="field-grid">
          <div class="field">
            <label>项目中的 Skill</label>
            <small>项目路径和读取按钮已经移到左侧；这里选择要创建或更新的文件。</small>

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
                  <button type="button" :class="{ active: operationMode === 'update' }" :aria-pressed="operationMode === 'update'" :disabled="busy || contentPhase === 'loading'" @click="selectOperation('update')">更新已有</button>
                  <button type="button" :class="{ active: operationMode === 'create' }" :aria-pressed="operationMode === 'create'" :disabled="busy || contentPhase === 'loading'" @click="selectOperation('create')">新建 Skill</button>
                </div>
                <div v-if="pendingOperationMode" class="discard-prompt" role="alert">
                  <strong>切换到{{ pendingOperationMode === 'create' ? '新建 Skill' : '更新已有' }}？</strong>
                  <span>这会离开当前尚未应用的编辑内容。</span>
                  <div>
                    <button type="button" class="secondary compact-button" @click="continuePendingOperation">继续切换</button>
                    <button type="button" class="quiet-button" @click="cancelPendingOperation">保留当前内容</button>
                  </div>
                </div>
                <template v-if="skillInventory.skills.length > 0 && operationMode === 'update'">
                  <label class="picker-select">
                    <span>要更新的 Skill</span>
                    <select :key="`${selectedInventoryName}:${pendingInventorySkill?.name ?? ''}`" :value="selectedInventoryName" :disabled="contentPhase === 'loading' || busy" @change="handleInventorySelection">
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
                  <div v-if="pendingInventorySkill" class="discard-prompt" role="alert">
                    <strong>加载 {{ pendingInventorySkill.name }}？</strong>
                    <span>这会替换当前尚未应用的编辑内容。</span>
                    <div>
                      <button type="button" class="secondary compact-button" @click="continuePendingSkillLoad">继续加载</button>
                      <button type="button" class="quiet-button" @click="cancelPendingSkillLoad">保留当前内容</button>
                    </div>
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
                <button type="button" :class="{ active: inputMode === 'form' }" :aria-pressed="inputMode === 'form'" :disabled="busy" @click="selectInputMode('form')">结构化表单</button>
                <button type="button" :class="{ active: inputMode === 'advanced' }" :aria-pressed="inputMode === 'advanced'" :disabled="busy" @click="selectInputMode('advanced')">配置文本</button>
                <button v-if="sourcePresentation === 'advanced-only'" type="button" :class="{ active: inputMode === 'raw' }" :aria-pressed="inputMode === 'raw'" :disabled="busy" @click="selectInputMode('raw')">SKILL.md 原文</button>
              </div>
            </div>

            <div v-if="sourcePresentation === 'partial-form'" class="source-notice partial-message">
              <strong>已回填可确认的字段</strong>
              <span>运行时文件不保存 3 个应当使用和 3 个不应使用的测试例；补齐后才能预览。</span>
            </div>
            <div v-else-if="sourcePresentation === 'advanced-only'" class="source-notice partial-message">
              <strong>{{ inputMode === 'raw' ? '正在编辑自定义 SKILL.md' : '正在改写为 Studio 标准模板' }}</strong>
              <span>{{ inputMode === 'raw' ? '只会替换当前文件，配套文件保持不变。' : '原文草稿仍保留，可随时切回原文模式。' }}</span>
            </div>

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

            <label v-else-if="inputMode === 'raw'" class="field advanced-editor raw-editor">
              <span>SKILL.md 原文</span>
              <textarea v-model="rawSourceContent" rows="20" maxlength="131072" spellcheck="false" :disabled="busy" />
              <small>{{ rawByteSize.toLocaleString() }} / 131,072 bytes · 只修改 {{ selectedInventorySkill?.logicalPath }}</small>
              <small v-if="rawIssues.length" class="inline-error">{{ rawIssues[0] }}</small>
            </label>

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
                <span><strong>{{ preview.plan.status === 'READY_CREATE' ? '我已检查新文件路径和以上内容' : preview.candidateMode === 'RAW_SKILL_MD' ? '我已检查整份 SKILL.md 的替换差异' : '我已检查目标文件和以上差异' }}</strong><small>{{ preview.plan.status === 'READY_CREATE' ? '创建后可以在下方撤销本次创建。' : '应用后会替换这个 SKILL.md，并保存恢复副本。' }}</small></span>
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
      </div>
    </div>
  </main>
</template>
