export const articleCategories=['举报指南','典型案例','防范提示']
// Keep older saved articles usable while presenting a consistent taxonomy.
export const articleCategory=value=>value==='反诈知识'?'举报指南':value||'举报指南'
