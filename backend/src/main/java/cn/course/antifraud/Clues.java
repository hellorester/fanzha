package cn.course.antifraud;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.ObjectProvider;
import java.math.*;
import java.time.*;
import java.util.*;
import static cn.course.antifraud.Support.*;

@Service
public class Clues {
    private final Support s;private final Files files;private final ObjectProvider<Commands> commands;
    public Clues(Support s,Files files,ObjectProvider<Commands> commands){this.s=s;this.files=files;this.commands=commands;}
    // Only a completed deadline transition commits when dispatch itself is rejected.
    static class DispatchDeadlineChanged extends Problem {DispatchDeadlineChanged(String state){super(409,state.equals("已解除")?"原止付已到期，已解除并释放剩余冻结资金，不能复用；如需再次处置，请另行受理预警":"原止付已超时，已转中心督办；请进入原指令详情使用“复核重发”");}}
    // Issuing callers hold the alert lock shared by merging and manual review.
    public boolean freezeReviewReady(Map<String,Object> alert){
        if(!Set.of("已核实","已派单","处置中").contains(str(alert,"state"))||yes(alert,"needs_review")||num(alert,"reviewed_by")==0)return false;
        // Retry notes are separate from the latest approval/rejection of the clues.
        var review=s.optional("SELECT actor_id,decision FROM af_assessment WHERE alert_id=? AND decision IN ('通过','驳回') ORDER BY id DESC LIMIT 1",alert.get("id"));
        return review!=null&&str(review,"decision").equals("通过")&&num(review,"actor_id")==num(alert,"reviewed_by");
    }
    public Map<String,Object> config(){var r=s.one("SELECT * FROM af_config ORDER BY id DESC LIMIT 1");var c=s.decode(str(r,"data"));c.put("version",r.get("id"));return c;}
    @Transactional(isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED) public Map<String,Object> submit(User u,String owner,Map<String,Object> b){
        if(u!=null)u.roles("PUBLIC","CENTER");
        boolean anon=u==null||yes(b,"anonymous")||u.role().equals("PUBLIC")&&!u.verified();
        String kind=required(b,"kind",20),value=required(b,"value",500),normalized=Rules.normalize(kind,value);required(b,"description",2000);String requestKey=required(b,"requestKey",80);
        // Serialize concurrent retries before looking for the committed response.
        s.db.update("INSERT INTO af_request_guard(request_key,touched_at) VALUES(?,?) ON DUPLICATE KEY UPDATE touched_at=VALUES(touched_at)",hash(requestKey),s.now());
        var exists=s.optional("SELECT * FROM af_report WHERE request_key=? FOR UPDATE",requestKey);
        if(exists!=null){
            boolean own=u!=null?num(exists,"owner_id")==u.id():num(exists,"owner_id")==0&&hash(owner).equals(str(exists,"request_owner_key"));
            conflict(own,"该请求已受理，请使用原提交会话或已保存的查询凭证");
            String token=s.decrypt(exists.get("query_token_enc"));
            // Older signed-in submissions have ownership proof; legacy anonymous rows do not.
            if(token.isEmpty()&&!str(exists,"token_hash").isEmpty()){token=random();s.db.update("UPDATE af_report SET token_hash=?,query_token_enc=? WHERE id=?",hash(token),s.encrypt(token),exists.get("id"));s.log(u,"恢复查询凭证","report",num(exists,"id"),null,null,"原提交者恢复历史凭证");}
            return map("id",exists.get("id"),"code",exists.get("code"),"queryToken",str(exists,"token_hash").isEmpty()?null:token,"anonymous",yes(exists,"anonymous"),"duplicate",true);
        }
        Rules.score(b,false,config());long victim=u!=null&&u.role().equals("PUBLIC")&&!anon&&u.verified()?u.victimId():u!=null&&u.role().equals("CENTER")?num(b,"victimId"):0;
        if(victim>0)s.one("SELECT id FROM af_victim WHERE id=?",victim);
        String token=random(),code=s.code("JB");Map<String,Object> payload=map("description",str(b,"description"),"amount",str(b,"amount"),"transactionTime",str(b,"transactionTime"),"remote",yes(b,"remote"),"repeat",yes(b,"repeat"),"newLarge",yes(b,"newLarge"));
        long id=s.insert("INSERT INTO af_report(code,request_key,request_owner_key,query_token_enc,owner_id,victim_id,anonymous,token_hash,kind,value_hash,value_enc,payload_enc,source,created_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)",code,requestKey,hash(owner),s.encrypt(token),u==null?null:u.id(),victim==0?null:victim,anon,hash(token),kind,s.fingerprint(kind,normalized),s.encrypt(value),s.encrypt(s.encode(payload)),u!=null&&u.role().equals("CENTER")?"模拟监测":"群众举报",s.now());
        files.bind(b.get("attachments"),u,owner,"report",id,false);s.log(u,"受理举报","report",id,null,"待研判","已保存线索");return map("id",id,"code",code,"queryToken",token,"anonymous",anon);
    }
    @org.springframework.scheduling.annotation.Scheduled(initialDelay=60000,fixedDelay=3600000)
    public void cleanupRequests(){s.db.update("DELETE FROM af_request_guard WHERE touched_at<?",s.now().minusDays(7));}
    @Transactional(isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED) public void process(long reportId){
        var peek=s.one("SELECT victim_id,alert_id FROM af_report WHERE id=?",reportId);if(num(peek,"alert_id")>0)s.lockAlert(num(peek,"alert_id"));else if(num(peek,"victim_id")>0)s.one("SELECT id FROM af_victim WHERE id=? FOR UPDATE",peek.get("victim_id"));
        var report=s.one("SELECT * FROM af_report WHERE id=? FOR UPDATE",reportId);if(str(report,"processing").equals("已研判"))return;
        conflict(num(peek,"victim_id")==num(report,"victim_id"),"身份关联刚刚更新，请重试研判");
        var config=config();long victim=num(report,"victim_id"),alertId=num(report,"alert_id");boolean newlyLinked=alertId==0;Map<String,Object> alert=null;LocalDateTime created=time(report.get("created_at"));
        if(victim>0&&alertId==0){alert=s.optional("SELECT * FROM af_alert WHERE victim_id=? AND state NOT IN ('已闭环','已驳回','已作废') AND root_time>=? AND root_time<=? ORDER BY root_time DESC,id DESC LIMIT 1 FOR UPDATE",victim,created.minusHours(num(config,"mergeHours")),created);if(alert!=null)alertId=num(alert,"id");}
        if(alertId==0){Long community=victim==0?null:((Number)s.one("SELECT community_id FROM af_victim WHERE id=?",victim).get("community_id")).longValue();alertId=s.insert("INSERT INTO af_alert(code,victim_id,community_id,root_time,updated_at) VALUES(?,?,?,?,?)",s.code("YJ"),victim==0?null:victim,community,created,s.now());}
        alert=s.one("SELECT * FROM af_alert WHERE id=? FOR UPDATE",alertId);s.db.update("UPDATE af_report SET alert_id=? WHERE id=?",alertId,reportId);
        long blacklistVersion=num(s.one("SELECT blacklist_version FROM af_rule_state WHERE id=1 FOR SHARE"),"blacklist_version");
        boolean incremental=newlyLinked&&num(alert,"score_config_id")==num(config,"version")&&alert.get("score_blacklist_version")!=null&&num(alert,"score_blacklist_version")==blacklistVersion;
        int max=incremental?(int)num(alert,"score"):-1;
        var reports=incremental?List.of(report):s.rows("SELECT * FROM af_report WHERE alert_id=? ORDER BY id",alertId);
        for(var r:reports){
            var matches=s.rows("SELECT kind,source FROM af_blacklist WHERE kind=? AND value_hash=? AND enabled=TRUE",r.get("kind"),r.get("value_hash"));
            var calculated=Rules.score(s.decode(s.decrypt(r.get("payload_enc"))),!matches.isEmpty(),config);calculated.put("matches",matches);calculated.put("blacklistVersion",blacklistVersion);
            int score=(int)num(calculated,"score");s.db.update("INSERT INTO af_score(alert_id,report_id,config_id,score,level,details,created_at) VALUES(?,?,?,?,?,?,?)",alertId,r.get("id"),config.get("version"),score,calculated.get("level"),s.encode(calculated),s.now());max=Math.max(max,score);
        }
        String level=max>=num(config,"high")?"高":max>=num(config,"medium")?"中":"低";
        boolean dispatched=s.count("SELECT COUNT(*) FROM af_command WHERE alert_id=?",alertId)>0;
        boolean merged=s.count("SELECT EXISTS(SELECT 1 FROM af_report WHERE alert_id=? AND id<>?)",alertId,reportId)>0;
        String state=dispatched?str(alert,"state"):merged?"已归并":"待研判";
        s.db.update("UPDATE af_alert SET score=?,level=?,state=?,needs_review=?,score_config_id=?,score_blacklist_version=?,updated_at=? WHERE id=?",max,level,state,dispatched,config.get("version"),blacklistVersion,s.now(),alertId);
        if(incremental)s.db.update("UPDATE af_report SET processing='已研判' WHERE id=?",reportId);else s.db.update("UPDATE af_report SET processing='已研判' WHERE alert_id=?",alertId);
        s.log(null,"规则研判","alert",alertId,str(alert,"state"),state,(incremental?"追加线索评分取最高":"按当前规则逐条评分取最高")+"；配置版本 "+config.get("version"));
    }
    @Transactional(isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED) public void failure(long id){if(s.db.update("UPDATE af_report SET processing='人工待研判' WHERE id=? AND processing<>'已研判'",id)>0)s.log(null,"转人工","report",id,null,"人工待研判","规则处理未完成，受理记录已保留");}
    public List<Map<String,Object>> reports(User u){u.roles("PUBLIC","CENTER");return s.rows("SELECT r.id,r.code,r.kind,r.source,r.processing,r.created_at,r.anonymous,r.alert_id,a.state,a.level,a.score FROM af_report r LEFT JOIN af_alert a ON r.alert_id=a.id "+(u.role().equals("PUBLIC")?"WHERE r.owner_id=? ":"")+"ORDER BY r.id DESC LIMIT 300",u.role().equals("PUBLIC")?new Object[]{u.id()}:new Object[]{}).stream().map(r->{if(u.role().equals("PUBLIC")){r.remove("score");r.remove("level");}return r;}).toList();}
    public Map<String,Object> report(User u,long id){u.roles("CENTER","PUBLIC");var r=s.one("SELECT * FROM af_report WHERE id=?"+(u.role().equals("PUBLIC")?" AND owner_id=?":""),u.role().equals("PUBLIC")?new Object[]{id,u.id()}:new Object[]{id});return reportView(r,u.role().equals("CENTER"));}
    private Map<String,Object> reportView(Map<String,Object> raw,boolean internal){Map<String,Object> r=map("id",raw.get("id"),"code",raw.get("code"),"kind",raw.get("kind"),"value",internal?s.decrypt(raw.get("value_enc")):mask(s.decrypt(raw.get("value_enc"))),"created_at",raw.get("created_at"),"processing",raw.get("processing"),"source",raw.get("source"),"alert_id",raw.get("alert_id"));r.putAll(s.decode(s.decrypt(raw.get("payload_enc"))));if(internal)r.put("normalized",Rules.normalize(str(raw,"kind"),s.decrypt(raw.get("value_enc"))));r.put("attachments",files.list("report",num(raw,"id")));if(num(raw,"alert_id")>0){var a=s.one("SELECT state,level,score,updated_at FROM af_alert WHERE id=?",raw.get("alert_id"));if(!internal){a.remove("level");a.remove("score");}r.put("progress",a);r.put("timeline",s.rows("SELECT action,new_state,created_at FROM af_log WHERE (entity_type='report' AND entity_id=?) OR (entity_type='alert' AND entity_id=?) ORDER BY id",raw.get("id"),raw.get("alert_id")));}return r;}
    public Map<String,Object> anonymous(Map<String,Object> b){String code=required(b,"code",40),token=required(b,"token",80);var r=s.one("SELECT r.code,r.processing,r.created_at,a.state,a.updated_at FROM af_report r LEFT JOIN af_alert a ON a.id=r.alert_id WHERE r.code=? AND r.token_hash=?",code,hash(token));return r;}
    @Transactional(isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED) public Map<String,Object> queryToken(User u,long id,Map<String,Object> b){
        u.roles("CENTER","PUBLIC");var report=s.one("SELECT * FROM af_report WHERE id=?"+(u.role().equals("PUBLIC")?" AND owner_id=?":"")+" FOR UPDATE",u.role().equals("PUBLIC")?new Object[]{id,u.id()}:new Object[]{id});
        String mode=required(b,"mode",20),note=required(b,"note",300);require(Set.of("重置","停用").contains(mode),"凭证操作无效");
        String token=mode.equals("重置")?random():null;s.db.update("UPDATE af_report SET token_hash=?,query_token_enc=? WHERE id=?",token==null?null:hash(token),s.encrypt(token),id);
        s.log(u,"管理查询凭证","report",id,null,mode,note);
        return map("code",report.get("code"),"queryToken",token,"credentialReset",true,"ok",true);
    }
    public List<Map<String,Object>> alerts(User u){u.roles("CENTER");return s.rows("SELECT a.*,c.name community_name,(SELECT COUNT(*) FROM af_report r WHERE r.alert_id=a.id) clue_count FROM af_alert a LEFT JOIN af_community c ON c.id=a.community_id ORDER BY FIELD(a.level,'高','中','低'),a.id DESC LIMIT 300");}
    public Map<String,Object> alert(User u,long id){u.roles("CENTER");var now=s.now();long timeout=num(config(),"timeoutMinutes");var a=s.one("SELECT * FROM af_alert WHERE id=?",id);a.put("freeze_review_ready",freezeReviewReady(a));a.put("reports",s.rows("SELECT * FROM af_report WHERE alert_id=? ORDER BY id",id).stream().map(r->reportView(r,true)).toList());a.put("scores",s.rows("SELECT * FROM af_score WHERE alert_id=? ORDER BY id DESC LIMIT 100",id).stream().peek(r->r.put("details",s.decode(str(r,"details")))).toList());a.put("assessments",s.rows("SELECT x.*,u.display_name FROM af_assessment x JOIN af_user u ON u.id=x.actor_id WHERE x.alert_id=? ORDER BY x.id DESC",id));a.put("commands",s.rows("SELECT id,code,kind,state,bank_id,retry_of,active_key,requested,actual,remaining,expires_at,created_at,accepted_at FROM af_command WHERE alert_id=? ORDER BY id",id).stream().peek(c->{var deadline=Commands.deadline(c,timeout);boolean passed=deadline!=null&&!now.isBefore(deadline);c.put("dispatch_deadline_passed",passed);c.put("dispatch_reusable",c.remove("active_key")!=null&&!passed);}).toList());a.put("visits",s.rows("SELECT * FROM af_visit WHERE alert_id=?",id));a.put("timeline",s.rows("SELECT action,old_state,new_state,note,created_at FROM af_log WHERE entity_type='alert' AND entity_id=? ORDER BY id",id));return a;}
    @Transactional(isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED) public void review(User u,long id,Map<String,Object> b){u.roles("CENTER");var a=s.lockAlert(id,num(b,"victimId"));conflict(!Set.of("已闭环","已驳回","已作废").contains(str(a,"state")),"当前预警已结束");String decision=required(b,"decision",20),note=required(b,"note",1000);require(Set.of("通过","驳回").contains(decision),"审核结论无效");boolean dispatched=s.count("SELECT COUNT(*) FROM af_command WHERE alert_id=?",id)>0;
        long victim=num(b,"victimId")>0?num(b,"victimId"):num(a,"victim_id");long community=num(b,"communityId")>0?num(b,"communityId"):num(a,"community_id");if(dispatched)require(victim==num(a,"victim_id")&&community==num(a,"community_id"),"派发后不能修改受害人或社区归属，请在原任务下处理补充线索");if(victim>0)s.one("SELECT id FROM af_victim WHERE id=?",victim);if(community>0)s.one("SELECT id FROM af_community WHERE id=?",community);String level=str(b,"level");if(level.isEmpty())level=str(a,"level");require(Set.of("高","中","低").contains(level),"风险等级无效");String next=dispatched?str(a,"state"):decision.equals("通过")?"已核实":"已驳回";
        s.db.update("INSERT INTO af_assessment(alert_id,actor_id,decision,note,created_at) VALUES(?,?,?,?,?)",id,u.id(),decision,note,s.now());s.db.update("UPDATE af_alert SET state=?,victim_id=?,community_id=?,level=?,needs_review=FALSE,reviewed_by=?,review_note=?,updated_at=? WHERE id=?",next,victim==0?null:victim,community==0?null:community,level,u.id(),note,s.now(),id);if(victim>0)s.db.update("UPDATE af_report SET victim_id=? WHERE alert_id=?",victim,id);if(victim!=num(a,"victim_id"))s.log(u,"更正受害人关联","alert",id,null,null,"受害人 #"+num(a,"victim_id")+" → #"+victim+"；同步关联举报，提交账号保持不变；"+note);s.log(u,"人工复核","alert",id,str(a,"state"),next,decision+"："+note);
    }
    @Transactional(isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED,noRollbackFor=DispatchDeadlineChanged.class) public Map<String,Object> dispatch(User u,long id,Map<String,Object> b){u.roles("CENTER");var a=s.lockAlert(id);conflict(freezeReviewReady(a),"请先完成有效人工复核；新增线索须补充复核通过");require(num(a,"victim_id")>0&&num(a,"community_id")>0,"请先补录受害人及社区");long bankId=num(b,"bankId");s.one("SELECT id FROM af_bank WHERE id=?",bankId);String activeKey=id+":"+bankId;var old=s.optional("SELECT * FROM af_command WHERE active_key=? FOR UPDATE",activeKey);var bank=s.one("SELECT * FROM af_bank WHERE id=? FOR UPDATE",bankId);long commandId;
        if(old!=null){
            // Read the clock after all row locks: waiting for funds may cross the deadline.
            commands.getObject().scanOne(num(old,"id"),s.now(),u,false);
            var checked=s.one("SELECT state,active_key FROM af_command WHERE id=?",old.get("id"));
            if(checked.get("active_key")==null)throw new DispatchDeadlineChanged(str(checked,"state"));
        }
        if(old==null){
            // Clearing active_key ends the active slot, not this account's dispatch history.
            // The alert lock also serializes receipts and retries with this history check.
            var previous=s.optional("SELECT id,code,state FROM af_command WHERE alert_id=? AND bank_id=? AND kind='止付' ORDER BY id DESC LIMIT 1",id,bankId);
            if(previous!=null)throw new Problem(409,Set.of("拒绝执行","未处理","已拒绝").contains(str(previous,"state"))?"该账户已有失败止付 "+str(previous,"code")+"，请进入原指令详情使用“复核重发”":"该账户已有止付记录 "+str(previous,"code")+"，不能重新派发；如需再次处置，请另行受理预警");
            BigDecimal request=str(b,"amount").isEmpty()?null:amount(b,"amount");require(request==null||request.signum()>0,"申请金额须大于0");commandId=s.insert("INSERT INTO af_command(code,kind,alert_id,bank_id,org_id,victim_id,requested,active_key,created_at) VALUES(?,'止付',?,?,?,?,?,?,?)",s.code("ZF"),id,bankId,bank.get("org_id"),a.get("victim_id"),request,activeKey,s.now());
        }else commandId=num(old,"id");
        var visit=s.optional("SELECT id FROM af_visit WHERE alert_id=?",id);long visitId=visit==null?s.insert("INSERT INTO af_visit(code,alert_id,community_id,due_at,created_at) VALUES(?,?,?,?,?)",s.code("SM"),id,a.get("community_id"),s.now().plusHours(str(a,"level").equals("高")?2:24),s.now()):num(visit,"id");String next=str(a,"state").equals("处置中")?"处置中":"已派单";s.db.update("UPDATE af_alert SET state=?,dispatch_error=NULL,updated_at=? WHERE id=?",next,s.now(),id);s.log(u,"联动派发","alert",id,str(a,"state"),next,"已关联止付及上门任务");return map("commandId",commandId,"visitId",visitId,"reused",old!=null);
    }
    @Transactional(isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED) public void close(User u,long id,Map<String,Object> b){u.roles("CENTER");var a=s.lockAlert(id);conflict(!Set.of("已闭环","已驳回","已作废").contains(str(a,"state"))&&!yes(a,"needs_review"),"当前状态不可办结或仍需补充复核");var visit=s.one("SELECT * FROM af_visit WHERE alert_id=? FOR UPDATE",id);conflict(Set.of("已回传","已归档").contains(str(visit,"state")),"上门任务尚未回传");var commands=s.rows("SELECT c.* FROM af_command c WHERE c.alert_id=? AND c.kind='止付'",id);require(!commands.isEmpty(),"缺少止付指令");boolean exceptional=!str(visit,"result").equals("已完成");for(var c:commands){conflict(!Set.of("待下发","已送达","受理中").contains(str(c,"state")),"机构尚未反馈");exceptional|=Set.of("拒绝执行","未处理").contains(str(c,"state"));}String note=required(b,"note",1000);if(exceptional)require(note.length()>=6,"异常办结须详细记录处理决定");s.db.update("UPDATE af_alert SET state='已闭环',exception_note=?,updated_at=? WHERE id=?",note,s.now(),id);s.db.update("UPDATE af_visit SET state='已归档' WHERE id=?",visit.get("id"));s.log(u,"确认办结","alert",id,str(a,"state"),"已闭环",note);
    }

