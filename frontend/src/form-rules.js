export const executionReasonRequired = data => ['部分执行', '拒绝执行'].includes(data.result)

export const reviewAmountRequired = data => data.decision === '通过'

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
