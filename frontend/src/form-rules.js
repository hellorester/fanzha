export const executionReasonRequired = data => ['部分执行', '拒绝执行'].includes(data.result)

export const reviewAmountRequired = data => data.decision === '通过'

export const reviewDecisions = application => application.kind === '返还' ? ['通过', '驳回'] : application.state === '待受理' ? ['受理'] : ['补正', '维持', '成立']

export function reviewDecision(application, requested = '') {
  const choices = reviewDecisions(application)
  return choices.includes(requested) ? requested : choices[0]
}

export const configFieldLimits = Object.freeze({
  medium: { min: 1, max: 100 },
  high: { min: 1, max: 100 },
  mergeHours: { min: 1, max: 1680 },
  freezeHours: { min: 1, max: 1680 },
  extensionHours: { min: 1, max: 1680 },
  timeoutMinutes: { min: 1, max: 1680 },
  maxExtensions: { min: 0, max: 10 }
})

export function syncReviewDecision(data) {
  if (data.decision !== '通过') data.amount = 0
  else if (Number(data.amount) <= 0) data.amount = ''
}

export function syncVisitResult(data) {
  if (data.result !== '未完成') data.reason = ''
}

export function executionAmountMax(data, requested) {
  if (requested === null || requested === undefined || requested === '') return undefined
  return data.result === '部分执行' ? Math.max(0, Math.round(Number(requested) * 100 - 1) / 100) : requested
}

export function syncExecutionResult(data, { kind = '止付', requested = null, clearReason = true } = {}) {
  if (data.result === '拒绝执行') {
    data.amount = 0
    return
  }
  if (clearReason && !executionReasonRequired(data)) data.reason = ''
  if (kind === '解除') return
  if (data.result === '全额执行' && requested !== null && requested !== undefined && requested !== '') {
    data.amount = requested
    return
  }
  if (data.result === '部分执行' && requested !== null && requested !== undefined && requested !== '' && Number(data.amount) >= Number(requested)) {
    data.amount = ''
  }
}

export function validateConfigForm(data) {
  const weights = [0, 1, 2, 3, 4].map(index => Number(data['w' + index]))
  if (weights.some(value => !Number.isInteger(value) || value < 1 || value > 100)) return '五项权重都应为1至100的整数'
  const total = weights.reduce((sum, value) => sum + value, 0)
  if (total !== 100) return `五项权重合计应为100%，当前为${total}%`
  const medium = Number(data.medium), high = Number(data.high)
  if (!Number.isInteger(medium) || !Number.isInteger(high) || high <= medium) return '高风险阈值必须大于中风险阈值'
  return ''
}
