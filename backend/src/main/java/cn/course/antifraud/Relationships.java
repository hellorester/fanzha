package cn.course.antifraud;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;
import static cn.course.antifraud.Support.*;

/** Verified people, account ownership and evidence-based clue links are separate relationships. */
@Service
public class Relationships {
    private final Support s;
    public Relationships(Support s){this.s=s;}

    public static String commandScope(User u){
        long person=u.verified()?u.victimId():-1;
        return "((c.kind IN ('止付','返还') AND c.victim_id="+person+") OR (c.kind IN ('止付','解除') AND b.owner_person_id="+person+"))";
    }
    public long holder(Map<String,Object> command){return num(s.one("SELECT owner_person_id FROM af_bank WHERE id=?",command.get("bank_id")),"owner_person_id");}
    public boolean applicant(long person,String kind,Map<String,Object> command){
        return person>0&&(kind.equals("返还")?num(command,"victim_id")==person:kind.equals("申诉")&&holder(command)==person);
    }
    public void applicant(User user,String kind,Map<String,Object> command){
        conflict(user.verified()&&applicant(user.victimId(),kind,command),kind.equals("申诉")?"只有被冻结资金账户的已核验户主可以申诉":"只有中心确认的受害人可以申请资金返还");
    }
    public Map<String,Object> payee(long bankId,long person,long sourceBank){
        require(bankId>0,"请选择已核验的本人收款账户");var bank=s.one("SELECT * FROM af_bank WHERE id=?",bankId);
        require(person>0&&num(bank,"owner_person_id")==person,"请选择已核验归属于受害人本人的收款账户");
        require(bankId!=sourceBank,"收款账户不能与被止付账户相同");return bank;
    }
    public void validApplication(Map<String,Object> app,Map<String,Object> original){
        long person=num(app,"applicant_person_id");
        conflict(applicant(person,str(app,"kind"),original),"申请人的实名关系尚未核验或已不符合条件，请重新提交申请");
        if(str(app,"kind").equals("返还"))payee(num(app,"payee_bank_id"),person,num(original,"bank_id"));
    }
    public List<Map<String,Object>> people(User u){
        u.roles("CENTER");return s.rows("SELECT p.*,c.name community_name FROM af_victim p LEFT JOIN af_community c ON c.id=p.community_id ORDER BY p.id").stream().peek(p->{p.put("name",s.decrypt(p.remove("name_enc")));p.put("phone",mask(s.decrypt(p.remove("phone_enc"))));p.put("identity",mask(s.decrypt(p.remove("identity_enc"))));}).toList();
    }
    @Transactional(isolation=Isolation.READ_COMMITTED)
    public long person(User u,Map<String,Object> b){
        u.administrator();String name=required(b,"name",80),identity=required(b,"identity",80),phone=required(b,"phone",30),note=required(b,"note",1000);
        Rules.normalize("号码",phone);s.one("SELECT id FROM af_community WHERE id=?",num(b,"communityId"));
        // Only administrators can establish verified identities; login/profile fields cannot assert ownership.
        s.one("SELECT id FROM af_rule_state WHERE id=1 FOR UPDATE");
        for(var p:s.rows("SELECT identity_enc FROM af_victim"))conflict(!s.decrypt(p.get("identity_enc")).equals(identity),"该证件身份已有人员资料，请使用现有记录");
        long id=s.insert("INSERT INTO af_victim(name_enc,phone_enc,identity_enc,community_id) VALUES(?,?,?,?)",s.encrypt(name),s.encrypt(phone),s.encrypt(identity),num(b,"communityId"));
        s.log(u,"核验人员身份","person",id,null,"已核验",note);return id;
    }
    @Transactional(isolation=Isolation.READ_COMMITTED)
    public long bank(User u,long id,Map<String,Object> b){
        u.administrator();long person=num(b,"personId");var p=s.one("SELECT * FROM af_victim WHERE id=? FOR UPDATE",person);String note=required(b,"note",1000);
        if(id==0){
            long org=num(b,"orgId");s.one("SELECT id FROM af_org WHERE id=?",org);String number=Rules.normalize("账户",required(b,"number",100));var opening=amount(b,"opening");require(opening.signum()>=0,"期初余额不能为负数");
            id=s.insert("INSERT INTO af_bank(org_id,number_hash,number_enc,holder_enc,available,owner_person_id) VALUES(?,?,?,?,?,?)",org,s.fingerprint("账户",number),s.encrypt(number),p.get("name_enc"),opening,person);
            s.db.update("INSERT INTO af_bank_origin(bank_id,opening,source,created_at) VALUES(?,?,'核验开户',?)",id,opening,s.now());
        }else{
            var old=s.one("SELECT * FROM af_bank WHERE id=? FOR UPDATE",id);
            conflict(num(old,"owner_person_id")==0||num(old,"owner_person_id")==person,"已核验账户不能转给另一实名主体；请新建其本人账户");
            s.db.update("UPDATE af_bank SET owner_person_id=?,holder_enc=? WHERE id=?",person,p.get("name_enc"),id);
        }
        s.log(u,"核验账户归属","bank",id,null,"已核验","实名主体 #"+person+"；"+note);return id;
    }
    public List<Map<String,Object>> targets(User u,long alertId){
        u.roles("CENTER");return s.rows("SELECT x.*,b.number_enc,b.owner_person_id,b.holder_enc,o.name org_name,r.code report_code,r.kind,r.value_enc,u.display_name verified_by FROM af_alert_bank x JOIN af_bank b ON b.id=x.bank_id JOIN af_org o ON o.id=b.org_id JOIN af_report r ON r.id=x.report_id JOIN af_user u ON u.id=x.actor_id WHERE x.alert_id=? ORDER BY x.bank_id",alertId).stream().peek(x->{x.put("account",mask(s.decrypt(x.remove("number_enc"))));x.put("holder",s.decrypt(x.remove("holder_enc")));x.put("clue",s.decrypt(x.remove("value_enc")));}).toList();
    }
    public List<Map<String,Object>> candidates(User u,long alertId){
        u.roles("CENTER");
        // Exact bank-number matches and previously verified clue mappings are suggestions, never authorization.
        return s.rows("SELECT DISTINCT b.id,b.number_enc,b.holder_enc,o.name org_name,r.id report_id,IF(r.kind='账户','账号精确匹配','历史核验关联') basis FROM af_report r JOIN af_bank b ON (r.kind='账户' AND r.value_hash=b.number_hash) OR EXISTS(SELECT 1 FROM af_alert_bank x JOIN af_report p ON p.id=x.report_id WHERE x.bank_id=b.id AND p.kind=r.kind AND p.value_hash=r.value_hash) JOIN af_org o ON o.id=b.org_id WHERE r.alert_id=? ORDER BY b.id",alertId).stream().peek(x->{x.put("account",mask(s.decrypt(x.remove("number_enc"))));x.put("holder",s.decrypt(x.remove("holder_enc")));}).toList();
    }
    @Transactional(isolation=Isolation.READ_COMMITTED)
    public void link(User u,long alertId,Map<String,Object> b){
        u.roles("CENTER");var a=s.lockAlert(alertId);conflict(!Set.of("已闭环","已驳回","已作废").contains(str(a,"state")),"已结束预警不能变更账户关联");
        long bank=num(b,"bankId"),report=num(b,"reportId");String note=required(b,"note",1000);require(note.length()>=6,"请填写至少6字的账户关联核验依据");
        s.one("SELECT id FROM af_report WHERE id=? AND alert_id=?",report,alertId);s.one("SELECT id FROM af_bank WHERE id=?",bank);
        var existing=s.optional("SELECT * FROM af_alert_bank WHERE alert_id=? AND bank_id=? FOR UPDATE",alertId,bank);
        if(existing!=null){
            boolean unchanged=num(existing,"report_id")==report&&str(existing,"basis").equals(note);
            if(!unchanged){
                conflict(s.count("SELECT COUNT(*) FROM af_command WHERE alert_id=? AND bank_id=?",alertId,bank)==0,"该账户关联已用于指令，不能覆盖原始证据关系");
                s.db.update("UPDATE af_alert_bank SET report_id=?,basis=?,actor_id=?,verified_at=? WHERE alert_id=? AND bank_id=?",report,note,u.id(),s.now(),alertId,bank);
            }else return;
        }else s.db.update("INSERT INTO af_alert_bank(alert_id,bank_id,report_id,basis,actor_id,verified_at) VALUES(?,?,?,?,?,?)",alertId,bank,report,note,u.id(),s.now());
        // A changed evidence/target relationship requires a fresh human approval before issuing funds commands.
        s.db.update("UPDATE af_alert SET needs_review=TRUE,updated_at=? WHERE id=?",s.now(),alertId);
        s.log(u,"核验线索账户关联","alert",alertId,null,null,"举报 #"+report+" → 资金账户 #"+bank+"；"+note);
    }
    @Transactional(isolation=Isolation.READ_COMMITTED)
    public void unlink(User u,long alertId,long bankId,Map<String,Object> b){
        u.roles("CENTER");var a=s.lockAlert(alertId);conflict(!Set.of("已闭环","已驳回","已作废").contains(str(a,"state")),"预警已结束");
        conflict(s.count("SELECT COUNT(*) FROM af_command WHERE alert_id=? AND bank_id=?",alertId,bankId)==0,"该关联已用于指令，不能直接移除；请在原业务下完成处置");String note=required(b,"note",1000);
        conflict(s.db.update("DELETE FROM af_alert_bank WHERE alert_id=? AND bank_id=?",alertId,bankId)>0,"该账户关联已移除");
        s.db.update("UPDATE af_alert SET needs_review=TRUE,updated_at=? WHERE id=?",s.now(),alertId);s.log(u,"撤销未使用账户关联","alert",alertId,null,null,"资金账户 #"+bankId+"；"+note);
    }
    public void requireTarget(long alertId,long bankId){
        conflict(s.count("SELECT COUNT(*) FROM af_alert_bank WHERE alert_id=? AND bank_id=?",alertId,bankId)>0,"该资金账户与本预警尚无核验关联，请先核验线索账户关系并完成人工复核");
    }
}
