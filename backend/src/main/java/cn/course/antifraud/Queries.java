package cn.course.antifraud;
import org.springframework.stereotype.Service;
import java.util.*;
import static cn.course.antifraud.Support.*;

/** Explicit allowlisted queries: identity scope is applied before counting, filtering and paging. */
@Service
public class Queries {
 private final Support s;
 public Queries(Support s){this.s=s;}
 public Map<String,Object> page(User u,String resource,int page,int size,String query,String state,Map<String,String> filters){
  require(page>=1&&page<=100000&&size>=1&&size<=50,"分页参数无效");require(query==null||query.length()<=80,"搜索词过长");
  String select,from,where="1=1",order="id DESC",stateField="",search;
  List<Object> params=new ArrayList<>();
  switch(resource){
   case "reports"->{u.roles("CENTER","PUBLIC");select="r.id,r.code,r.kind,r.source,r.processing,r.created_at,r.anonymous,r.alert_id,a.state";from="af_report r LEFT JOIN af_alert a ON a.id=r.alert_id";order="r.id DESC";stateField="r.processing";search="r.code LIKE ? OR r.kind LIKE ? OR r.source LIKE ?";if(u.role().equals("PUBLIC")){where="(r.owner_id=? OR r.victim_id=?)";params.add(u.id());params.add(u.victimId()==0?-1:u.victimId());}}
   case "alerts"->{u.roles("CENTER");select="a.*,c.name community_name,(SELECT COUNT(*) FROM af_report r WHERE r.alert_id=a.id) clue_count";from="af_alert a LEFT JOIN af_community c ON c.id=a.community_id";order="FIELD(a.level,'高','中','低'),a.id DESC";stateField="a.state";search="a.code LIKE ? OR a.level LIKE ? OR c.name LIKE ?";}
   case "commands"->{u.roles("CENTER","PAYMENT","PUBLIC");select="c.*,o.name org_name,b.number_enc";from="af_command c JOIN af_org o ON o.id=c.org_id JOIN af_bank b ON b.id=c.bank_id";order="c.id DESC";stateField="c.state";search="c.code LIKE ? OR c.kind LIKE ? OR o.name LIKE ?";if(u.role().equals("PAYMENT")){where="c.org_id=?";params.add(u.orgId());}if(u.role().equals("PUBLIC")){where="c.victim_id=?";params.add(u.victimId()==0?-1:u.victimId());}}
   case "visits"->{u.roles("CENTER","GRID");select="v.*,a.level,a.code alert_code,c.name community_name,u.display_name assignee";from="af_visit v JOIN af_alert a ON a.id=v.alert_id JOIN af_community c ON c.id=v.community_id LEFT JOIN af_user u ON u.id=v.assignee_id";order="FIELD(a.level,'高','中','低'),v.due_at,v.id DESC";stateField="v.state";search="v.code LIKE ? OR c.name LIKE ? OR u.display_name LIKE ?";if(u.role().equals("GRID")){where="v.community_id=?";params.add(u.communityId());}}
   case "applications"->{u.roles("CENTER","PUBLIC");select="a.id,a.code,a.kind,a.state,a.amount,a.approved,a.reason,a.note,a.original_id,a.created_at,c.code original_code,(SELECT state FROM af_command x WHERE x.application_id=a.id ORDER BY x.id DESC LIMIT 1) execution_state";from="af_application a JOIN af_command c ON c.id=a.original_id";order="a.id DESC";stateField="a.state";search="a.code LIKE ? OR a.kind LIKE ? OR c.code LIKE ?";if(u.role().equals("PUBLIC")){where="a.owner_id=?";params.add(u.id());}}
   case "campaigns"->{u.roles("CENTER","GRID");select="c.*,m.name community_name,(SELECT COALESCE(SUM(people),0) FROM af_campaign_record r WHERE r.campaign_id=c.id) people,(SELECT COALESCE(SUM(sessions),0) FROM af_campaign_record r WHERE r.campaign_id=c.id) sessions";from="af_campaign c JOIN af_community m ON m.id=c.community_id";order="c.id DESC";stateField="c.state";search="c.title LIKE ? OR c.description LIKE ? OR m.name LIKE ?";if(u.role().equals("GRID")){where="c.community_id=?";params.add(u.communityId());}}
   case "notices"->{u.roles("CENTER");select="n.*,c.code,c.state command_state";from="af_notice n JOIN af_command c ON c.id=n.command_id";order="n.id DESC";search="c.code LIKE ? OR n.node LIKE ? OR n.message LIKE ?";}
   case "corrections"->{u.roles("CENTER","PAYMENT");select="x.*,c.code,c.org_id";from="af_correction x JOIN af_command c ON c.id=x.command_id";order="x.id DESC";stateField="x.state";search="c.code LIKE ? OR x.reason LIKE ? OR x.new_result LIKE ?";if(u.role().equals("PAYMENT")){where="c.org_id=?";params.add(u.orgId());}}
   case "blacklist"->{u.roles("CENTER");select="b.id,b.kind,b.value_enc,b.source,b.enabled";from="af_blacklist b";order="b.id DESC";search="b.kind LIKE ? OR b.source LIKE ? OR b.source LIKE ?";}
   case "users"->{u.administrator();select="u.id,u.username,u.display_name,u.role,u.org_id,u.community_id,u.victim_id,u.verified,u.enabled,u.admin";from="af_user u";order="u.id";search="u.username LIKE ? OR u.display_name LIKE ? OR u.role LIKE ?";}
   case "banks"->{u.roles("CENTER","PAYMENT");select="b.id,b.org_id,b.number_enc,b.holder_enc,b.available,o.name org_name";from="af_bank b JOIN af_org o ON o.id=b.org_id";order="b.id";search="o.name LIKE ? OR o.name LIKE ? OR o.name LIKE ?";if(u.role().equals("PAYMENT")){where="b.org_id=?";params.add(u.orgId());}}
   case "logs"->{u.roles("CENTER");select="l.*,COALESCE(u.display_name,'系统') actor";from="af_log l LEFT JOIN af_user u ON u.id=l.actor_id";order="l.id DESC";search="l.action LIKE ? OR l.note LIKE ? OR u.display_name LIKE ?";}
   default->throw new Problem(404,"列表不存在");
  }
  List<String> states=new ArrayList<>();if(!stateField.isBlank())for(var r:s.rows("SELECT DISTINCT "+stateField+" value FROM "+from+" WHERE "+where+" ORDER BY value",params.toArray()))if(r.get("value")!=null)states.add(str(r,"value"));
  if(query!=null&&!query.isBlank()){where+=" AND ("+search+")";String term="%"+query.trim()+"%";params.addAll(List.of(term,term,term));}
  if(state!=null&&!state.isBlank()&&!stateField.isBlank()){where+=" AND "+stateField+"=?";params.add(state);}
  String alias=switch(resource){case "reports"->"r";case "alerts","applications"->"a";case "commands","campaigns"->"c";case "visits"->"v";case "logs"->"l";case "notices"->"n";case "corrections"->"x";default->"";};
  String dateColumn=resource.equals("alerts")?"a.root_time":alias+".created_at";
  if(!alias.isEmpty())for(String bound:List.of("from","to")){String value=filters.getOrDefault(bound,"");if(!value.isBlank()){var day=java.time.LocalDate.parse(value);where+=" AND "+dateColumn+(bound.equals("from")?">=?":"<?");params.add((bound.equals("to")?day.plusDays(1):day).atStartOfDay());}}
  String kind=filters.getOrDefault("kind",""),level=filters.getOrDefault("level",""),source=filters.getOrDefault("source","");
  if(!kind.isBlank()&&Set.of("reports","commands","applications").contains(resource)){where+=" AND "+alias+".kind=?";params.add(kind);}
  if(!source.isBlank()&&resource.equals("reports")){where+=" AND r.source=?";params.add(source);}
  if(!level.isBlank()&&Set.of("reports","alerts","visits").contains(resource)){where+=" AND a.level=?";params.add(level);}
  long total=s.count("SELECT COUNT(*) FROM "+from+" WHERE "+where,params.toArray());params.add(size);params.add((page-1)*size);var items=s.rows("SELECT "+select+" FROM "+from+" WHERE "+where+" ORDER BY "+order+" LIMIT ? OFFSET ?",params.toArray());
  for(var item:items){if(item.containsKey("number_enc"))item.put("account",mask(s.decrypt(item.remove("number_enc"))));if(item.containsKey("holder_enc"))item.put("holder",s.decrypt(item.remove("holder_enc")));if(item.containsKey("value_enc"))item.put("value",s.decrypt(item.remove("value_enc")));}
  return map("items",items,"total",total,"page",page,"size",size,"states",states);
 }
}
