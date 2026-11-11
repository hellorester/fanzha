export const verificationOptions=[
 {value:'UNVERIFIED',label:'暂未核验（不关联实名人员）'},
 {value:'VERIFIED',label:'已核验，关联现有实名人员'}
]

const asText=value=>String(value??'').trim()

export function personOptions(values,format=asText,currentAccountId=0){
 return (values||[]).map(person=>{
  const count=Number(person.active_account_count||0),current=Number(person.active_account_id)===Number(currentAccountId)
  const binding=count>1?'存在账号关联冲突':count===1?(current?'已关联当前账号':'已被 '+format(person.active_username||'其他账号')+' 使用'):'尚未绑定账号'
  return {value:person.id,label:[format(person.name),person.community_name?format(person.community_name):'',person.identity?'证件 '+person.identity:'',binding].filter(Boolean).join(' · '),disabled:count>1||count===1&&!current}
 })
}

export function resetUserRole(data){
 data.orgId=''
 data.communityId=''
 data.victimId=''
 data.verification='UNVERIFIED'
 data.admin=false
}

export function resetVerification(data){
 data.victimId=''
}

export function normalizeUserData(data){
 const role=data.role,verified=role==='PUBLIC'&&data.verification==='VERIFIED'
 return {
  username:data.username,
  displayName:data.displayName,
  role,
  password:data.password||'',
  orgId:role==='PAYMENT'?data.orgId:'',
  communityId:role==='GRID'?data.communityId:'',
  victimId:verified?data.victimId:'',
  verified,
  enabled:Boolean(data.enabled),
  admin:role==='CENTER'&&Boolean(data.admin),
  version:Number(data.version||0),
  reason:data.reason||''
 }
}

export function userSummary(data,meta,roleNames,format=asText){
 const items=[{label:'业务角色',value:roleNames[data.role]||'请选择'}]
 if(data.role==='PUBLIC'){
  const person=(meta.victims||[]).find(item=>String(item.id)===String(data.victimId))
  items.push({label:'实名状态',value:data.verification==='VERIFIED'?(person?personOptions([person],format,data.accountId||0)[0].label:'请选择实名人员'):'暂未核验'})
 }else if(data.role==='PAYMENT'){
  const org=(meta.orgs||[]).find(item=>String(item.id)===String(data.orgId))
  items.push({label:'所属机构',value:org?format(org.name):'请选择支付机构'})
 }else if(data.role==='GRID'){
  const community=(meta.communities||[]).find(item=>String(item.id)===String(data.communityId))
  items.push({label:'所属社区',value:community?format(community.name):'请选择社区'})
 }else if(data.role==='CENTER')items.push({label:'账号权限',value:data.admin?'中心管理员':'研判人员'})
 items.push({label:'账号状态',value:data.enabled?'启用':'停用'})
 return items
}
