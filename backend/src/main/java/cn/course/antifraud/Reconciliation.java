package cn.course.antifraud;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.util.*;
import static cn.course.antifraud.Support.*;

/** Read-only reconciliation, independent of the command execution path. Never repairs balances. */
@Service
public class Reconciliation {
    private final Support s;
    public Reconciliation(Support s){this.s=s;}

    static BigDecimal delta(Map<String,Object> event){
        BigDecimal amount=amount(event,"amount");
        return switch(str(event,"action")){
            case "止付全额执行","止付部分执行","回执更正" -> amount.negate();
            case "解除解除成功","到期解除","返还入账" -> amount;
            case "止付拒绝执行","返还全额执行","返还部分执行","返还拒绝执行","解除拒绝执行" -> BigDecimal.ZERO;
            default -> throw new IllegalArgumentException("未知资金操作："+str(event,"action"));
        };
    }

    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Map<String,Object> check(User user){
        user.roles("CENTER","PAYMENT");
        var banks=s.rows("SELECT b.*,o.opening,o.source baseline_source FROM af_bank b LEFT JOIN af_bank_origin o ON o.bank_id=b.id"+(user.role().equals("PAYMENT")?" WHERE b.org_id=?":"")+" ORDER BY b.id",user.role().equals("PAYMENT")?new Object[]{user.orgId()}:new Object[]{});
        List<Map<String,Object>> accounts=new ArrayList<>();int issueCount=0;
        for(var bank:banks){
            long bankId=num(bank,"id");List<Map<String,Object>> issues=new ArrayList<>();
            var commands=s.rows("SELECT * FROM af_command WHERE bank_id=? ORDER BY id",bankId);
            var events=s.rows("SELECT l.* FROM af_ledger l JOIN af_command c ON c.id=l.command_id WHERE l.bank_id=? ORDER BY l.id",bankId);
            var receipts=s.rows("SELECT r.* FROM af_receipt r JOIN af_command c ON c.id=r.command_id WHERE c.bank_id=? ORDER BY r.command_id,r.version",bankId);
            Map<Long,List<Map<String,Object>>> eventsByCommand=group(events,"command_id"),receiptsByCommand=group(receipts,"command_id"),childrenByOriginal=group(commands,"original_id");
            Set<Long> roots=new HashSet<>();for(var command:commands)if(str(command,"kind").equals("止付"))roots.add(num(command,"id"));
            if(bank.get("opening")==null)issue(issues,"缺少期初核验基线",bankId,null,null);
            BigDecimal expected=amount(bank,"opening"),frozen=BigDecimal.ZERO,outflow=BigDecimal.ZERO,inflow=BigDecimal.ZERO;
            for(var event:events){
                if(str(event,"action").equals("返还入账")){
                    inflow=inflow.add(amount(event,"amount"));
                    var payment=s.one("SELECT c.actual,a.payee_bank_id FROM af_command c JOIN af_application a ON a.id=c.application_id WHERE c.id=?",event.get("command_id"));
                    equal(issues,"返还入账与执行金额不符",num(event,"command_id"),amount(payment,"actual"),amount(event,"amount"));
                    if(num(payment,"payee_bank_id")!=bankId)issue(issues,"返还收款账户不一致",num(event,"command_id"),null,null);
                }
                try{expected=expected.add(delta(event));equal(issues,"账本余额不连续",num(event,"id"),expected,amount(event,"available_after"));}
                catch(IllegalArgumentException e){issue(issues,"未知账本动作",num(event,"id"),null,null);}
            }
            equal(issues,"账户余额与账本不符",bankId,expected,amount(bank,"available"));
            for(var command:commands){
                long id=num(command,"id");String kind=str(command,"kind");
                var history=receiptsByCommand.getOrDefault(id,List.of());
                var ledger=eventsByCommand.getOrDefault(id,List.of());
                var valid=history.stream().filter(r->yes(r,"valid")).toList();
                if(!history.isEmpty()){
                    if(valid.size()!=1||!yes(history.getLast(),"valid"))issue(issues,"有效回执版本异常",id,null,null);
                    else equal(issues,"指令金额与有效回执不符",id,amount(valid.getFirst(),"amount"),amount(command,"actual"));
                }else if(amount(command,"actual").signum()!=0)issue(issues,"执行金额缺少回执",id,null,null);
                var receiptEvents=ledger.stream().filter(e->!str(e,"action").equals("到期解除")).toList();
                if(receiptEvents.size()!=history.size())issue(issues,"回执与资金流水数量不符",id,null,null);
                for(int i=0;i<Math.min(receiptEvents.size(),history.size());i++){
                    var receipt=history.get(i);var event=receiptEvents.get(i);
                    String action=i==0?kind+str(receipt,"result"):"回执更正";
                    if(!str(event,"action").equals(action)||num(receipt,"version")!=i+1)issue(issues,"回执与账本动作或版本不符",id,null,null);
                    BigDecimal expectedAmount=amount(receipt,"amount").subtract(i==0?BigDecimal.ZERO:amount(history.get(i-1),"amount"));
                    equal(issues,"回执与流水金额不符",num(event,"id"),expectedAmount,amount(event,"amount"));
                }
                if(kind.equals("返还")&&amount(command,"actual").signum()>0){
                    var app=s.one("SELECT payee_bank_id FROM af_application WHERE id=?",command.get("application_id"));
                    if(num(app,"payee_bank_id")>0){var incoming=s.rows("SELECT * FROM af_ledger WHERE command_id=? AND action='返还入账'",id);if(incoming.size()!=1)issue(issues,"返还收款流水缺失或重复",id,null,null);else{equal(issues,"返还转出与入账不一致",id,amount(command,"actual"),amount(incoming.getFirst(),"amount"));if(num(incoming.getFirst(),"bank_id")!=num(app,"payee_bank_id"))issue(issues,"收款流水账户不一致",id,null,null);}}
                }
                if(kind.equals("止付")){
                    BigDecimal returned=BigDecimal.ZERO,reserved=BigDecimal.ZERO,released=BigDecimal.ZERO;
                    for(var child:childrenByOriginal.getOrDefault(id,List.of())){
                        if(str(child,"kind").equals("返还")){
                            returned=returned.add(amount(child,"actual"));
                            if(Set.of("待下发","已送达","受理中").contains(str(child,"state")))reserved=reserved.add(amount(child,"requested"));
                        }else if(str(child,"kind").equals("解除"))released=released.add(amount(child,"actual"));
                        if(num(child,"org_id")!=num(command,"org_id"))issue(issues,"关联指令机构不一致",num(child,"id"),null,null);
                    }
                    for(var event:ledger)if(str(event,"action").equals("到期解除"))released=released.add(amount(event,"amount"));
                    equal(issues,"已返还金额与执行记录不符",id,returned,amount(command,"returned"));
                    equal(issues,"返还占用与待执行指令不符",id,reserved,amount(command,"reserved"));
                    equal(issues,"剩余冻结金额不守恒",id,amount(command,"actual").subtract(returned).subtract(released),amount(command,"remaining"));
                    if(amount(command,"reserved").compareTo(amount(command,"remaining"))>0)issue(issues,"返还占用超过剩余冻结",id,null,null);
                    if(str(command,"state").equals("已解除")&&amount(command,"remaining").signum()!=0)issue(issues,"已解除指令仍有冻结余额",id,null,null);
                    frozen=frozen.add(amount(command,"remaining"));outflow=outflow.add(returned);
                }else{
                    if(!roots.contains(num(command,"original_id")))issue(issues,"关联原止付账户不一致或缺失",id,null,null);
                }
            }
            equal(issues,"账户总资金不守恒",bankId,amount(bank,"opening").add(inflow).subtract(outflow),amount(bank,"available").add(frozen));
            issueCount+=issues.size();accounts.add(map("id",bankId,"account",mask(s.decrypt(bank.get("number_enc"))),"opening",bank.get("opening"),"baseline",bank.get("baseline_source"),"available",bank.get("available"),"expectedAvailable",expected,"frozen",frozen,"outflow",outflow,"inflow",inflow,"ledgerCount",events.size(),"ok",issues.isEmpty(),"issues",issues));
        }
        return map("checkedAt",s.now(),"ok",issueCount==0,"issueCount",issueCount,"accounts",accounts);
    }
    private static Map<Long,List<Map<String,Object>>> group(List<Map<String,Object>> rows,String key){
        Map<Long,List<Map<String,Object>>> grouped=new HashMap<>();
        for(var row:rows)grouped.computeIfAbsent(num(row,key),id->new ArrayList<>()).add(row);
        return grouped;
    }
    private static void equal(List<Map<String,Object>> issues,String check,long reference,BigDecimal expected,BigDecimal actual){if(expected.compareTo(actual)!=0)issue(issues,check,reference,expected,actual);}
    private static void issue(List<Map<String,Object>> issues,String check,long reference,Object expected,Object actual){issues.add(map("check",check,"reference",reference,"expected",expected,"actual",actual));}
}
