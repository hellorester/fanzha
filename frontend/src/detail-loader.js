// A detail request may finish after another selection, navigation or sign-out.
export function createDetailLoader({request,start,commit,fail}){
 let sequence=0
 const embedded=new Set(['notices','blacklist'])
 return {
  invalidate(){sequence++},
  version(){return sequence},
  async open(type,row){
   const current=++sequence
   start(type,row)
   try{
    const data=embedded.has(type)?{...row}:await request('/'+type+'/'+row.id)
    if(current===sequence)commit({type,id:row.id,data,loading:false})
   }catch(error){if(current===sequence)fail(error)}
  }
 }
}
