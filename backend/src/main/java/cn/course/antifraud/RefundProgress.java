package cn.course.antifraud;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import static cn.course.antifraud.Support.*;

/** Approval and credit are separate facts; retries must not count the same approval twice. */
final class RefundProgress {
    private RefundProgress() {}

    // Read approval, freeze and all actual credits in one statement snapshot for both list APIs.
    static final String LIST_FIELDS=",c.kind progress_original_kind,c.state progress_original_state,c.expires_at progress_expires_at,c.remaining progress_remaining,(SELECT COALESCE(SUM(x.actual),0) FROM af_command x WHERE x.application_id=a.id AND x.kind='返还') credited";

    static void decorateListRow(Map<String,Object> row,LocalDateTime now){
        var original=map("kind",row.remove("progress_original_kind"),"state",row.remove("progress_original_state"),
            "expires_at",row.remove("progress_expires_at"),"remaining",row.remove("progress_remaining"));
        if(str(row,"kind").equals("返还")){
            var progress=describe(row,original,amount(row,"credited"),str(row,"execution_state"),now);
            row.put("progress_state",progress.get("state"));
        }else{
            row.put("credited",null);
            row.put("progress_state",str(row,"execution_state").isBlank()?row.get("state"):row.get("execution_state"));
        }
    }

    static Map<String,Object> describe(Map<String,Object> application,Map<String,Object> original,
                                      List<Map<String,Object>> commands,LocalDateTime now){
        var refunds=commands.stream().filter(c->str(c,"kind").equals("返还")).toList();
        BigDecimal credited=refunds.stream().map(c->amount(c,"actual")).reduce(BigDecimal.ZERO,BigDecimal::add);
        return describe(application,original,credited,refunds.isEmpty()?"":str(refunds.getLast(),"state"),now);
    }

    private static Map<String,Object> describe(Map<String,Object> application,Map<String,Object> original,
                                              BigDecimal credited,String latestState,LocalDateTime now){
        BigDecimal approved=amount(application,"approved");
        String state,note;
        if(credited.signum()>0){
            boolean full=approved.signum()>0&&credited.compareTo(approved)>=0;
            if(full){
                state="已成功入账";note="本申请核定款项已转入本人收款账户。";
            }else if(!Commands.liveFreeze(original,now)){
                state="部分入账（已结束）";note="原止付已结束、到期或冻结资金已耗尽，已入账金额保留；未入账部分不能基于该止付继续办理。";
            }else{
                state="部分入账";note="本次执行已结束，未执行部分不会自动再次划款。原止付仍有效，如仍有未返还的受损资金，可另行申请并由中心核定。";
            }
        }else if(str(application,"state").equals("已失效")){
            state="申请已失效";note="本申请未入账且已结束，不能继续执行或重发；核定意见及历次执行记录保留，结束原因见办理记录。";
        }else if(str(application,"state").equals("核定通过")){
            if(!Commands.liveFreeze(original,now)){
                state="办理已结束";note="原止付已结束、到期或冻结资金已耗尽，本申请尚未入账，不能继续执行或重发。";
            }else if(Set.of("未处理","拒绝执行","已拒绝").contains(latestState)){
                state="执行失败";note="本申请尚未入账。中心核实当前可用冻结金额后，可从最新返还指令复核重发。";
            }else{
                state="等待执行";note="申请已核定，款项尚未入账，等待支付机构执行并提交回执。";
            }
        }else{
            state=str(application,"state").equals("核定驳回")?"未通过核定":"等待核定";
            note="尚未发生资金划转；审核通过后仍须由支付机构执行返还。";
        }
        return map("state",state,"credited",credited,"note",note);
    }
}