    @Transactional(isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED) public void voidAlert(User u,long id,Map<String,Object> b){
        u.roles("CENTER");var a=s.lockAlert(id);
        conflict(!Set.of("已闭环","已驳回","已作废").contains(str(a,"state")),"已结束预警不可作废");
        var commands=s.rows("SELECT * FROM af_command WHERE alert_id=? FOR UPDATE",id);
        for(var c:commands)conflict(str(c,"state").equals("已送达")&&str(c,"kind").equals("止付"),"机构已受理或已经执行，须通过后续处理和更正流程办理");
        var v=s.optional("SELECT * FROM af_visit WHERE alert_id=? FOR UPDATE",id);
        conflict(v==null||Set.of("待接收","待上门").contains(str(v,"state")),"网格已到场，不可直接作废");
        String note=required(b,"note",1000);
        for(var c:commands){s.db.update("UPDATE af_command SET state='已作废',active_key=NULL WHERE id=?",c.get("id"));s.log(u,"联动作废","command",num(c,"id"),str(c,"state"),"已作废",note);}
        if(v!=null){s.db.update("UPDATE af_visit SET state='已作废' WHERE id=?",v.get("id"));s.log(u,"联动作废","visit",num(v,"id"),str(v,"state"),"已作废",note);}
        s.db.update("UPDATE af_alert SET state='已作废',needs_review=FALSE,exception_note=?,updated_at=? WHERE id=?",note,s.now(),id);
        s.log(u,"预警作废","alert",id,str(a,"state"),"已作废",note);
    }
}
