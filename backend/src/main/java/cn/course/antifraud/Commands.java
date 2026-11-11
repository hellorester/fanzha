package cn.course.antifraud;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;
import java.math.*;
import java.time.*;
import static cn.course.antifraud.Support.*;

@Service
public class Commands {
    private final Support s;private final Clues clues;private final Files files;private final Relationships relationships;
    public Commands(Support s,Clues clues,Files files,Relationships relationships){this.s=s;this.clues=clues;this.files=files;this.relationships=relationships;}
    public void access(User u,Map<String,Object> c){u.roles("CENTER","PAYMENT","PUBLIC");if(u.role().equals("PAYMENT")&&num(c,"org_id")!=u.orgId()||u.role().equals("PUBLIC")&&(!u.verified()||u.victimId()==0||!(Set.of("止付","返还").contains(str(c,"kind"))&&num(c,"victim_id")==u.victimId()||Set.of("止付","解除").contains(str(c,"kind"))&&relationships.holder(c)==u.victimId())))throw new Problem(404,"指令不存在或无权访问");}
    public List<Map<String,Object>> list(User u){u.roles("CENTER","PAYMENT","PUBLIC");String scope=u.role().equals("PAYMENT")?" WHERE c.org_id=?":u.role().equals("PUBLIC")?" WHERE "+Relationships.commandScope(u):"";Object[] params=u.role().equals("PAYMENT")?new Object[]{u.orgId()}:new Object[]{};return s.rows("SELECT c.*,o.name org_name,b.number_enc FROM af_command c JOIN af_org o ON o.id=c.org_id JOIN af_bank b ON b.id=c.bank_id"+scope+" ORDER BY c.id DESC LIMIT 300",params).stream().peek(c->{c.put("account",mask(s.decrypt(c.remove("number_enc"))));applicationEligibility(u,c);hidePublicInternals(u,c);}).toList();}
    public Map<String,Object> detail(User u,long id){var c=s.one("SELECT * FROM af_command WHERE id=?",id);access(u,c);var alert=s.one("SELECT * FROM af_alert WHERE id=?",c.get("alert_id"));c.put("alert_state",alert.get("state"));capabilities(c,alert);applicationEligibility(u,c);if(u.role().equals("CENTER")){c.put("alert_needs_review",alert.get("needs_review"));c.put("freeze_review_ready",clues.freezeReviewReady(alert));c.put("target_verified",s.count("SELECT COUNT(*) FROM af_alert_bank WHERE alert_id=? AND bank_id=?",c.get("alert_id"),c.get("bank_id"))>0);}var bank=s.one("SELECT * FROM af_bank WHERE id=?",c.get("bank_id"));c.put("bank",map("id",bank.get("id"),"account",mask(s.decrypt(bank.get("number_enc"))),"holder",u.role().equals("PUBLIC")&&u.victimId()!=relationships.holder(c)?"***":s.decrypt(bank.get("holder_enc")),"available",u.role().equals("PUBLIC")?null:bank.get("available")));c.put("receipts",s.rows("SELECT r.*,u.display_name FROM af_receipt r JOIN af_user u ON u.id=r.actor_id WHERE command_id=? ORDER BY version DESC",id).stream().peek(r->r.put("attachments",u.role().equals("PUBLIC")?List.of():files.list("receipt",num(r,"id")))).toList());c.put("timeline",s.rows("SELECT action,old_state,new_state,note,created_at FROM af_log WHERE entity_type='command' AND entity_id=? ORDER BY id",id));if(!u.role().equals("PUBLIC")){c.put("reportMaterials",s.rows("SELECT a.id,a.filename,a.mime FROM af_attachment a JOIN af_report r ON a.entity_type='report' AND a.entity_id=r.id WHERE r.alert_id=?",c.get("alert_id")));if(num(c,"application_id")>0){var app=s.one("SELECT id,code,kind,reason,note,amount,approved,payee_enc,applicant_person_id FROM af_application WHERE id=?",c.get("application_id"));applicationIdentity(app);app.put("attachments",files.list("application",num(app,"id")));c.put("application",app);}}if(u.role().equals("PUBLIC")){hidePublicInternals(u,c);for(var row:(List<Map<String,Object>>)c.get("timeline"))row.remove("note");for(var row:(List<Map<String,Object>>)c.get("receipts")){row.remove("reason");row.remove("voucher");row.remove("actor_id");row.remove("display_name");}}return c;}
    public void applicationEligibility(User u,Map<String,Object> c){
        boolean live=liveFreeze(c,s.now());c.put("application_eligible",live);
        c.put("refund_eligible",live&&u.verified()&&num(c,"victim_id")==u.victimId());
        c.put("appeal_eligible",live&&u.verified()&&relationships.holder(c)==u.victimId()&&!hasAppeal(s,num(c,"id")));
        if(u.role().equals("PUBLIC")){c.put("relationship",num(c,"victim_id")==u.victimId()?"受害人相关":"本人资金账户");c.remove("victim_id");}
    }
    private static void hidePublicInternals(User u,Map<String,Object> c){if(u.role().equals("PUBLIC")){c.remove("active_key");c.remove("period_version");}}
    @Transactional(isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED,noRollbackFor=CommandTimeout.class) public void accept(User u,long id){u.roles("PAYMENT");var peek=s.one("SELECT * FROM af_command WHERE id=?",id);access(u,peek);var original=lockOriginal(peek);var c=s.one("SELECT * FROM af_command WHERE id=? FOR UPDATE",id);deadlines(u,c,original);conflict(str(c,"state").equals("已送达"),"指令已受理或不处于待受理状态");s.db.update("UPDATE af_command SET state='受理中',accepted_at=? WHERE id=?",s.now(),id);s.log(u,"机构受理","command",id,str(c,"state"),"受理中","等待人工核查和回执");}
    // Only this rejection commits its timeout transition; all other business errors roll back.
    static class CommandTimeout extends Problem {
        CommandTimeout(){this("指令已超时，已转中心督办");}
        CommandTimeout(String message){super(409,message);}
    }
    static LocalDateTime deadline(Map<String,Object> c,long timeoutMinutes){
        String state=str(c,"state");
        long snapshot=num(c,"timeout_minutes");if(snapshot<=0)snapshot=timeoutMinutes;
        if(Set.of("已送达","受理中").contains(state))return time(c.get("accepted_at")==null?c.get("created_at"):c.get("accepted_at")).plusMinutes(snapshot);
        return str(c,"kind").equals("止付")&&Set.of("生效中","已延期").contains(state)?time(c.get("expires_at")):null;
    }
    private boolean expirePending(Map<String,Object> c,Map<String,Object> original,LocalDateTime now,User actor,String label){
        String state=str(c,"state");if(!Set.of("已送达","受理中").contains(state))return false;
        var deadline=deadline(c,num(clues.config(),"timeoutMinutes"));
        if(now.isBefore(deadline))return false;
        long id=num(c,"id");s.db.update("UPDATE af_command SET state='未处理',active_key=NULL WHERE id=?",id);
        if(str(c,"kind").equals("返还"))s.db.update("UPDATE af_command SET reserved=GREATEST(0,reserved-?) WHERE id=?",c.get("requested"),original.get("id"));
        notice(id,num(c,"period_version"),"超时",label+"机构超时未回执");s.log(actor,label+"超时检查","command",id,state,"未处理","转中心督办");return true;
    }
    private Map<String,Object> lockOriginal(Map<String,Object> c){s.lockAlert(num(c,"alert_id"));return s.one("SELECT * FROM af_command WHERE id=? FOR UPDATE",num(c,"original_id")>0?c.get("original_id"):c.get("id"));}
    private Map<String,Object> lockCommand(long id){var peek=s.one("SELECT * FROM af_command WHERE id=?",id);var original=lockOriginal(peek);return num(original,"id")==id?original:s.one("SELECT * FROM af_command WHERE id=? FOR UPDATE",id);}
    private void notSuperseded(Map<String,Object> c){
        conflict(s.count("SELECT COUNT(*) FROM af_command WHERE retry_of=?",c.get("id"))==0,"原指令已重发，请处理最新指令");
        if(str(c,"kind").equals("止付"))conflict(s.count("SELECT COUNT(*) FROM af_command WHERE alert_id=? AND bank_id=? AND kind='止付' AND id>?",c.get("alert_id"),c.get("bank_id"),c.get("id"))==0,"已有后续止付指令，请处理最新指令");
    }
    private void active(Map<String,Object> c){conflict(liveFreeze(c,s.now()),"原止付已结束、到期或无剩余冻结资金");}
    static boolean liveFreeze(Map<String,Object> c,LocalDateTime now){return str(c,"kind").equals("止付")&&Set.of("生效中","已延期").contains(str(c,"state"))&&c.get("expires_at")!=null&&now.isBefore(time(c.get("expires_at")))&&amount(c,"remaining").signum()>0;}
    private void capabilities(Map<String,Object> c,Map<String,Object> alert){
        var now=s.now();var config=clues.config();long id=num(c,"id");String kind=str(c,"kind"),state=str(c,"state");
        var original=num(c,"original_id")>0?s.one("SELECT * FROM af_command WHERE id=?",c.get("original_id")):c;
        var due=deadline(c,num(config,"timeoutMinutes"));
        if(original!=c&&original.get("expires_at")!=null&&(due==null||time(original.get("expires_at")).isBefore(due)))due=time(original.get("expires_at"));
        boolean elapsed=due!=null&&!now.isBefore(due),originalLive=liveFreeze(original,now);
        boolean latest=s.count("SELECT COUNT(*) FROM af_command WHERE retry_of=?",id)==0&&(!kind.equals("止付")||s.count("SELECT COUNT(*) FROM af_command WHERE alert_id=? AND bank_id=? AND kind='止付' AND id>?",c.get("alert_id"),c.get("bank_id"),id)==0);
        boolean pending=!elapsed&&(original==c||originalLive);
        boolean correction=kind.equals("止付")&&latest&&!elapsed&&Set.of("生效中","已延期","拒绝执行").contains(state)&&s.count("SELECT COUNT(*) FROM af_receipt WHERE command_id=? AND valid=TRUE",id)==1&&s.count("SELECT COUNT(*) FROM af_command WHERE original_id=?",id)==0&&s.count("SELECT COUNT(*) FROM af_correction WHERE command_id=? AND state='待审核'",id)==0;
        c.put("deadline_at",due);c.put("deadline_passed",elapsed);c.put("application_eligible",original==c&&originalLive);
        c.put("remaining_seconds",due==null?null:Math.max(0,Duration.between(now,due).getSeconds()));
        c.put("actions",map("accept",state.equals("已送达")&&pending,"receipt",state.equals("受理中")&&pending,"extend",original==c&&originalLive&&num(c,"extension_count")<num(config,"maxExtensions"),"retry",latest&&Set.of("未处理","拒绝执行","已拒绝").contains(state)&&(kind.equals("止付")?clues.freezeReviewReady(alert)&&s.count("SELECT COUNT(*) FROM af_alert_bank WHERE alert_id=? AND bank_id=?",c.get("alert_id"),c.get("bank_id"))>0:originalLive),"correct",correction));
    }
    // All business entries share the lock order and the same clock after the funds lock.
    // Only the dedicated deadline rejection commits; ordinary validation/SQL errors roll back.
    private void deadlines(User u,Map<String,Object> c,Map<String,Object> original){
        long payee=0;if(str(c,"kind").equals("返还")&&num(c,"application_id")>0)payee=num(s.one("SELECT payee_bank_id FROM af_application WHERE id=?",c.get("application_id")),"payee_bank_id");
        java.util.stream.LongStream.of(num(original,"bank_id"),payee).filter(v->v>0).distinct().sorted().forEach(v->s.one("SELECT id FROM af_bank WHERE id=? FOR UPDATE",v));
        var now=s.now();
        if(Set.of("生效中","已延期").contains(str(original,"state"))&&original.get("expires_at")!=null&&!now.isBefore(time(original.get("expires_at")))){
            scanOne(num(original,"id"),now,u,false);
            throw new CommandTimeout("原止付已到期，剩余冻结已释放，未完成申请及执行任务已结束，请刷新查看");
        }
        if(expirePending(c,original,now,u,""))throw new CommandTimeout();
    }
    static void endApplications(Support s,long originalId,User actor,String reason){
        // Approval reserves funds but is not a payment. Preserve every credited application,
        // including partial payments, and expire unpaid approvals when the freeze ends.
        for(var a:s.rows("SELECT a.id,a.state FROM af_application a WHERE a.original_id=? AND (a.state IN ('待核定','待受理','审核中','驳回待补') OR (a.kind='返还' AND a.state='核定通过' AND NOT EXISTS (SELECT 1 FROM af_command c WHERE c.application_id=a.id AND c.kind='返还' AND c.actual>0))) ORDER BY a.id FOR UPDATE",originalId)){
            s.db.update("UPDATE af_application SET state='已失效',updated_at=? WHERE id=?",s.now(),a.get("id"));
            s.log(actor,"申请失效","application",num(a,"id"),str(a,"state"),"已失效",reason+(str(a,"state").equals("核定通过")?"；本申请尚未入账，原核定金额和审核意见保留":""));
        }
    }
    private void correctionEligible(Map<String,Object> c){
        conflict(str(c,"kind").equals("止付")&&Set.of("生效中","已延期","拒绝执行").contains(str(c,"state")),"当前止付状态不能更正回执");
        notSuperseded(c);
        conflict(s.count("SELECT COUNT(*) FROM af_command WHERE original_id=?",c.get("id"))==0&&amount(c,"returned").signum()==0&&amount(c,"reserved").signum()==0,"存在返还或解除依赖，禁止直接更正");
    }
    private void validateResult(String kind,String result,BigDecimal amount,BigDecimal requested,String reason){
        require(amount.signum()>=0,"实际金额不能为负数");require(reason.length()<=1000,"执行说明或原因最多1000字");require((kind.equals("解除")?Set.of("解除成功","拒绝执行"):Set.of("全额执行","部分执行","拒绝执行")).contains(result),"回执结果不适用于该指令");
        if(result.equals("拒绝执行")){require(amount.signum()==0,"拒绝执行的金额应为0");require(!reason.isBlank(),"拒绝执行必须填写原因");}
        else if(!kind.equals("解除")){require(amount.signum()>0,"执行金额须大于0");if(requested!=null){require(amount.compareTo(requested)<=0,"执行金额超过指令金额");if(result.equals("全额执行"))require(amount.compareTo(requested)==0,"全额执行必须等于指令金额");if(result.equals("部分执行"))require(amount.compareTo(requested)<0,"部分执行必须小于指令金额");}if(result.equals("部分执行"))require(!reason.isBlank(),"部分执行必须填写原因");}
    }
    @Transactional(isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED,noRollbackFor=CommandTimeout.class) public void receipt(User u,long id,Map<String,Object> b,String owner){
        u.roles("PAYMENT");var peek=s.one("SELECT * FROM af_command WHERE id=?",id);access(u,peek);var original=lockOriginal(peek);var c=s.one("SELECT * FROM af_command WHERE id=? FOR UPDATE",id);deadlines(u,c,original);conflict(str(c,"state").equals("受理中"),"请先受理；已回执、超时或失效指令不能重复执行");conflict(s.count("SELECT COUNT(*) FROM af_receipt WHERE command_id=? AND valid=TRUE",id)==0,"已经存在有效回执");String kind=str(c,"kind"),result=required(b,"result",30),reason=str(b,"reason"),voucher=required(b,"voucher",100);BigDecimal amount=amount(b,"amount"),request=c.get("requested")==null?null:amount(c,"requested");validateResult(kind,result,amount,request,reason);var bank=s.one("SELECT * FROM af_bank WHERE id=? FOR UPDATE",c.get("bank_id"));BigDecimal available=amount(bank,"available");String state;
        if(kind.equals("止付")){
            require(amount.compareTo(available)<=0,"超过账户可用余额");state=result.equals("拒绝执行")?"拒绝执行":"生效中";available=available.subtract(amount);s.db.update("UPDATE af_command SET state=?,actual=?,remaining=?,expires_at=?,active_key=IF(?='拒绝执行',NULL,active_key) WHERE id=?",state,amount,amount,result.equals("拒绝执行")?null:s.now().plusHours(num(clues.config(),"freezeHours")),state,id);
        }else if(kind.equals("返还")){
            var application=s.one("SELECT * FROM af_application WHERE id=?",c.get("application_id"));relationships.validApplication(application,original);active(original);BigDecimal remaining=amount(original,"remaining"),reserved=amount(original,"reserved");require(request!=null&&reserved.compareTo(request)>=0&&amount.compareTo(remaining)<=0,"剩余冻结金额或核定占用不足");s.db.update("UPDATE af_command SET remaining=remaining-?,returned=returned+?,reserved=reserved-? WHERE id=?",amount,amount,request,original.get("id"));state=result.equals("拒绝执行")?"已拒绝":"已执行";s.db.update("UPDATE af_command SET state=?,actual=? WHERE id=?",state,amount,id);
            if(amount.signum()>0){
                var target=s.one("SELECT * FROM af_bank WHERE id=? FOR UPDATE",application.get("payee_bank_id"));var credited=amount(target,"available").add(amount);money(credited.add(amount(s.one("SELECT COALESCE(SUM(remaining),0) frozen FROM af_command WHERE bank_id=? AND kind='止付'",target.get("id")),"frozen")));
                s.db.update("UPDATE af_bank SET available=? WHERE id=?",credited,target.get("id"));
                s.db.update("INSERT INTO af_ledger(command_id,bank_id,action,amount,available_after,note,created_at) VALUES(?,?,'返还入账',?,?,?,?)",id,target.get("id"),amount,credited,"受害人本人账户收款",s.now());
            }
            if(amount.signum()>0&&remaining.compareTo(amount)==0){
                release(original,u,"冻结资金已全部返还");
                s.log(u,"全额返还结束止付","command",num(original,"id"),str(original,"state"),"已解除","冻结资金已全部返还；未决定申请及未执行后续指令已结束，保留原审核决定");
            }
        }else{
            if(result.equals("解除成功")){relationships.validApplication(s.one("SELECT * FROM af_application WHERE id=?",c.get("application_id")),original);active(original);amount=amount(original,"remaining");available=available.add(amount);state="已解除";}else{state="拒绝执行";amount=BigDecimal.ZERO;}
            s.db.update("UPDATE af_command SET state=?,actual=? WHERE id=?",state,amount,id);
            if(result.equals("解除成功"))release(original,u,"申诉解除");
        }
        s.db.update("UPDATE af_bank SET available=? WHERE id=?",available,bank.get("id"));long receiptId=s.insert("INSERT INTO af_receipt(command_id,version,result,amount,reason,voucher,actor_id,created_at) VALUES(?,1,?,?,?,?,?,?)",id,result,amount,reason,voucher,u.id(),s.now());files.bind(b.get("attachments"),u,owner,"receipt",receiptId,false);s.db.update("INSERT INTO af_ledger(command_id,bank_id,action,amount,available_after,note,created_at) VALUES(?,?,?,?,?,?,?)",id,bank.get("id"),kind+result,amount,available,"资金操作",s.now());s.db.update("UPDATE af_alert SET state=IF(state='已派单','处置中',state),updated_at=? WHERE id=?",s.now(),c.get("alert_id"));if(result.equals("拒绝执行"))notice(id,num(c,"period_version"),"拒绝执行","机构拒绝执行，需要中心处理");if(kind.equals("解除")&&result.equals("解除成功"))s.log(u,"申诉解除","command",num(original,"id"),str(original,"state"),"已解除","剩余冻结资金已释放；未完成后续指令失效");s.log(u,"提交回执","command",id,"受理中",state,result+(reason.isEmpty()?"":"："+reason));
    }
    private void release(Map<String,Object> original,User u,String reason){finishFreeze(s,original,u,reason);}
    // Called under the original command lock, or during a transactional startup migration.
    static void finishFreeze(Support s,Map<String,Object> original,User u,String reason){long id=num(original,"id");
        endApplications(s,id,u,reason+"，原止付不再生效");
        for(var x:s.rows("SELECT id FROM af_correction WHERE command_id=? AND state='待审核' ORDER BY id FOR UPDATE",id)){
            s.db.update("UPDATE af_correction SET state='已失效',review_note=? WHERE id=?",reason+"，已不能更正原回执",x.get("id"));
            s.log(u,"更正失效","correction",num(x,"id"),"待审核","已失效",reason);
        }
        s.db.update("UPDATE af_command SET state='已解除',remaining=0,reserved=0,active_key=NULL WHERE id=?",id);for(var child:s.rows("SELECT id,kind,state FROM af_command WHERE original_id=? AND state IN ('待下发','已送达','受理中') ORDER BY id FOR UPDATE",id)){
            String next=str(child,"kind").equals("返还")?"已取消":"已失效";
            s.db.update("UPDATE af_command SET state=?,active_key=NULL WHERE id=?",next,child.get("id"));
            s.log(u,"结束后续指令","command",num(child,"id"),str(child,"state"),next,reason);
        }
    }
    static boolean hasAppeal(Support s,long originalId){
        return s.count("SELECT COUNT(*) FROM af_application WHERE original_id=? AND kind='申诉' AND state IN ('待受理','审核中','驳回待补','已生成解除指令','已维持')",originalId)>0;
    }
    public List<Map<String,Object>> applications(User u){u.roles("CENTER","PUBLIC");return s.rows("SELECT a.id,a.code,a.kind,a.state,a.amount,a.approved,a.reason,a.note,a.original_id,a.created_at,c.code original_code,(SELECT state FROM af_command x WHERE x.application_id=a.id ORDER BY id DESC LIMIT 1) execution_state"+RefundProgress.LIST_FIELDS+" FROM af_application a JOIN af_command c ON c.id=a.original_id"+(u.role().equals("PUBLIC")?" WHERE a.owner_id=?":"")+" ORDER BY a.id DESC LIMIT 300",u.role().equals("PUBLIC")?new Object[]{u.id()}:new Object[]{}).stream().peek(a->RefundProgress.decorateListRow(a,s.now())).toList();}
    private void applicationIdentity(Map<String,Object> a){
        Object encrypted=a.remove("payee_enc");a.put("payee",str(a,"kind").equals("返还")&&encrypted!=null?mask(s.decrypt(encrypted)):null);
        if(num(a,"applicant_person_id")>0)a.put("applicant_name",s.decrypt(s.one("SELECT name_enc FROM af_victim WHERE id=?",a.get("applicant_person_id")).get("name_enc")));
    }
    @Transactional(readOnly=true,isolation=org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public Map<String,Object> application(User u,long id){
        u.roles("CENTER","PUBLIC");var a=s.one("SELECT * FROM af_application WHERE id=?"+(u.role().equals("PUBLIC")?" AND owner_id=?":""),u.role().equals("PUBLIC")?new Object[]{id,u.id()}:new Object[]{id});
        var original=s.one("SELECT kind,state,expires_at,remaining,reserved FROM af_command WHERE id=?",a.get("original_id"));
        a.put("originalFunds",map("remaining",original.get("remaining"),"reserved",original.get("reserved"),"available",amount(original,"remaining").subtract(amount(original,"reserved"))));
        applicationIdentity(a);a.put("attachments",files.list("application",id));
        var children=s.rows("SELECT id,code,kind,state,requested,actual FROM af_command WHERE application_id=? ORDER BY id",id);a.put("commands",children);
        if(str(a,"kind").equals("返还"))a.put("fundsProgress",RefundProgress.describe(a,original,children,s.now()));
        a.put("timeline",s.rows("SELECT action,old_state,new_state,note,created_at FROM af_log WHERE entity_type='application' AND entity_id=? ORDER BY id",id));return a;
    }
    @Transactional(isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED,noRollbackFor=CommandTimeout.class) public Map<String,Object> apply(User u,Map<String,Object> b,String owner){u.roles("PUBLIC");s.lockPublicIdentity(u);require(u.verified()&&u.victimId()>0,"请先完成实名身份核验");long originalId=num(b,"originalId");var c=lockCommand(originalId);access(u,c);require(str(c,"kind").equals("止付"),"请选择原止付指令");deadlines(u,c,c);active(c);String kind=required(b,"kind",20);require(Set.of("返还","申诉").contains(kind),"申请类型无效");String reason=required(b,"reason",2000);BigDecimal amount=kind.equals("返还")?amount(b,"amount"):null;
        relationships.applicant(u,kind,c);Map<String,Object> receiving=null;
        if(kind.equals("返还")){require(amount.signum()>0,"申请金额须大于0");receiving=relationships.payee(num(b,"payeeBankId"),u.victimId(),num(c,"bank_id"));}else conflict(!hasAppeal(s,originalId),"该止付已有申诉待办或裁定，请查看原申请记录");
        String state=kind.equals("返还")?"待核定":"待受理",code=s.code(kind.equals("返还")?"FH":"SS");long id=s.insert("INSERT INTO af_application(code,kind,owner_id,original_id,state,amount,reason,payee_enc,created_at,updated_at,applicant_person_id,payee_bank_id) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",code,kind,u.id(),originalId,state,amount,reason,receiving==null?null:receiving.get("number_enc"),s.now(),s.now(),u.victimId(),receiving==null?null:receiving.get("id"));files.bind(b.get("attachments"),u,owner,"application",id,kind.equals("返还"));s.log(u,"提交申请","application",id,null,state,"申请已受理");return map("id",id,"code",code);
    }
    @Transactional(isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED,noRollbackFor=CommandTimeout.class) public void supplement(User u,long id,Map<String,Object> b,String owner){u.roles("PUBLIC");s.lockPublicIdentity(u);var peek=s.one("SELECT * FROM af_application WHERE id=? AND owner_id=?",id,u.id());var original=lockCommand(num(peek,"original_id"));access(u,original);var a=s.one("SELECT * FROM af_application WHERE id=? AND owner_id=? FOR UPDATE",id,u.id());deadlines(u,original,original);active(original);relationships.applicant(u,str(a,"kind"),original);conflict(num(a,"applicant_person_id")==u.victimId(),"账号实名关联已变更，请重新提交申请");conflict(str(a,"state").equals("驳回待补"),"当前申请不在补正状态");String reason=required(b,"reason",2000);files.bind(b.get("attachments"),u,owner,"application",id,true);s.db.update("UPDATE af_application SET reason=?,state='待受理',updated_at=? WHERE id=?",reason,s.now(),id);s.log(u,"补正材料","application",id,"驳回待补","待受理","已补充说明和材料");}
    @Transactional(isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED,noRollbackFor=CommandTimeout.class) public void adjudicate(User u,long id,Map<String,Object> b){u.roles("CENTER");var peek=s.one("SELECT * FROM af_application WHERE id=?",id);var c=lockCommand(num(peek,"original_id"));var a=s.one("SELECT * FROM af_application WHERE id=? FOR UPDATE",id);deadlines(u,c,c);relationships.validApplication(a,c);String decision=required(b,"decision",20),note=required(b,"note",2000),kind=str(a,"kind"),next;BigDecimal approved=null;String commandKind=null;
        if(kind.equals("返还")){conflict(str(a,"state").equals("待核定"),"申请已处理");require(Set.of("通过","驳回").contains(decision),"核定结论无效");next=decision.equals("通过")?"核定通过":"核定驳回";if(decision.equals("通过")){active(c);approved=amount(b,"amount");BigDecimal available=amount(c,"remaining").subtract(amount(c,"reserved"));require(approved.signum()>0&&approved.compareTo(amount(a,"amount"))<=0&&approved.compareTo(available)<=0,"核定金额超过申请额或剩余可返还额");s.db.update("UPDATE af_command SET reserved=reserved+? WHERE id=?",approved,c.get("id"));commandKind="返还";}}
        else{require(Set.of("受理","补正","维持","成立").contains(decision),"裁定动作无效");if(decision.equals("受理")){conflict(str(a,"state").equals("待受理"),"当前申请不可受理");next="审核中";}else{conflict(str(a,"state").equals("审核中"),"请先受理申诉");next=decision.equals("补正")?"驳回待补":decision.equals("维持")?"已维持":"已生成解除指令";if(decision.equals("成立")){active(c);commandKind="解除";}}}
        if(commandKind!=null)s.insert("INSERT INTO af_command(code,kind,alert_id,original_id,application_id,bank_id,org_id,victim_id,requested,active_key,timeout_minutes,created_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",s.code(commandKind.equals("返还")?"TK":"JC"),commandKind,c.get("alert_id"),c.get("id"),id,c.get("bank_id"),c.get("org_id"),c.get("victim_id"),approved,"application:"+id,num(clues.config(),"timeoutMinutes"),s.now());s.db.update("UPDATE af_application SET state=?,approved=?,note=?,updated_at=? WHERE id=?",next,approved,note,s.now(),id);s.log(u,"中心审核","application",id,str(a,"state"),next,note);
    }
    @Transactional(isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED,noRollbackFor=CommandTimeout.class) public void extend(User u,long id,Map<String,Object> b){u.roles("CENTER");var c=lockCommand(id);require(str(c,"kind").equals("止付"),"只有止付可以延期");deadlines(u,c,c);active(c);var cfg=clues.config();conflict(num(c,"extension_count")<num(cfg,"maxExtensions"),"已经达到延期次数上限");String note=required(b,"note",1000);s.db.update("UPDATE af_command SET state='已延期',expires_at=?,extension_count=extension_count+1,period_version=period_version+1 WHERE id=?",time(c.get("expires_at")).plusHours(num(cfg,"extensionHours")),id);s.log(u,"申请延期","command",id,str(c,"state"),"已延期",note);}
    public void notice(long id,long version,String node,String message){s.db.update("INSERT IGNORE INTO af_notice(command_id,period_version,node,message,created_at) VALUES(?,?,?,?,?)",id,version,node,message,s.now());}
    @Transactional(isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED,noRollbackFor=CommandTimeout.class) public void scanOne(long id,LocalDateTime effectiveNow,User actor,boolean simulated){
        var peek=s.optional("SELECT * FROM af_command WHERE id=?",id);if(peek==null)return;var original=lockOriginal(peek);var c=s.one("SELECT * FROM af_command WHERE id=? FOR UPDATE",id);
        if(str(c,"kind").equals("止付")&&Set.of("生效中","已延期").contains(str(c,"state")))s.one("SELECT id FROM af_bank WHERE id=? FOR UPDATE",c.get("bank_id"));
        if(!simulated)effectiveNow=s.now();
        String state=str(c,"state");String label=simulated?"时效检查：":"";
        if(expirePending(c,original,effectiveNow,actor,label))return;
        if(!str(c,"kind").equals("止付")||!Set.of("生效中","已延期").contains(state))return;
        LocalDateTime expiry=time(c.get("expires_at"));if(!effectiveNow.isBefore(expiry)){var bank=s.one("SELECT * FROM af_bank WHERE id=? FOR UPDATE",c.get("bank_id"));BigDecimal release=amount(c,"remaining"),available=amount(bank,"available").add(release);release(c,actor,label+"到期解除");s.db.update("UPDATE af_bank SET available=? WHERE id=?",available,bank.get("id"));s.db.update("INSERT INTO af_ledger(command_id,bank_id,action,amount,available_after,note,created_at) VALUES(?,?,?,?,?,?,?)",id,bank.get("id"),"到期解除",release,available,label+"自动执行",s.now());notice(id,num(c,"period_version"),"到期",label+"原止付到期已解除");s.log(actor,label+"到期解除","command",id,state,"已解除","剩余冻结资金已释放；未完成后续指令失效");}
        else{long seconds=Duration.between(effectiveNow,expiry).getSeconds();if(seconds<=86400)notice(id,num(c,"period_version"),"24小时",label+"剩余时间不超过24小时");if(seconds<=7200)notice(id,num(c,"period_version"),"2小时",label+"剩余时间不超过2小时");}
    }
    public List<Map<String,Object>> notices(User u){u.roles("CENTER");return s.rows("SELECT n.*,c.code,c.state command_state FROM af_notice n JOIN af_command c ON c.id=n.command_id ORDER BY n.id DESC LIMIT 300");}
    @Transactional(isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED,noRollbackFor=CommandTimeout.class) public void resolveNotice(User u,long id,Map<String,Object> b){u.roles("CENTER");String note=required(b,"note",1000);var notice=s.one("SELECT resolved FROM af_notice WHERE id=? FOR UPDATE",id);conflict(!yes(notice,"resolved"),"督办事项已处理，不能重复覆盖处理意见");s.db.update("UPDATE af_notice SET resolved=TRUE,resolution=? WHERE id=?",note,id);s.log(u,"督办处理","notice",id,null,"已处理",note);}
    @Transactional(isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED,noRollbackFor=CommandTimeout.class) public Map<String,Object> retry(User u,long id,Map<String,Object> b){
        u.roles("CENTER");var peek=s.one("SELECT * FROM af_command WHERE id=?",id);
        var alert=s.lockAlert(num(peek,"alert_id"));
        if(str(peek,"kind").equals("止付")){conflict(!Set.of("已闭环","已驳回","已作废").contains(str(alert,"state")),"预警已结束，不能重发新的止付；请另行受理预警");conflict(clues.freezeReviewReady(alert),"请先完成有效人工复核；新增线索须补充复核通过后再重发止付");}
        var original=lockOriginal(peek);var c=s.one("SELECT * FROM af_command WHERE id=? FOR UPDATE",id);
        if(str(c,"kind").equals("止付"))relationships.requireTarget(num(c,"alert_id"),num(c,"bank_id"));else relationships.validApplication(s.one("SELECT * FROM af_application WHERE id=?",c.get("application_id")),original);
        deadlines(u,c,original);
        conflict(Set.of("未处理","拒绝执行","已拒绝").contains(str(c,"state")),"仅失败指令可经复核重发");notSuperseded(c);
        String note=required(b,"note",1000),kind=str(c,"kind");long newId;
        if(kind.equals("止付")){
            String key=c.get("alert_id")+":"+c.get("bank_id");
            conflict(s.count("SELECT COUNT(*) FROM af_command WHERE active_key=?",key)==0,"已有有效重发指令");
            newId=s.insert("INSERT INTO af_command(code,kind,alert_id,bank_id,org_id,victim_id,requested,active_key,timeout_minutes,created_at) VALUES(?,'止付',?,?,?,?,?,?,?,?)",s.code("ZF"),c.get("alert_id"),c.get("bank_id"),c.get("org_id"),c.get("victim_id"),c.get("requested"),key,num(clues.config(),"timeoutMinutes"),s.now());
        }else{
            active(original);long appId=num(c,"application_id");
            conflict(s.count("SELECT MAX(id) FROM af_command WHERE application_id=?",appId)==id,"已有后续重发记录，请处理最新指令");
            if(kind.equals("返还")){
                BigDecimal amount=amount(c,"requested");
                require(amount.compareTo(amount(original,"remaining").subtract(amount(original,"reserved")))<=0,"当前可用冻结额不足原核定金额");
                s.db.update("UPDATE af_command SET reserved=reserved+? WHERE id=?",amount,original.get("id"));
            }
            s.db.update("UPDATE af_command SET active_key=NULL WHERE id=?",id);
            newId=s.insert("INSERT INTO af_command(code,kind,alert_id,original_id,application_id,bank_id,org_id,victim_id,requested,active_key,timeout_minutes,created_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",s.code(kind.equals("返还")?"TK":"JC"),kind,c.get("alert_id"),c.get("original_id"),appId,c.get("bank_id"),c.get("org_id"),c.get("victim_id"),c.get("requested"),"application:"+appId,num(clues.config(),"timeoutMinutes"),s.now());
            s.log(u,"核实重发","application",appId,null,null,note);
        }
        s.db.update("UPDATE af_command SET retry_of=? WHERE id=?",id,newId);
        s.db.update("INSERT INTO af_assessment(alert_id,actor_id,decision,note,created_at) VALUES(?,?,'重发复核',?,?)",c.get("alert_id"),u.id(),note,s.now());
        if(kind.equals("止付"))s.db.update("UPDATE af_alert SET state='处置中',updated_at=? WHERE id=?",s.now(),c.get("alert_id"));
        s.log(u,"核实重发","command",newId,null,"已送达","原指令 "+c.get("code")+"："+note);return map("id",newId);
    }
    @Transactional(isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED,noRollbackFor=CommandTimeout.class)
    public void requestCorrection(User u,long id,Map<String,Object> b){
        u.roles("PAYMENT");var c=lockCommand(id);access(u,c);conflict(str(c,"kind").equals("止付"),"当前仅支持止付回执更正");deadlines(u,c,c);correctionEligible(c);
        String reason=required(b,"reason",1000),result=required(b,"result",30);BigDecimal amount=amount(b,"amount");
        validateResult("止付",result,amount,c.get("requested")==null?null:amount(c,"requested"),reason);
        if(str(c,"state").equals("拒绝执行")&&!result.equals("拒绝执行"))relationships.requireTarget(num(c,"alert_id"),num(c,"bank_id"));
        if(str(c,"state").equals("拒绝执行")&&!result.equals("拒绝执行"))conflict(clues.freezeReviewReady(s.one("SELECT * FROM af_alert WHERE id=?",c.get("alert_id"))),"重新形成冻结前须完成有效人工复核，已结束预警不能恢复冻结");
        var receipt=s.one("SELECT id FROM af_receipt WHERE command_id=? AND valid=TRUE",id);
        conflict(s.count("SELECT COUNT(*) FROM af_correction WHERE command_id=? AND state='待审核'",id)==0,"已有待审核更正");
        s.db.update("INSERT INTO af_correction(command_id,actor_id,reason,new_result,new_amount,base_receipt_id,created_at) VALUES(?,?,?,?,?,?,?)",id,u.id(),reason,result,amount,receipt.get("id"),s.now());
        s.log(u,"申请更正","command",id,str(c,"state"),str(c,"state"),reason);
    }
    public Map<String,Object> correction(User u,long id){
        u.roles("CENTER","PAYMENT");var x=s.one("SELECT x.*,c.code,c.org_id FROM af_correction x JOIN af_command c ON c.id=x.command_id WHERE x.id=?",id);access(u,x);
        x.put("originalReceipt",num(x,"base_receipt_id")>0?s.optional("SELECT version,result,amount,reason,voucher,created_at FROM af_receipt WHERE id=?",x.get("base_receipt_id")):null);return x;
    }
    public List<Map<String,Object>> corrections(User u){u.roles("CENTER","PAYMENT");return s.rows("SELECT x.*,c.code,c.org_id FROM af_correction x JOIN af_command c ON c.id=x.command_id"+(u.role().equals("PAYMENT")?" WHERE c.org_id=?":"")+" ORDER BY x.id DESC LIMIT 300",u.role().equals("PAYMENT")?new Object[]{u.orgId()}:new Object[]{});}
    @Transactional(isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED,noRollbackFor=CommandTimeout.class) public void correct(User u,long id,Map<String,Object> b){u.roles("CENTER");var peek=s.one("SELECT * FROM af_correction WHERE id=?",id);var c=lockCommand(num(peek,"command_id"));var x=s.one("SELECT * FROM af_correction WHERE id=? FOR UPDATE",id);conflict(str(x,"state").equals("待审核"),"更正已处理");String note=required(b,"note",1000);boolean approve=yes(b,"approve");
        if(approve){
            deadlines(u,c,c);correctionEligible(c);
            conflict(s.count("SELECT COUNT(*) FROM af_command WHERE active_key=? AND id<>?",c.get("alert_id")+":"+c.get("bank_id"),c.get("id"))==0,"存在其他有效止付指令，请先处理最新指令");
            var valid=s.one("SELECT id FROM af_receipt WHERE command_id=? AND valid=TRUE",c.get("id"));
            conflict(num(x,"base_receipt_id")==0||num(x,"base_receipt_id")==num(valid,"id"),"有效回执已变更，请基于最新版本重新申请");
            BigDecimal amount=amount(x,"new_amount"),old=amount(c,"actual");String result=str(x,"new_result");
            validateResult("止付",result,amount,c.get("requested")==null?null:amount(c,"requested"),str(x,"reason"));
            boolean restarting=str(c,"state").equals("拒绝执行")&&!result.equals("拒绝执行");
            if(restarting)relationships.requireTarget(num(c,"alert_id"),num(c,"bank_id"));
            if(restarting)conflict(clues.freezeReviewReady(s.one("SELECT * FROM af_alert WHERE id=?",c.get("alert_id"))),"重新形成冻结前须完成有效人工复核，已结束预警不能恢复冻结");
            var bank=s.one("SELECT * FROM af_bank WHERE id=? FOR UPDATE",c.get("bank_id"));
            BigDecimal available=amount(bank,"available").add(old).subtract(amount);require(available.signum()>=0,"更正后账户余额不足");
            long version=s.count("SELECT COALESCE(MAX(version),0)+1 FROM af_receipt WHERE command_id=?",c.get("id"));
            s.db.update("UPDATE af_receipt SET valid=FALSE WHERE command_id=?",c.get("id"));
            s.db.update("INSERT INTO af_receipt(command_id,version,result,amount,reason,voucher,actor_id,created_at) VALUES(?,?,?,?,?,?,?,?)",c.get("id"),version,result,amount,str(x,"reason"),"更正-"+id,u.id(),s.now());
            String state=result.equals("拒绝执行")?"拒绝执行":!restarting&&num(c,"extension_count")>0?"已延期":"生效中";
            s.db.update("UPDATE af_command SET state=?,actual=?,remaining=?,expires_at=?,active_key=?,extension_count=?,period_version=? WHERE id=?",state,amount,amount,result.equals("拒绝执行")?null:restarting?s.now().plusHours(num(clues.config(),"freezeHours")):time(c.get("expires_at")),result.equals("拒绝执行")?null:c.get("alert_id")+":"+c.get("bank_id"),restarting?0:num(c,"extension_count"),num(c,"period_version")+(restarting?1:0),c.get("id"));
            if(result.equals("拒绝执行"))endApplications(s,num(c,"id"),u,"原止付已更正为拒绝执行，不再存在有效冻结");
            s.db.update("UPDATE af_bank SET available=? WHERE id=?",available,bank.get("id"));
            s.db.update("INSERT INTO af_ledger(command_id,bank_id,action,amount,available_after,note,created_at) VALUES(?,?,'回执更正',?,?,?,?)",c.get("id"),c.get("bank_id"),amount.subtract(old),available,note,s.now());
            s.log(u,"批准回执更正","command",num(c,"id"),str(c,"state"),state,note);
        }
        s.db.update("UPDATE af_correction SET state=?,review_note=? WHERE id=?",approve?"已批准":"已驳回",note,id);s.log(u,"审核更正","correction",id,"待审核",approve?"已批准":"已驳回",note);
    }
}
