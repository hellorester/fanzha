// Only a first dispatch creates a new root command. Existing active commands are reused.
export function dispatchTargets(banks,commands=[]){
 return banks.map(bank=>{
  const history=commands.filter(c=>c.kind==='止付'&&c.bank_id===bank.id)
  const active=history.some(c=>c.dispatch_reusable)
  const latest=history.reduce((last,c)=>!last||c.id>last.id?c:last,null)
  const dispatchLabel=active?'复用现有指令':!latest?'新派发':['拒绝执行','未处理','已拒绝'].includes(latest.state)?'请复核重发':'已办理，不再新建'
  return {...bank,dispatchLabel,disabled:!!latest&&!active}
 })
}
