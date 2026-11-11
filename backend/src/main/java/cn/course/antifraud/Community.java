package cn.course.antifraud;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;
import java.math.*;
import java.time.*;
import static cn.course.antifraud.Support.*;

@Service
public class Community {
 private final Support s;private final Files files;
 public Community(Support s,Files files){this.s=s;this.files=files;}
 public static String attention(Map<String,Object> t,LocalDateTime now){
  if(str(t,"state").equals("已回传")&&str(t,"result").equals("未完成"))return "劝阻未完成";
  return Set.of("待接收","待上门","劝阻中").contains(str(t,"state"))&&t.get("due_at")!=null&&now!=null&&!now.isBefore(time(t.get("due_at")))?"超期未回传":"";
 }
 public List<Map<String,Object>> attention(User u){
  u.roles("CENTER");var now=s.now();
  return s.rows("SELECT v.*,a.level,c.name community_name,u.display_name assignee FROM af_visit v JOIN af_alert a ON a.id=v.alert_id JOIN af_community c ON c.id=v.community_id LEFT JOIN af_user u ON u.id=v.assignee_id WHERE (v.state IN ('待接收','待上门','劝阻中') AND v.due_at<=?) OR (v.state='已回传' AND v.result='未完成') ORDER BY v.due_at,v.id LIMIT 300",now).stream().peek(v->v.put("attention",attention(v,now))).toList();
 }
 private void access(User u,Map<String,Object> t){u.roles("CENTER","GRID");if(u.role().equals("GRID")&&u.communityId()!=num(t,"community_id"))throw new Problem(404,"任务不存在或无权访问");}
 private void assignee(User u,Map<String,Object> t){u.roles("GRID");access(u,t);if(num(t,"assignee_id")!=u.id())throw new Problem(403,"请由接收任务的网格员操作");}
 static final String VISIT_SUMMARY="v.id,v.code,v.alert_id,v.community_id,v.assignee_id,v.state,v.due_at,v.created_at";
 static final String GRID_SCOPE="v.community_id=? AND (v.assignee_id=? OR (v.assignee_id IS NULL AND v.state='待接收'))";
 public List<Map<String,Object>> visits(User u){u.roles("CENTER","GRID");boolean grid=u.role().equals("GRID");return s.rows("SELECT "+(grid?VISIT_SUMMARY:"v.*")+",a.level,a.code alert_code,c.name community_name,u.display_name assignee FROM af_visit v JOIN af_alert a ON a.id=v.alert_id JOIN af_community c ON c.id=v.community_id LEFT JOIN af_user u ON u.id=v.assignee_id"+(grid?" WHERE "+GRID_SCOPE:"")+" ORDER BY FIELD(a.level,'高','中','低'),v.due_at,v.id DESC LIMIT 300",grid?new Object[]{u.communityId(),u.id()}:new Object[]{});}
 public Map<String,Object> visit(User u,long id){
  var t=s.one("SELECT v.*,a.level,a.victim_id FROM af_visit v JOIN af_alert a ON a.id=v.alert_id WHERE v.id=?",id);access(u,t);t.put("attention",attention(t,s.now()));
  if(u.role().equals("GRID")&&num(t,"assignee_id")!=u.id()){
   if(num(t,"assignee_id")!=0||!str(t,"state").equals("待接收"))throw new Problem(404,"任务不存在或无权访问");
   Map<String,Object> summary=new LinkedHashMap<>();for(String key:List.of("id","code","alert_id","community_id","assignee_id","state","due_at","created_at","level"))summary.put(key,t.get(key));
   summary.put("restricted",true);return summary;
  }
   var victim=s.one("SELECT * FROM af_victim WHERE id=?",t.get("victim_id"));t.put("person",s.decrypt(victim.get("name_enc")));t.put("phone",mask(s.decrypt(victim.get("phone_enc"))));t.put("reportMaterials",s.rows("SELECT x.id,x.filename,x.mime FROM af_attachment x JOIN af_report r ON x.entity_type='report' AND x.entity_id=r.id WHERE r.alert_id=? ORDER BY x.created_at,x.id",t.get("alert_id")));t.put("attachments",files.list("visit",id));t.put("timeline",s.rows("SELECT action,old_state,new_state,note,created_at FROM af_log WHERE entity_type='visit' AND entity_id=? ORDER BY id",id));return t;
 }
 private Map<String,Object> lockVisit(long id){var peek=s.one("SELECT alert_id FROM af_visit WHERE id=?",id);s.lockAlert(num(peek,"alert_id"));return s.one("SELECT * FROM af_visit WHERE id=? FOR UPDATE",id);}
 @Transactional(isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED) public void claim(User u,long id){u.roles("GRID");s.one("SELECT id FROM af_user WHERE id=? AND role='GRID' AND community_id=? AND enabled=TRUE FOR SHARE",u.id(),u.communityId());var t=lockVisit(id);access(u,t);conflict(str(t,"state").equals("待接收")&&num(t,"assignee_id")==0,"任务已被接收");s.db.update("UPDATE af_visit SET assignee_id=?,state='待上门' WHERE id=?",u.id(),id);s.log(u,"接收任务","visit",id,"待接收","待上门","已明确执行人");}
 @Transactional(isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED) public void assign(User u,long id,Map<String,Object> b){u.roles("CENTER");s.one("SELECT id FROM af_user WHERE id=? AND role='GRID' AND enabled=TRUE FOR SHARE",num(b,"assigneeId"));var t=lockVisit(id);conflict(Set.of("待接收","待上门").contains(str(t,"state")),"已经到场或回传的任务不能直接改派");long uid=num(b,"assigneeId");s.one("SELECT id FROM af_user WHERE id=? AND role='GRID' AND community_id=? AND enabled=TRUE",uid,t.get("community_id"));String note=required(b,"note",1000);s.db.update("UPDATE af_visit SET assignee_id=?,state='待上门' WHERE id=?",uid,id);s.log(u,"中心改派","visit",id,str(t,"state"),"待上门",note);}
 @Transactional(isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED) public void arrive(User u,long id,Map<String,Object> b){var t=lockVisit(id);assignee(u,t);conflict(str(t,"state").equals("待上门")&&t.get("arrived_at")==null,"任务已到场或不可到场");BigDecimal lat=new BigDecimal(required(b,"lat",20)),lng=new BigDecimal(required(b,"lng",20));require(lat.abs().compareTo(BigDecimal.valueOf(90))<=0&&lng.abs().compareTo(BigDecimal.valueOf(180))<=0&&lat.scale()<=6&&lng.scale()<=6,"坐标范围错误或超过六位小数");LocalDateTime time=str(b,"time").isBlank()?s.now():time(b.get("time"));require(!time.isAfter(s.now().plusMinutes(1))&&!time.isBefore(time(t.get("created_at"))),"到场时间应在任务创建后且不能是未来");s.db.update("UPDATE af_visit SET state='劝阻中',arrived_at=?,lat=?,lng=?,location_simulated=? WHERE id=?",time,lat,lng,yes(b,"simulated"),id);s.log(u,"到场记录","visit",id,"待上门","劝阻中",yes(b,"simulated")?"手动录入坐标":"设备定位");}
 @Transactional(isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED) public void feedback(User u,long id,Map<String,Object> b,String owner){var t=lockVisit(id);assignee(u,t);conflict(str(t,"state").equals("劝阻中"),"请先完成到场记录，已回传任务不能重复回传");String process=required(b,"process",2000),result=required(b,"result",30),reason=str(b,"reason");require(reason.length()<=1000,"未完成原因最多1000字");require(Set.of("已完成","未完成").contains(result),"请选择劝阻结果");require(!result.equals("未完成")||!reason.isBlank(),"未完成必须填写原因");files.bind(b.get("attachments"),u,owner,"visit",id,true);s.db.update("UPDATE af_visit SET state='已回传',process_text=?,result=?,reason=?,returned_at=? WHERE id=?",process,result,reason,s.now(),id);s.db.update("UPDATE af_alert SET state=IF(state='已派单','处置中',state),updated_at=? WHERE id=?",s.now(),t.get("alert_id"));s.log(u,"结果回传","visit",id,"劝阻中","已回传",result);}
 @Transactional(isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED) public void append(User u,long id,Map<String,Object> b){var t=lockVisit(id);access(u,t);if(u.role().equals("GRID"))assignee(u,t);conflict(Set.of("已回传","已归档").contains(str(t,"state")),"回传后才能追加说明");s.log(u,"追加说明","visit",id,str(t,"state"),str(t,"state"),required(b,"note",1000));}
 public List<Map<String,Object>> campaigns(User u){u.roles("CENTER","GRID");return s.rows("SELECT c.*,m.name community_name,(SELECT COALESCE(SUM(people),0) FROM af_campaign_record r WHERE r.campaign_id=c.id) people,(SELECT COALESCE(SUM(sessions),0) FROM af_campaign_record r WHERE r.campaign_id=c.id) sessions FROM af_campaign c JOIN af_community m ON m.id=c.community_id"+(u.role().equals("GRID")?" WHERE c.community_id=?":"")+" ORDER BY c.id DESC LIMIT 300",u.role().equals("GRID")?new Object[]{u.communityId()}:new Object[]{});}
 public Map<String,Object> campaign(User u,long id){u.roles("CENTER","GRID");var c=s.one("SELECT * FROM af_campaign WHERE id=?",id);if(u.role().equals("GRID")&&u.communityId()!=num(c,"community_id"))throw new Problem(404,"任务不存在");c.put("records",s.rows("SELECT r.*,u.display_name FROM af_campaign_record r JOIN af_user u ON r.actor_id=u.id WHERE r.campaign_id=? ORDER BY r.id DESC",id));c.put("timeline",s.rows("SELECT action,old_state,new_state,note,created_at FROM af_log WHERE entity_type='campaign' AND entity_id=? ORDER BY id",id));return c;}
 @Transactional(isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
 public void finishCampaign(User u,long id,Map<String,Object> b){
  u.roles("CENTER");var c=s.one("SELECT * FROM af_campaign WHERE id=? FOR UPDATE",id);conflict(str(c,"state").equals("已发布"),"宣传任务已结束");
  String state=required(b,"state",20),note=required(b,"note",1000);require(Set.of("已完成","已取消").contains(state),"请选择完成或取消任务");
  if(state.equals("已完成"))conflict(s.count("SELECT COUNT(*) FROM af_campaign_record WHERE campaign_id=?",id)>0,"至少保存一次活动打卡后才能完成任务");
  s.db.update("UPDATE af_campaign SET state=? WHERE id=?",state,id);s.log(u,"结束宣防","campaign",id,"已发布",state,note);
 }
 @Transactional(isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED) public long createCampaign(User u,Map<String,Object> b){u.roles("CENTER");String title=required(b,"title",120),description=required(b,"description",2000);long community=num(b,"communityId");s.one("SELECT id FROM af_community WHERE id=?",community);LocalDateTime due=time(b.get("dueAt"));require(due.isAfter(s.now()),"宣传截止时间须在未来");long id=s.insert("INSERT INTO af_campaign(title,community_id,description,due_at,created_at) VALUES(?,?,?,?,?)",title,community,description,due,s.now());s.log(u,"派发宣防","campaign",id,null,"已发布",title);return id;}
 @Transactional(isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED) public void checkIn(User u,long id,Map<String,Object> b){u.roles("GRID");var c=s.one("SELECT * FROM af_campaign WHERE id=? FOR UPDATE",id);if(num(c,"community_id")!=u.communityId())throw new Problem(404,"任务不存在");conflict(str(c,"state").equals("已发布"),"当前宣传任务不可打卡");require(str(b,"note").length()<=1000,"活动说明最多1000字");String request=required(b,"requestKey",80);conflict(s.count("SELECT COUNT(*) FROM af_campaign_record WHERE request_key=?",request)==0,"本次打卡已经保存");long people=num(b,"people"),sessions=num(b,"sessions");require(people>=0&&people<=100000&&sessions>0&&sessions<=1000,"人数须为0至100000，场次须为1至1000");LocalDateTime occurred=time(b.get("time"));require(!occurred.isAfter(s.now())&&!occurred.isBefore(time(c.get("created_at"))),"活动时间不能早于任务创建或晚于当前时间");require(!occurred.isAfter(time(c.get("due_at"))),"活动时间不能超过任务截止时间");s.db.update("INSERT INTO af_campaign_record(campaign_id,actor_id,form,occurred_at,sessions,people,note,request_key,created_at) VALUES(?,?,?,?,?,?,?,?,?)",id,u.id(),required(b,"form",80),occurred,sessions,people,str(b,"note"),request,s.now());s.log(u,"宣传打卡","campaign",id,null,null,"记录宣传场次和触达人次");}
 private static final String ARTICLE_SUMMARY_FIELDS="a.id,a.title,a.category,a.summary,a.state,a.updated_at,a.cover_id,f.filename cover_filename,f.mime cover_mime,f.bytes cover_bytes";
 public List<Map<String,Object>> articles(User u){return s.rows("SELECT "+ARTICLE_SUMMARY_FIELDS+" FROM af_article a LEFT JOIN af_attachment f ON f.id=a.cover_id"+(u!=null&&u.role().equals("CENTER")?"":" WHERE a.state='已发布'")+" ORDER BY a.id DESC");}
 public Map<String,Object> article(User u,long id){return s.one("SELECT "+ARTICLE_SUMMARY_FIELDS+",a.body FROM af_article a LEFT JOIN af_attachment f ON f.id=a.cover_id WHERE a.id=?"+(u!=null&&u.role().equals("CENTER")?"":" AND a.state='已发布'"),id);}
 @Transactional(isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED) public long saveArticle(User u,long id,Map<String,Object> b){
  u.roles("CENTER");String state=required(b,"state",20);require(Set.of("草稿","已发布","已下线").contains(state),"发布状态无效");
  String title=required(b,"title",120),category=required(b,"category",30),summary=required(b,"summary",300),body=required(b,"body",20000),oldCover=null,cover=null;
  if(id==0)id=s.insert("INSERT INTO af_article(title,category,summary,body,state,updated_at) VALUES(?,?,?,?,?,?)",title,category,summary,body,state,s.now());
  else{var old=s.one("SELECT id,cover_id FROM af_article WHERE id=? FOR UPDATE",id);oldCover=str(old,"cover_id");if(oldCover.isBlank())oldCover=null;cover=oldCover;}
  if(b.containsKey("cover")){
   require(b.get("cover") instanceof List<?>,"封面图数据无效");var covers=(List<?>)b.get("cover");require(covers.size()<=1&&covers.stream().allMatch(v->v instanceof String),"封面图只能上传一张");
   cover=covers.isEmpty()?null:covers.get(0).toString();files.bind(covers,u,"","article",id,false);
   if(cover!=null){var file=s.one("SELECT mime FROM af_attachment WHERE id=? AND entity_type='article' AND entity_id=?",cover,id);require(Set.of("image/png","image/jpeg").contains(str(file,"mime")),"封面图仅支持PNG或JPG");}
  }
  s.db.update("UPDATE af_article SET title=?,category=?,summary=?,body=?,state=?,cover_id=?,updated_at=? WHERE id=?",title,category,summary,body,state,cover,s.now(),id);
  if(oldCover!=null&&!oldCover.equals(cover))files.deleteBound(oldCover,"article",id);
  s.log(u,"维护文章","article",id,null,state,title);return id;
 }
}
