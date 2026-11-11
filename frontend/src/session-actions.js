export async function endSession({request,refreshSession}){
 try{
  await request()
 }catch(error){
  if(error?.status===401)return
  if(error?.status!==403)throw error
  await refreshSession()
  await request()
 }
}
