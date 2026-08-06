export interface SkillForm {
  name: string
  description: string
  goal: string
  inputs: string
  outputs: string
  triggers: string
  exclusions: string
  boundaries: string
  positiveExamples: string
  negativeExamples: string
  steps: string
  completion: string
  validations: string
}

const LIST_LIMIT = 16

export function emptySkillForm(): SkillForm {
  return {
    name: '',
    description: '',
    goal: '',
    inputs: '',
    outputs: '',
    triggers: '',
    exclusions: '',
    boundaries: '',
    positiveExamples: '',
    negativeExamples: '',
    steps: '',
    completion: '',
    validations: '',
  }
}

export function exampleSkillForm(): SkillForm {
  return {
    name: 'review-api-change',
    description: '检查 API 变更的兼容性、测试覆盖与迁移风险',
    goal: '输出一份范围明确、可以逐项处理的 API 变更审查结果',
    inputs: '本次变更的文件\n相关接口契约和测试',
    outputs: '按优先级排列的审查结论',
    triggers: '用户要求审查接口、数据结构或迁移变更',
    exclusions: '只涉及页面样式或宣传文案的修改',
    boundaries: '仅调整按钮颜色时不应使用这个 Skill',
    positiveExamples: '审查新增的订单接口\n检查数据库迁移是否兼容\n核对接口响应字段的变更',
    negativeExamples: '调整登录页间距\n撰写发布公告\n重命名图片文件',
    steps: '检查接口契约和调用方\n检查测试与迁移路径\n按优先级整理发现',
    completion: '每个受影响的接口都有明确结论，风险均附带处理建议',
    validations: '确认每条结论都引用具体输入\n确认没有把页面样式问题列为接口风险',
  }
}

export function splitItems(value: string): string[] {
  return value.split(/\r?\n/).map(cleanLine).filter(Boolean)
}

export function serializeSkillForm(form: SkillForm): string {
  const rows: string[] = [
    'repeated-workflow: true',
    'clear-trigger: true',
    'success-criteria: true',
    'confirmed-artifact: skill',
    'confirmed-scope: project',
  ]
  addOne(rows, 'name', form.name)
  addOne(rows, 'description', form.description)
  addOne(rows, 'goal', form.goal)
  addMany(rows, 'input', form.inputs)
  addMany(rows, 'output', form.outputs)
  addMany(rows, 'trigger', form.triggers)
  addMany(rows, 'exclusion', form.exclusions)
  addMany(rows, 'boundary-example', form.boundaries)
  addMany(rows, 'should-trigger', form.positiveExamples)
  addMany(rows, 'should-not-trigger', form.negativeExamples)
  addMany(rows, 'step', form.steps)
  addOne(rows, 'completion', form.completion)
  addMany(rows, 'validation', form.validations)
  rows.push('permission: NONE', 'risk: LOW')
  return `${rows.join('\n')}\n`
}

export function validateSkillForm(form: SkillForm): string[] {
  const issues: string[] = []
  if (!cleanLine(form.name)) issues.push('填写文件名称')
  else if (!/^[a-z0-9]+(?:-[a-z0-9]+)*$/.test(cleanLine(form.name)) || cleanLine(form.name).length > 63) {
    issues.push('文件名称需使用 1–63 位小写英文、数字和连字符')
  }
  requireOne(issues, form.description, '填写用途说明')
  requireOne(issues, form.goal, '填写完成目标')
  requireList(issues, form.inputs, 1, '至少填写 1 项输入')
  requireList(issues, form.outputs, 1, '至少填写 1 项输出')
  requireList(issues, form.triggers, 1, '至少填写 1 个适用场景')
  requireList(issues, form.exclusions, 1, '至少填写 1 个不适用场景')
  requireList(issues, form.boundaries, 1, '至少填写 1 个边界示例')
  requireList(issues, form.positiveExamples, 3, '至少填写 3 个应当使用的例子')
  requireList(issues, form.negativeExamples, 3, '至少填写 3 个不应使用的例子')
  requireList(issues, form.steps, 1, '至少填写 1 个执行步骤')
  requireOne(issues, form.completion, '填写完成标准')
  requireList(issues, form.validations, 1, '至少填写 1 个检查方法')

  for (const [label, value] of [
    ['输入', form.inputs], ['输出', form.outputs], ['适用场景', form.triggers],
    ['不适用场景', form.exclusions], ['边界示例', form.boundaries],
    ['正向例子', form.positiveExamples], ['反向例子', form.negativeExamples],
    ['执行步骤', form.steps], ['检查方法', form.validations],
  ] as const) {
    const items = splitItems(value)
    if (items.length > LIST_LIMIT) issues.push(`${label}最多填写 ${LIST_LIMIT} 项`)
    if (items.some(item => item.length > 2048)) issues.push(`${label}的单项内容不能超过 2,048 个字符`)
  }

  for (const [label, value] of [
    ['文件名称', form.name], ['用途说明', form.description], ['完成目标', form.goal],
    ['完成标准', form.completion],
  ] as const) {
    if (cleanLine(value).length > 2048) issues.push(`${label}不能超过 2,048 个字符`)
  }

  checkUnique(issues, form.positiveExamples, '应当使用的例子不能重复')
  checkUnique(issues, form.negativeExamples, '不应使用的例子不能重复')
  checkOverlap(issues, form.positiveExamples, form.negativeExamples, '正向和反向例子不能相同')
  checkOverlap(issues, form.triggers, form.exclusions, '适用和不适用场景不能相同')

  const serialized = serializeSkillForm(form)
  if (new TextEncoder().encode(serialized).length > 32 * 1024) issues.push('表单内容超过 32 KiB')
  if (serialized.trimEnd().split('\n').length > 160) issues.push('表单内容超过 160 行')
  return issues
}

function cleanLine(value: string): string {
  return value.trim().replace(/[\u0000-\u001f\u007f-\u009f]+/g, ' ').replace(/\s+/g, ' ')
}

function addOne(rows: string[], key: string, value: string): void {
  const cleaned = cleanLine(value)
  if (cleaned) rows.push(`${key}: ${cleaned}`)
}

function addMany(rows: string[], key: string, value: string): void {
  for (const item of splitItems(value)) rows.push(`${key}: ${item}`)
}

function requireOne(issues: string[], value: string, message: string): void {
  if (!cleanLine(value)) issues.push(message)
}

function requireList(issues: string[], value: string, count: number, message: string): void {
  if (splitItems(value).length < count) issues.push(message)
}

function normalized(value: string): string[] {
  return splitItems(value).map(item => item.toLocaleLowerCase().replace(/\s+/g, ' '))
}

function checkUnique(issues: string[], value: string, message: string): void {
  const items = normalized(value)
  if (new Set(items).size !== items.length) issues.push(message)
}

function checkOverlap(issues: string[], left: string, right: string, message: string): void {
  const rightItems = new Set(normalized(right))
  if (normalized(left).some(item => rightItems.has(item))) issues.push(message)
}
