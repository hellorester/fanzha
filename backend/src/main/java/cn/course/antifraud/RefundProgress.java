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

    static Map<String,Object> describe(Map<String,Object> application,Map<String,Object> original,
                                      List<Map<String,Object>> commands,LocalDateTime now){
        var refunds=commands.stream().filter(c->str(c,"kind").equals("返还")).toList();
        BigDecimal credited=refunds.stream().map(c->amount(c,"actual")).reduce(BigDecimal.ZERO,BigDecimal::add);
        BigDecimal approved=amount(application,"approved");
        String state,note;
        if(credited.signum()>0){
            boolean full=approved.signum()>0&&credited.compareTo(approved)>=0;
            state=full?"已成功入账":"部分入账";
            note=full?"本申请核定款项已转入本人收款账户。":"以实际入账金额为准；本次执行已结束，未执行部分不会自动再次划款。";
        }else if(str(application,"state").equals("已失效")){
            state="申请已失效";note="本申请未入账且已结束，不能继续执行或重发；核定意见及历次执行记录保留，结束原因见办理记录。";
        }else if(str(application,"state").equals("核定通过")){
            if(!Commands.liveFreeze(original,now)){
                state="办理已结束";note="原止付已结束、到期或冻结资金已耗尽，本申请尚未入账，不能继续执行或重发。";
            }else if(!refunds.isEmpty()&&Set.of("未处理","拒绝执行","已拒绝").contains(str(refunds.getLast(),"state"))){
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
