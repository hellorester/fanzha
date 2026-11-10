package cn.course.antifraud;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.*;
import jakarta.servlet.http.*;
import java.util.*;
import java.time.*;
import static cn.course.antifraud.Support.*;

@RestController
@RequestMapping("/api")
public class BusinessApi {
 private final Support s;private final Auth auth;private final Commands commands;private final Community community;private final Admin admin;private final Queries queries;private final Reconciliation reconciliation;private final Audit audit;private final Relationships relationships;
 public BusinessApi(Support s,Auth auth,Commands commands,Community community,Admin admin,Queries queries,Reconciliation reconciliation,Audit audit,Relationships relationships){this.s=s;this.auth=auth;this.commands=commands;this.community=community;this.admin=admin;this.queries=queries;this.reconciliation=reconciliation;this.audit=audit;this.relationships=relationships;}
 @GetMapping("/list/{resource}") public Object list(HttpServletRequest r,@PathVariable String resource,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="10") int size,@RequestParam(defaultValue="") String q,@RequestParam(defaultValue="") String state,@RequestParam Map<String,String> filters){return queries.page(auth.user(r),resource,page,size,q,state,filters);}
 @GetMapping("/meta") public Object meta(HttpServletRequest r){return admin.meta(auth.user(r));}
 @GetMapping("/commands") public Object commands(HttpServletRequest r){return commands.list(auth.user(r));}
 @GetMapping("/commands/{id}") public Object command(HttpServletRequest r,@PathVariable long id){return commands.detail(auth.user(r),id);}
 @PostMapping("/commands/{id}/accept") public Object accept(HttpServletRequest r,@PathVariable long id){commands.accept(auth.user(r),id);return map("ok",true);}
 @PostMapping("/commands/{id}/receipt") public Object receipt(HttpServletRequest r,@PathVariable long id,@RequestBody Map<String,Object> b){commands.receipt(auth.user(r),id,b,auth.owner(r));return map("ok",true);}
 @PostMapping("/commands/{id}/extend") public Object extend(HttpServletRequest r,@PathVariable long id,@RequestBody Map<String,Object> b){commands.extend(auth.user(r),id,b);return map("ok",true);}
 @PostMapping("/commands/{id}/retry") public Object retry(HttpServletRequest r,@PathVariable long id,@RequestBody Map<String,Object> b){return commands.retry(auth.user(r),id,b);}
 @PostMapping("/commands/{id}/simulate") public Object simulate(HttpServletRequest r,@PathVariable long id,@RequestBody Map<String,Object> b){User u=auth.user(r);u.administrator();require(Set.of("24小时","2小时","到期","超时").contains(str(b,"node")),"请选择有效演示节点");var c=s.one("SELECT * FROM af_command WHERE id=?",id);String node=str(b,"node");LocalDateTime at=node.equals("超时")?time(c.get("accepted_at")==null?c.get("created_at"):c.get("accepted_at")).plusDays(2):time(c.get("expires_at")).minusHours(node.equals("24小时")?24:node.equals("2小时")?2:0);commands.scanOne(id,at,u,true);return map("ok",true,"simulatedAt",at);}
 @GetMapping("/applications") public Object applications(HttpServletRequest r){return commands.applications(auth.user(r));}
 @GetMapping("/applications/{id}") public Object application(HttpServletRequest r,@PathVariable long id){return commands.application(auth.user(r),id);}
 @PostMapping("/applications") public Object apply(HttpServletRequest r,@RequestBody Map<String,Object> b){return commands.apply(auth.user(r),b,auth.owner(r));}
 @PostMapping("/applications/{id}/supplement") public Object supplement(HttpServletRequest r,@PathVariable long id,@RequestBody Map<String,Object> b){commands.supplement(auth.user(r),id,b,auth.owner(r));return map("ok",true);}
 @PostMapping("/applications/{id}/review") public Object adjudicate(HttpServletRequest r,@PathVariable long id,@RequestBody Map<String,Object> b){commands.adjudicate(auth.user(r),id,b);return map("ok",true);}
 @GetMapping("/notices") public Object notices(HttpServletRequest r){return commands.notices(auth.user(r));}
 @PostMapping("/notices/{id}/resolve") public Object resolve(HttpServletRequest r,@PathVariable long id,@RequestBody Map<String,Object> b){commands.resolveNotice(auth.user(r),id,b);return map("ok",true);}
 @GetMapping("/corrections/{id}") public Object correctionDetail(HttpServletRequest r,@PathVariable long id){return commands.correction(auth.user(r),id);}
 @GetMapping("/corrections") public Object corrections(HttpServletRequest r){return commands.corrections(auth.user(r));}
 @PostMapping("/commands/{id}/correction") public Object correction(HttpServletRequest r,@PathVariable long id,@RequestBody Map<String,Object> b){commands.requestCorrection(auth.user(r),id,b);return map("ok",true);}
 @PostMapping("/corrections/{id}/review") public Object correctionReview(HttpServletRequest r,@PathVariable long id,@RequestBody Map<String,Object> b){commands.correct(auth.user(r),id,b);return map("ok",true);}
 @GetMapping("/visits") public Object visits(HttpServletRequest r){return community.visits(auth.user(r));}
 @GetMapping("/visit-attention") public Object visitAttention(HttpServletRequest r){return community.attention(auth.user(r));}
 @GetMapping("/visits/{id}") public Object visit(HttpServletRequest r,@PathVariable long id){return community.visit(auth.user(r),id);}
 @PostMapping("/visits/{id}/claim") public Object claim(HttpServletRequest r,@PathVariable long id){community.claim(auth.user(r),id);return map("ok",true);}
 @PostMapping("/visits/{id}/assign") public Object assign(HttpServletRequest r,@PathVariable long id,@RequestBody Map<String,Object> b){community.assign(auth.user(r),id,b);return map("ok",true);}
 @PostMapping("/visits/{id}/arrive") public Object arrive(HttpServletRequest r,@PathVariable long id,@RequestBody Map<String,Object> b){community.arrive(auth.user(r),id,b);return map("ok",true);}
 @PostMapping("/visits/{id}/feedback") public Object feedback(HttpServletRequest r,@PathVariable long id,@RequestBody Map<String,Object> b){community.feedback(auth.user(r),id,b,auth.owner(r));return map("ok",true);}
 @PostMapping("/visits/{id}/append") public Object append(HttpServletRequest r,@PathVariable long id,@RequestBody Map<String,Object> b){community.append(auth.user(r),id,b);return map("ok",true);}
 @GetMapping("/campaigns") public Object campaigns(HttpServletRequest r){return community.campaigns(auth.user(r));}
 @GetMapping("/campaigns/{id}") public Object campaign(HttpServletRequest r,@PathVariable long id){return community.campaign(auth.user(r),id);}
 @PostMapping("/campaigns") public Object campaign(HttpServletRequest r,@RequestBody Map<String,Object> b){return map("id",community.createCampaign(auth.user(r),b));}
 @PostMapping("/campaigns/{id}/checkin") public Object checkin(HttpServletRequest r,@PathVariable long id,@RequestBody Map<String,Object> b){community.checkIn(auth.user(r),id,b);return map("ok",true);}
 @PostMapping("/campaigns/{id}/finish") public Object campaignFinish(HttpServletRequest r,@PathVariable long id,@RequestBody Map<String,Object> b){community.finishCampaign(auth.user(r),id,b);return map("ok",true);}
 @GetMapping("/articles") public Object articles(HttpServletRequest r){return community.articles(auth.optional(r));}
 @GetMapping("/articles/{id}") public Object article(HttpServletRequest r,@PathVariable long id){return community.article(auth.optional(r),id);}
 @PostMapping("/articles") public Object articleCreate(HttpServletRequest r,@RequestBody Map<String,Object> b){return map("id",community.saveArticle(auth.user(r),0,b));}
 @PostMapping("/articles/{id}") public Object articleSave(HttpServletRequest r,@PathVariable long id,@RequestBody Map<String,Object> b){return map("id",community.saveArticle(auth.user(r),id,b));}
 @GetMapping("/reconciliation") public Object reconciliation(HttpServletRequest r){return reconciliation.check(auth.user(r));}
 @GetMapping("/audit/verify") public Object verifyAudit(HttpServletRequest r){return audit.verify(auth.user(r));}
 @GetMapping("/people") public Object people(HttpServletRequest r){return relationships.people(auth.user(r));}
 @PostMapping("/people") public Object person(HttpServletRequest r,@RequestBody Map<String,Object> b){return map("id",relationships.person(auth.user(r),b));}
 @PostMapping("/banks") public Object bankCreate(HttpServletRequest r,@RequestBody Map<String,Object> b){return map("id",relationships.bank(auth.user(r),0,b));}
 @PostMapping("/banks/{id}/owner") public Object bankOwner(HttpServletRequest r,@PathVariable long id,@RequestBody Map<String,Object> b){return map("id",relationships.bank(auth.user(r),id,b));}
 @PostMapping("/alerts/{id}/accounts") public Object linkAccount(HttpServletRequest r,@PathVariable long id,@RequestBody Map<String,Object> b){relationships.link(auth.user(r),id,b);return map("ok",true);}
 @PostMapping("/alerts/{id}/accounts/{bankId}/remove") public Object unlinkAccount(HttpServletRequest r,@PathVariable long id,@PathVariable long bankId,@RequestBody Map<String,Object> b){relationships.unlink(auth.user(r),id,bankId,b);return map("ok",true);}
 @GetMapping("/banks") public Object banks(HttpServletRequest r){return admin.banks(auth.user(r));}
 @GetMapping("/users") public Object users(HttpServletRequest r){return admin.users(auth.user(r));}
 @PostMapping("/users") public Object userCreate(HttpServletRequest r,@RequestBody Map<String,Object> b){return map("id",admin.user(auth.user(r),0,b));}
 @PostMapping("/users/{id}") public Object userSave(HttpServletRequest r,@PathVariable long id,@RequestBody Map<String,Object> b){return map("id",admin.user(auth.user(r),id,b));}
 @GetMapping("/blacklist") public Object blacklist(HttpServletRequest r){return admin.blacklist(auth.user(r));}
 @PostMapping("/blacklist") public Object blackCreate(HttpServletRequest r,@RequestBody Map<String,Object> b){return map("id",admin.blacklist(auth.user(r),0,b));}
 @PostMapping("/blacklist/{id}") public Object blackSave(HttpServletRequest r,@PathVariable long id,@RequestBody Map<String,Object> b){return map("id",admin.blacklist(auth.user(r),id,b));}
 @GetMapping("/configs") public Object configs(HttpServletRequest r){return admin.configurations(auth.user(r));}
 @PostMapping("/configs") public Object configSave(HttpServletRequest r,@RequestBody Map<String,Object> b){admin.configure(auth.user(r),b);return map("ok",true);}
 @GetMapping("/logs") public Object logs(HttpServletRequest r){return admin.logs(auth.user(r));}
 @GetMapping("/stats") public Object stats(HttpServletRequest r,@RequestParam(required=false) String from,@RequestParam(required=false) String to){return admin.stats(auth.user(r),from,to);}
 @GetMapping("/stats.csv") public ResponseEntity<String> statsCsv(HttpServletRequest r,@RequestParam(required=false) String from,@RequestParam(required=false) String to){var stats=admin.stats(auth.user(r),from,to);StringBuilder out=new StringBuilder("\uFEFF指标,数值,单位,开始日期,结束日期\r\n");for(Object metric:(List<?>)stats.get("metrics")){Map<?,?> m=(Map<?,?>)metric;out.append(m.get("label")).append(',').append(m.get("value")).append(',').append(m.get("unit")).append(',').append(stats.get("from")).append(',').append(stats.get("to")).append("\r\n");}return ResponseEntity.ok().header("Content-Disposition","attachment; filename=statistics.csv").contentType(MediaType.parseMediaType("text/csv;charset=UTF-8")).body(out.toString());}
}
